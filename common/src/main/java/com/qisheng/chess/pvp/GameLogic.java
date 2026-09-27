package com.qisheng.chess.pvp;

import com.qisheng.chess.engine.xqwlight.Position;
import com.qisheng.chess.util.CChessUtil;

import java.util.UUID;

/**
 * Pure game-rule logic shared by every input path:
 *   - {@link com.qisheng.chess.command.ModCommands} (CLI /qisheng select|move)
 *   - {@link com.qisheng.chess.network.ChessInteractC2SPacket} (C2S packet)
 *   - Future GUI / hotkey handlers
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

        if (sq < 0 || sq >= 256) return SelectOutcome.OUT_OF_BOUNDS;

        // Toggle: re-selecting the currently selected square deselects it.
        // Lets the client UX work as "click own piece to highlight; click
        // again to cancel" without needing a separate DESELECT action.
        if (sq == session.getSelectPoint()) {
            session.setSelectPoint(-1);
            return SelectOutcome.OK;
        }

        Position p = session.getChessData();
        byte piece = p.squares[sq];
        if (piece == 0) return SelectOutcome.EMPTY_SQUARE;

        // TLM allows only red-side picks because the player is always red.
        // PVP layer: piece must match the player's role side.
        boolean pieceIsRed = CChessUtil.isRed(piece);
        boolean playerIsRed = (role == 0);
        if (pieceIsRed != playerIsRed) return SelectOutcome.WRONG_PIECE_SIDE;

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
        if (src < 0 || src >= 256 || dst < 0 || dst >= 256) return MoveOutcome.OUT_OF_BOUNDS;

        Position p = session.getChessData();
        int mv = Position.MOVE(src, dst);
        if (!p.legalMove(mv)) return MoveOutcome.ILLEGAL_MOVE;

        // makeMove flips Position.sdPlayer internally via changeSide().
        // Returns false when the move would expose own king (TLM aborts here).
        boolean notChecked = p.makeMove(mv);
        if (!notChecked) return MoveOutcome.KING_EXPOSED;

        // TLM: capture resets the irreversible counter for repStatus(3) accuracy.
        if (p.captured()) {
            p.setIrrev();
        }

        // Mirror Position.sdPlayer into GameSession.sdPlayer (PVP state machine
        // tracks it independently; chessData already flipped itself).
        session.setSdPlayer(1 - session.getSdPlayer());
        // TLM preserves destination as the next "selected" point.
        session.setSelectPoint(dst);

        // Game-over check (TLM does not do this server-side; PVP needs it).
        GameResult result = session.checkGameOver();
        if (result != GameResult.ONGOING) {
            session.setResult(result);
            session.setState(GameState.FINISHED);
        }
        return MoveOutcome.OK;
    }
}