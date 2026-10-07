package com.qisheng.chess.pvp;

import com.qisheng.chess.engine.BoardRegistry;
import com.qisheng.chess.engine.BoardVariant;
import com.qisheng.chess.engine.gomoku.GomokuBoard;
import com.qisheng.chess.engine.go.GoBoard;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Regression tests for the placement action used by gomoku and go.
 *
 * <p>Prior to v0.4.14 the GUI sent a click as {@code ACTION_MOVE} with
 * {@code src == dst} and the server had a hacky {@code isPlacementOnly()}
 * guard around the {@code SOURCE_MISMATCH} check to make it work.
 * That approach mixed two unrelated semantics (move piece / drop stone)
 * behind a single packet, which broke the moment the engine API and the
 * wire API diverged (Go placement wants a single square; Go pass wants
 * the {@code (-1, -1)} sentinel; chess wants a true src→dst pair).
 *
 * <p>v0.4.14 splits the wire into {@code ACTION_PLACE} (single square,
 * gomoku + go), {@code ACTION_MOVE} (xiangqi + chess, src→dst), and
 * {@code ACTION_PASS} (go, no args). The server-side rules are
 * {@code tryPlace} / {@code tryPlace} + {@link com.qisheng.chess.engine.BoardVariant#canPlace canPlace}
 * / {@link com.qisheng.chess.engine.BoardVariant#applyPlace applyPlace}
 * — fully independent of the move path.
 */
public class PlacementMoveTest {

    private static final UUID RED = UUID.randomUUID();
    private static final UUID BLACK = UUID.randomUUID();

    private static GameSession newPlayingPvC(BoardVariant v) {
        GameSession s = new GameSession();
        s.setVariantId(v.id());
        s.setMode(BoardMode.PVC);
        s.setState(GameState.PLAYING);
        s.setRedPlayer(RED);
        s.setBlackPlayer(BLACK);
        return s;
    }

    @Test
    public void gomokuSingleClickPlacementPlacesStone() {
        BoardVariant gomoku = BoardRegistry.getByIdOrDefault("gomoku");
        GameSession s = newPlayingPvC(gomoku);
        s.setSdPlayer(0);
        int center = gomoku.indexForFileRank(7, 7);
        int before = s.getBoardState() instanceof GomokuBoard g
                ? g.squares[center] : -1;
        assertEquals(GomokuBoard.EMPTY, before);

        GameLogic.MoveOutcome out = GameLogic.tryPlace(s, RED, center);
        assertEquals(GameLogic.MoveOutcome.OK, out);

        GomokuBoard board = (GomokuBoard) s.getBoardState();
        assertEquals(GomokuBoard.BLACK, board.squares[center]);
        assertEquals(1, board.moveCount);
        assertEquals(1, s.getSdPlayer());
    }

    @Test
    public void goSingleClickPlacementPlacesStone() {
        BoardVariant go = BoardRegistry.getByIdOrDefault("go19");
        GameSession s = newPlayingPvC(go);
        s.setSdPlayer(0);
        int center = go.indexForFileRank(9, 9);
        int before = s.getBoardState() instanceof GoBoard g
                ? g.squares[center] : -1;
        assertEquals(GoBoard.EMPTY, before);

        GameLogic.MoveOutcome out = GameLogic.tryPlace(s, RED, center);
        assertEquals(GameLogic.MoveOutcome.OK, out);

        GoBoard board = (GoBoard) s.getBoardState();
        assertEquals(GoBoard.BLACK, board.squares[center]);
        assertEquals(1, s.getSdPlayer());
    }

    @Test
    public void gomokuPlacementOnOccupiedSquareRejected() {
        BoardVariant gomoku = BoardRegistry.getByIdOrDefault("gomoku");
        GameSession s = newPlayingPvC(gomoku);
        s.setSdPlayer(0);
        int sq = gomoku.indexForFileRank(7, 7);

        GameLogic.tryPlace(s, RED, sq);
        // Black's turn now. Place the same square — should be rejected
        // because the square is no longer empty.
        GameLogic.MoveOutcome out = GameLogic.tryPlace(s, BLACK, sq);
        assertEquals(GameLogic.MoveOutcome.ILLEGAL_MOVE, out);
    }

    @Test
    public void gomokuPlacementFlipsSdPlayerAfterFirstPlacement() {
        BoardVariant gomoku = BoardRegistry.getByIdOrDefault("gomoku");
        GameSession s = newPlayingPvC(gomoku);
        s.setSdPlayer(0);
        int a = gomoku.indexForFileRank(7, 7);
        int b = gomoku.indexForFileRank(7, 8);

        GameLogic.tryPlace(s, RED, a);
        assertEquals(1, s.getSdPlayer());
        GameLogic.MoveOutcome out = GameLogic.tryPlace(s, BLACK, b);
        assertEquals(GameLogic.MoveOutcome.OK, out);
        assertEquals(0, s.getSdPlayer());
    }

    @Test
    public void placementVariantBypassesSelectPointMismatch() {
        // Sanity: with placement variants, getSelectPoint() is -1 by default
        // (we never set it), and the placement still succeeds. This is the
        // regression guard that ensures tryMove no longer needs a hacky
        // isPlacementOnly() bypass.
        BoardVariant gomoku = BoardRegistry.getByIdOrDefault("gomoku");
        GameSession s = newPlayingPvC(gomoku);
        s.setSdPlayer(0);
        assertEquals(-1, s.getSelectPoint());

        int sq = gomoku.indexForFileRank(7, 7);
        GameLogic.MoveOutcome out = GameLogic.tryPlace(s, RED, sq);
        assertEquals(GameLogic.MoveOutcome.OK, out);
        // tryPlace resets selectPoint on success so the GUI never paints a
        // stale highlight across the next frame.
        assertEquals(-1, s.getSelectPoint());
    }

    @Test
    public void placementVariantRejectsActionMoveForHuman() {
        // After v0.4.14 the human path for placement is tryPlace, not tryMove.
        // A human who accidentally sends ACTION_MOVE for a placement variant
        // gets SOURCE_MISMATCH (no selectPoint to match) — the client will
        // never do this, but a malicious client shouldn't be able to abuse the
        // isPlacementOnly() bypass that v0.4.13 added.
        BoardVariant gomoku = BoardRegistry.getByIdOrDefault("gomoku");
        GameSession s = newPlayingPvC(gomoku);
        s.setSdPlayer(0);
        int sq = gomoku.indexForFileRank(7, 7);
        GameLogic.MoveOutcome out = GameLogic.tryMove(s, RED, sq, sq);
        assertEquals(GameLogic.MoveOutcome.SOURCE_MISMATCH, out);
    }

    @Test
    public void tryEnginePlacePlacesStoneForComputer() {
        // Mirror of gomokuSingleClickPlacementPlacesStone but for the AI:
        // PvcController.apply uses tryEnginePlace for placement variants.
        // BLACK stays null so isComputerToMove() returns true once the side
        // to move flips to 1 (computer plays the empty seat). The board's
        // own sdPlayer also has to be flipped — the session and the board
        // track the side to move separately, and applyPlace consults the
        // board's copy.
        BoardVariant gomoku = BoardRegistry.getByIdOrDefault("gomoku");
        GameSession s = new GameSession();
        s.setVariantId(gomoku.id());
        s.setMode(BoardMode.PVC);
        s.setState(GameState.PLAYING);
        s.setRedPlayer(RED);
        // BLACK left null on purpose.
        s.setSdPlayer(1);
        gomoku.setSideToMove(s.getBoardState(), 1);

        int sq = gomoku.indexForFileRank(7, 7);

        GameLogic.MoveOutcome out = GameLogic.tryEnginePlace(s, sq);
        assertEquals(GameLogic.MoveOutcome.OK, out);

        GomokuBoard board = (GomokuBoard) s.getBoardState();
        assertEquals(GomokuBoard.WHITE, board.squares[sq]);
        assertEquals(0, s.getSdPlayer());
    }

    @Test
    public void tryEnginePlaceRejectsWhenComputerNotToMove() {
        // The computer must own the seat gate even for tryEnginePlace, the
        // same way tryEngineMove does. RED occupies side 0, BLACK is null
        // (computer), but we set sdPlayer = 0 — the human's turn — so the
        // computer must not be allowed to move.
        BoardVariant gomoku = BoardRegistry.getByIdOrDefault("gomoku");
        GameSession s = new GameSession();
        s.setVariantId(gomoku.id());
        s.setMode(BoardMode.PVC);
        s.setState(GameState.PLAYING);
        s.setRedPlayer(RED);
        // BLACK left null on purpose (computer).
        s.setSdPlayer(0); // human's turn

        int sq = gomoku.indexForFileRank(7, 7);

        GameLogic.MoveOutcome out = GameLogic.tryEnginePlace(s, sq);
        assertEquals(GameLogic.MoveOutcome.NOT_YOUR_TURN, out);
    }
}