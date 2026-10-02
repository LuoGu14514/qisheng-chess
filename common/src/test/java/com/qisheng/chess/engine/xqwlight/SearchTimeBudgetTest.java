package com.qisheng.chess.engine.xqwlight;

import com.qisheng.chess.engine.ChineseChessEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The search must honour its time budget.
 *
 * <p>Before the budget was pushed down into {@code searchFull}/{@code searchQuiesc},
 * {@code searchMain} only compared the clock <em>between</em> iterations, so a single
 * iteration could run for an unbounded time. These tests pin the new behaviour: a
 * bounded search stops near its budget, still hands back a playable legal move, and
 * never leaves the position half-mutated.
 *
 * <p>Note that this jar ships no opening book ({@code Position.bookSize == 0}), so
 * {@code bookMove()} returns 0 and {@code searchMain} really does search.
 */
@Timeout(60)
class SearchTimeBudgetTest {

    private static final String INITIAL_FEN =
            "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR";

    private static Position initial() {
        Position pos = ChineseChessEngine.parseFen(INITIAL_FEN);
        assertNotNull(pos, "the initial FEN must parse");
        return pos;
    }

    /**
     * Plays {@code halfMoves} plies, always picking the (i * 7 + k)-th generated move
     * that the engine accepts. Deterministic, and guarantees a legal, reachable
     * position — safer than hand-writing a midgame FEN.
     */
    private static Position playSomeMoves(int halfMoves) {
        Position pos = initial();
        int[] mvs = new int[128];
        for (int i = 0; i < halfMoves; i++) {
            int genMoves = pos.generateAllMoves(mvs);
            int played = 0;
            for (int k = 0; k < genMoves; k++) {
                int mv = mvs[(i * 7 + k) % genMoves];
                if (pos.makeMove(mv)) {
                    played = mv;
                    break;
                }
            }
            assertTrue(played != 0, "expected a legal move at ply " + i);
        }
        return pos;
    }

    private static long millisSince(long nanoTime) {
        return (System.nanoTime() - nanoTime) / 1_000_000L;
    }

    @Test
    void searchStopsNearItsBudget() {
        Position pos = playSomeMoves(16);
        String before = pos.toFen();
        Search search = new Search(pos, 16);

        long t0 = System.nanoTime();
        int mv = search.searchMain(64, 60);
        long elapsed = millisSince(t0);

        assertTrue(mv > 0, "a bounded search must still return a move");
        assertTrue(ChineseChessEngine.canMove(pos, Position.SRC(mv), Position.DST(mv)),
                "search returned an illegal move: " + mv);
        assertEquals(before, pos.toFen(), "search must leave the position untouched");
        assertTrue(elapsed < 5_000L, "a 60 ms budget took " + elapsed + " ms");
    }

    @Test
    void tinyBudgetStillReturnsPlayableMove() {
        Position pos = playSomeMoves(8);
        Search search = new Search(pos, 12);

        long t0 = System.nanoTime();
        int mv = search.searchMain(64, 1);
        long elapsed = millisSince(t0);

        assertTrue(mv > 0, "even a 1 ms budget must hand back a move (fallbackMove)");
        assertTrue(ChineseChessEngine.canMove(pos, Position.SRC(mv), Position.DST(mv)),
                "fallback move is illegal: " + mv);
        assertTrue(elapsed < 5_000L, "a 1 ms budget took " + elapsed + " ms");
    }

    @Test
    void searchNeverLeavesTheBoardHalfMoved() {
        Position pos = playSomeMoves(20);
        byte[] squares = pos.squares.clone();
        int sdPlayer = pos.sdPlayer;
        int zobristKey = pos.zobristKey;
        int zobristLock = pos.zobristLock;
        int moveNum = pos.moveNum;
        int vlWhite = pos.vlWhite;
        int vlBlack = pos.vlBlack;
        Search search = new Search(pos, 14);

        for (int i = 0; i < 4; i++) {
            int mv = search.searchMain(64, 20);
            assertTrue(mv > 0, "round " + i + " returned no move");
            assertArrayEquals(squares, pos.squares, "board mutated after round " + i);
            assertEquals(sdPlayer, pos.sdPlayer, "side to move changed after round " + i);
            assertEquals(zobristKey, pos.zobristKey, "zobristKey changed after round " + i);
            assertEquals(zobristLock, pos.zobristLock, "zobristLock changed after round " + i);
            assertEquals(moveNum, pos.moveNum, "moveNum changed after round " + i);
            assertEquals(vlWhite, pos.vlWhite, "vlWhite changed after round " + i);
            assertEquals(vlBlack, pos.vlBlack, "vlBlack changed after round " + i);
        }
    }

    @Test
    void zeroDepthSearchDoesNotDivideByZero() {
        Position pos = playSomeMoves(6);
        String before = pos.toFen();
        Search search = new Search(pos, 10);

        // The iteration loop never runs, so the clock is never read: allMillis stays 0
        // and getKNPS() used to throw ArithmeticException.
        int mv = search.searchMain(0, 30);

        assertTrue(mv > 0, "a zero-depth search must still return a legal move");
        assertTrue(ChineseChessEngine.canMove(pos, Position.SRC(mv), Position.DST(mv)),
                "zero-depth move is illegal: " + mv);
        assertEquals(0, search.getKNPS(), "KNPS must be 0 when no time was measured");
        assertEquals(before, pos.toFen(), "zero-depth search must not touch the position");
        assertTrue(pos.distance == 0, "searchMain resets the search distance");
    }

    @Test
    void unrestrictedSearchStillWorks() {
        Position pos = playSomeMoves(12);
        Search search = new Search(pos, 16);

        int mv = search.searchMain(3, 60_000);

        assertTrue(mv > 0, "an unbudgeted search must return a move");
        assertTrue(ChineseChessEngine.canMove(pos, Position.SRC(mv), Position.DST(mv)),
                "move from a completed search is illegal: " + mv);
        assertTrue(search.getKNPS() > 0, "a completed search should report a throughput");
    }
}
