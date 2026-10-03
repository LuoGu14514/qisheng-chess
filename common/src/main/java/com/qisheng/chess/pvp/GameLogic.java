package com.qisheng.chess.pvp;

import com.qisheng.chess.engine.BoardState;
import com.qisheng.chess.engine.BoardVariant;
import com.qisheng.chess.engine.xqwlight.Position;
import com.qisheng.chess.util.CChessUtil;

import java.util.UUID;

/**
 * Pure game-rule logic shared by every input path:
 *   - {@link com.qisheng.chess.command.ModCommands} (CLI /qisheng select|move)
 *   - {@link com.qisheng.chess.network.ChessInteractC2SPacket} (C2S packet)
 *   - Future GUI / hotkey handlers
 *
 * <p>As of v0.3.1, game logic dispatches through
 * {@link GameSession#getVariant()} so xiangqi and international chess share
 * the same input path. The xiangqi-specific fallback (the
 * {@code captured()} / {@code setIrrev()} dance that keeps xqwlight's
 * threefold-repetition counter accurate) stays inside
 * {@link #applyXiangqiIrrev} and runs only when the session is actually
 * playing xiangqi — every other variant skips it.
 *
 * Mirrors TLM {@code BlockCChess.use()} core (TartaricAcid/TouhouLittleMaid,
 * 1.18.2, MIT-licensed Java):
 *   1. {@link Position#MOVE} encodes (src, dst)
 *   2. {@link Position#legalMove} checks legality (own-piece move, board bounds,
 *      piece-specific rules). TLM also relies on this.
 *   3. {@link Position#makeMove} flips sdPlayer via changeSide() and returns
 *      false if the move would leave the mover's own king in check.
 *      TLM handles this by sending a "check" message and aborting the move;
 *      here we return MoveOutcome.KING_EXPOSED.
 *   4. {@link Position#captured()} + {@link Position#setIrrev()} — when a
 *      capture happens, reset the irreversible counter so the 3-fold-repetition
 *      check (repStatus(3)) inside {@link CChessUtil#isRepeat} stays accurate.
 *      TLM does the same call.
 *   5. Selection point is preserved at dst (TLM sets it to the destination
 *      square too). The PVP adaptation: we mirror Position.sdPlayer into
 *      GameSession.sdPlayer manually because the session state machine tracks
 *      it independently of the engine board.
 *
 * Forced compromises vs TLM (PVP layer, NOT logic divergences):
 *   - Side check uses the player's role (red or black) rather than TLM's
 *     hard-coded "player is always red".
 *   - Game-over is checked synchronously after each move (TLM defers to the
 *     client-side AI loop and never sees a checkmate on the server).
 *   - No maid AI, no multi-block PART machinery, no BlockJoy / EntitySit.
 */
public final class GameLogic {
    private GameLogic() {}

    public enum SelectOutcome {
        OK,
        NOT_IN_GAME,
        GAME_NOT_PLAYING,
        GAME_FINISHED,
        NOT_YOUR_TURN,
        OUT_OF_BOUNDS,
        EMPTY_SQUARE,
        WRONG_PIECE_SIDE
    }

    public enum MoveOutcome {
        OK,
        NOT_IN_GAME,
        GAME_NOT_PLAYING,
        GAME_FINISHED,
        NOT_YOUR_TURN,
        SOURCE_MISMATCH,
        OUT_OF_BOUNDS,
        ILLEGAL_MOVE,
        KING_EXPOSED
    }

    /** Select a square. Mirrors TLM use() piece-side check. */
    public static SelectOutcome trySelect(GameSession session, UUID playerId, int sq) {
        if (session == null) return SelectOutcome.NOT_IN_GAME;
        GameState state = session.getState();
        if (state == GameState.FINISHED) return SelectOutcome.GAME_FINISHED;
        if (state != GameState.PLAYING) return SelectOutcome.GAME_NOT_PLAYING;

        int role = session.getPlayerRole(playerId);
        if (role != session.getSdPlayer()) return SelectOutcome.NOT_YOUR_TURN;

        BoardVariant v = session.getVariant();
        if (!v.isValidSquare(sq)) return SelectOutcome.OUT_OF_BOUNDS;

        // Toggle: re-selecting the currently selected square deselects it.
        // Lets the client UX work as "click own piece to highlight; click
        // again to cancel" without needing a separate DESELECT action.
        if (sq == session.getSelectPoint()) {
            session.setSelectPoint(-1);
            return SelectOutcome.OK;
        }

        BoardState bs = session.getBoardState();
        byte piece = v.pieceAt(bs, sq);
        if (piece == 0) return SelectOutcome.EMPTY_SQUARE;

        // The variant decides which side a byte represents (xiangqi: red+8 vs
        // black+16; international chess uses the same byte layout by happy
        // coincidence). We only need the role-to-side mapping.
        int pieceSide = v.sideOfPiece(bs, sq);
        boolean playerIsRed = (role == 0);
        if ((pieceSide == 0) != playerIsRed) return SelectOutcome.WRONG_PIECE_SIDE;

        session.setSelectPoint(sq);
        return SelectOutcome.OK;
    }

    /** Execute a move. Mirrors TLM use() core move handling. */
    public static MoveOutcome tryMove(GameSession session, UUID playerId, int src, int dst) {
        if (session == null) return MoveOutcome.NOT_IN_GAME;
        GameState state = session.getState();
        if (state == GameState.FINISHED) return MoveOutcome.GAME_FINISHED;
        if (state != GameState.PLAYING) return MoveOutcome.GAME_NOT_PLAYING;

        int role = session.getPlayerRole(playerId);
        if (role != session.getSdPlayer()) return MoveOutcome.NOT_YOUR_TURN;

        if (session.getSelectPoint() != src) return MoveOutcome.SOURCE_MISMATCH;
        return applyMove(session, src, dst);
    }

    /**
     * Execute a move on behalf of whichever side is to move, with no seat check.
     *
     * <p>For the computer opponent ({@link PvcController}) only: the engine owns
     * no seat, so {@link #tryMove}'s role gate could never pass for it. The gate
     * is replaced — not removed — by
     * {@link GameSession#isComputerToMove()}, so a routing mistake still cannot
     * let the server move a human's piece.
     *
     * <p>There is deliberately no {@code SOURCE_MISMATCH} check: the engine has
     * no selection state to match against.
     */
    public static MoveOutcome tryEngineMove(GameSession session, int src, int dst) {
        if (session == null) return MoveOutcome.NOT_IN_GAME;
        GameState state = session.getState();
        if (state == GameState.FINISHED) return MoveOutcome.GAME_FINISHED;
        if (state != GameState.PLAYING) return MoveOutcome.GAME_NOT_PLAYING;
        if (!session.isComputerToMove()) return MoveOutcome.NOT_YOUR_TURN;
        return applyMove(session, src, dst);
    }

    /**
     * Pass action — currently only meaningful for {@code go9} / {@code go19}.
     * Re-uses {@code Move(-1, -1)} as the sentinel; {@code BoardVariant.applyMove}
     * routes that to {@code applyPass} for Go and rejects it elsewhere.
     */
    public static MoveOutcome tryPass(GameSession session, UUID playerId) {
        if (session == null) return MoveOutcome.NOT_IN_GAME;
        GameState state = session.getState();
        if (state == GameState.FINISHED) return MoveOutcome.GAME_FINISHED;
        if (state != GameState.PLAYING) return MoveOutcome.GAME_NOT_PLAYING;
        int role = session.getPlayerRole(playerId);
        if (role < 0) return MoveOutcome.NOT_IN_GAME;
        if (role != session.getSdPlayer()) return MoveOutcome.NOT_YOUR_TURN;
        BoardVariant v = session.getVariant();
        BoardState bs = session.getBoardState();
        // Variants that don't support pass just return ILLEGAL_MOVE. The Go
        // variants flip sdPlayer via applyPass and check for 2-pass end inside
        // their own applyMove implementation.
        if (!v.canMove(bs, -1, -1)) return MoveOutcome.ILLEGAL_MOVE;
        if (!v.applyMove(bs, -1, -1)) return MoveOutcome.ILLEGAL_MOVE;
        session.setSdPlayer(1 - session.getSdPlayer());
        session.setSelectPoint(-1);
        session.checkGameOver();
        return MoveOutcome.OK;
    }

    /** The shared tail of every move path: validate, mutate, flip, check game-over. */
    private static MoveOutcome applyMove(GameSession session, int src, int dst) {
        BoardVariant v = session.getVariant();
        BoardState bs = session.getBoardState();

        if (!v.isValidSquare(src) || !v.isValidSquare(dst)) {
            return MoveOutcome.OUT_OF_BOUNDS;
        }

        // canMove() rejects src == dst, an empty/off-board source and own-piece
        // captures before the variant's piece tables are touched, so a
        // malformed packet can never throw on the server tick thread.
        if (!v.canMove(bs, src, dst)) return MoveOutcome.ILLEGAL_MOVE;

        // applyMove mutates the board and flips sdPlayer (or its variant-local
        // equivalent). For xiangqi, applying through the variant returns
        // {@code false} when the move would expose the mover's own king —
        // other variants roll check-detection into canMove() already, so they
        // always return {@code true} from applyMove when canMove() did.
        if (!v.applyMove(bs, src, dst)) return MoveOutcome.KING_EXPOSED;

        // Xiangqi-only bookkeeping: a capture flips xqwlight's threefold-
        // repetition irreversible counter so the next isRepeat() check is
        // accurate. International chess's draw clock (halfmoveClock) is
        // updated inside IntChessBoard.makeMove(), nothing for us to do.
        applyXiangqiIrrev(bs);

        // The session's side-to-move is a separate piece of state from the
        // board's own counter (mostly so it can be inspected independently of
        // the engine's internals). Flip it to match.
        session.setSdPlayer(1 - session.getSdPlayer());
        // TLM preserves destination as the next "selected" point.
        session.setSelectPoint(dst);
        // Record the move so the GUI can highlight src / dst, and so a
        // reconnect / rejoin still sees the most recent move. Persisted in
        // GameSession.save() / fromTag().
        session.setLastMoveSource(src);
        session.setLastMoveDest(dst);

        // Game-over check (TLM does not do this server-side; PVP needs it).
        GameResult result = session.checkGameOver();
        if (result != GameResult.ONGOING) {
            session.setResult(result);
            session.setState(GameState.FINISHED);
        }
        return MoveOutcome.OK;
    }

    /**
     * If the live board is xiangqi, run the xqwlight-specific
     * {@code captured()} / {@code setIrrev()} pair so threefold repetition
     * stays accurate. For every other variant this is a no-op — the variant
     * already updates whatever counter it uses inside {@code applyMove}.
     */
    private static void applyXiangqiIrrev(BoardState bs) {
        if (!(bs instanceof Position p)) return;
        if (p.captured()) p.setIrrev();
    }
}