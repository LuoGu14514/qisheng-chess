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
 * Regression tests for the single-click placement flow used by
 * gomoku and go variants.
 *
 * <p>As of v0.4.13, the GUI sends a click as {@code ACTION_MOVE}
 * with {@code src == dst}, and the server must accept it without
 * requiring the player to first issue a separate {@code ACTION_SELECT}.
 * The earlier check {@code session.getSelectPoint() != src} rejected
 * every placement because selectPoint was {@code -1} for these
 * variants.
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

        GameLogic.MoveOutcome out = GameLogic.tryMove(s, RED, center, center);
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

        GameLogic.MoveOutcome out = GameLogic.tryMove(s, RED, center, center);
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

        GameLogic.tryMove(s, RED, sq, sq);
        // Black moved; sdPlayer is now 1. White's turn. Place the same square
        // — should be rejected because the square is no longer empty.
        GameLogic.MoveOutcome out = GameLogic.tryMove(s, BLACK, sq, sq);
        assertEquals(GameLogic.MoveOutcome.ILLEGAL_MOVE, out);
    }

    @Test
    public void gomokuPlacementFlipsSdPlayerAfterFirstPlacement() {
        BoardVariant gomoku = BoardRegistry.getByIdOrDefault("gomoku");
        GameSession s = newPlayingPvC(gomoku);
        s.setSdPlayer(0);
        int a = gomoku.indexForFileRank(7, 7);
        int b = gomoku.indexForFileRank(7, 8);

        GameLogic.tryMove(s, RED, a, a);
        assertEquals(1, s.getSdPlayer());
        GameLogic.MoveOutcome out = GameLogic.tryMove(s, BLACK, b, b);
        assertEquals(GameLogic.MoveOutcome.OK, out);
        assertEquals(0, s.getSdPlayer());
    }

    @Test
    public void placementVariantBypassesSelectPointMismatch() {
        // Sanity: with placement variants, getSelectPoint() is -1 by default,
        // and yet the placement still succeeds. This is the regression guard
        // for the SOURCE_MISMATCH check that used to fire before the fix.
        BoardVariant gomoku = BoardRegistry.getByIdOrDefault("gomoku");
        GameSession s = newPlayingPvC(gomoku);
        s.setSdPlayer(0);
        assertEquals(-1, s.getSelectPoint());

        int sq = gomoku.indexForFileRank(7, 7);
        GameLogic.MoveOutcome out = GameLogic.tryMove(s, RED, sq, sq);
        assertEquals(GameLogic.MoveOutcome.OK, out);
        assertNotEquals(-1, s.getSelectPoint());
    }
}