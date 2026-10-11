package com.qisheng.chess.pvp;

import com.qisheng.chess.engine.BoardRegistry;
import com.qisheng.chess.engine.BoardVariant;
import com.qisheng.chess.engine.gomoku.GomokuBoard;
import com.qisheng.chess.engine.go.GoBoard;
import com.qisheng.chess.engine.go.GoVariant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for the v0.4.8 winner-determination bug (re-opened in v0.4.20
 * audit and correctly fixed by remapping GomokuBoard.BLACK → RED_WIN and
 * GomokuBoard.WHITE → BLACK_WIN).
 *
 * <p>Before the original fix, {@link GameSession#checkGameOver()} declared
 * the winner by reading {@code sdPlayer} using the Xiangqi convention (the
 * mated side has no legal moves, so {@code sdPlayer} points at the loser).
 * That mapping inverts for Gomoku and Go: in those variants the winner is
 * stored on the board itself ({@code GomokuBoard.winner} or the area-score
 * delta), and {@code sdPlayer} after a winning move points at the side
 * whose turn just <em>began</em> — which is the loser. The naive
 * sdPlayer-flip would declare the wrong colour the victor.
 *
 * <p>Even after v0.4.8 added the {@code GomokuVariant}/{@code GoVariant}
 * special-cases, the seat mapping inside those branches was inverted
 * (Gomoku BLACK → BLACK_WIN) because the author forgot that the first
 * joiner takes the RED seat and plays first, which means Gomoku's BLACK
 * stones are physically placed by the session's RED player. This test
 * fixes the assertion to match the correct mapping.
 *
 * <p>Seat convention (matches SessionManager.joinGame):
 * <ul>
 *   <li>{@code sdPlayer=0} → RED seat (first joiner, plays first)</li>
 *   <li>{@code sdPlayer=1} → BLACK seat (second joiner, plays second)</li>
 *   <li>Gomoku/Go stone BLACK → played by RED seat</li>
 *   <li>Gomoku/Go stone WHITE → played by BLACK seat</li>
 * </ul>
 *
 * <p>Note on the enum: {@link GameResult#RED_WIN} is declared before
 * {@link GameResult#BLACK_WIN}, so their ordinals are 0 and 1
 * respectively. We compare by name to stay robust against reorderings.
 */
class GameSessionWinnerTest {

    /** Walk a sequence of (file, rank) placements on a Gomoku board;
     *  black on even turns, white on odd. Returns the mutated board. */
    private static GomokuBoard runGomoku(int[][] fileRanks) {
        BoardVariant variant = BoardRegistry.getById("gomoku");
        GomokuBoard b = (GomokuBoard) variant.initialState();
        for (int[] fr : fileRanks) {
            int idx = variant.indexForFileRank(fr[0], fr[1]);
            // v0.4.15: Gomoku is placement-only — use applyPlace, not applyMove.
            variant.applyPlace(b, idx);
        }
        return b;
    }

    @Test
    @DisplayName("Gomoku: 黑 5 连后 checkGameOver 报 RED_WIN (黑子在红方座位)")
    void gomokuBlackWinReportsCorrectColor() {
        // 水平 5 连 —— 黑 (5,5)(5,6)(5,7)(5,8)(5,9),白在其他位置
        GomokuBoard blackWin = runGomoku(new int[][]{
                {5, 5}, {0, 0},   // black, white
                {5, 6}, {0, 1},   // black, white
                {5, 7}, {0, 2},   // black, white
                {5, 8}, {0, 3},   // black, white
                {5, 9},           // black 5-in-row 横向
        });
        assertEquals(GomokuBoard.BLACK, blackWin.winner);

        GameSession session = new GameSession();
        session.setVariantId("gomoku");
        session.setBoardState(blackWin);
        // Gomoku BLACK stones were placed by the RED seat (first joiner,
        // plays first), so RED is the winner. After the winning move
        // sdPlayer has flipped to 1 (White's turn) — the Xiangqi-flip
        // trick would declare the wrong colour the victor.
        assertEquals(GameResult.RED_WIN, session.checkGameOver());
    }

    @Test
    @DisplayName("Gomoku: 白 5 连后 checkGameOver 报 BLACK_WIN (白子在黑方座位)")
    void gomokuWhiteWinReportsCorrectColor() {
        // 白 5 连 —— 白 (5,5)(5,6)(5,7)(5,8)(5,9)
        // 黑下在散落位 (0,0)(1,1)(2,2)(0,1)(0,2),故意避开任何 5-线
        GomokuBoard whiteWin = runGomoku(new int[][]{
                {0, 0}, {5, 5},
                {1, 1}, {5, 6},
                {2, 2}, {5, 7},
                {0, 1}, {5, 8},
                {0, 2},           // black 不 5 连
                {5, 9},           // white 5-in-column-5
        });
        assertEquals(GomokuBoard.WHITE, whiteWin.winner);

        GameSession session = new GameSession();
        session.setVariantId("gomoku");
        session.setBoardState(whiteWin);
        // WHITE stones were placed by the BLACK seat (second joiner,
        // plays second), so BLACK is the winner. After the winning move
        // sdPlayer has flipped to 0 (Black's turn) — looks identical to
        // the bare-black-Stones case, which is exactly why this branch
        // needs the explicit board-colour → seat lookup.
        assertEquals(GameResult.BLACK_WIN, session.checkGameOver());
    }

    @Test
    @DisplayName("Go: 两次 pass 后 finished=true, 检测出胜负,不报 DRAW")
    void goTwoPassesEndsGame() {
        GoVariant v9 = (GoVariant) BoardRegistry.getById("go9");
        GoBoard b = (GoBoard) v9.initialState();
        // v0.4.15: Pass is its own action — use applyPass, not applyMove(-1,-1).
        v9.applyPass(b);  // black pass
        v9.applyPass(b);  // white pass
        assertTrue(b.finished);

        GameSession session = new GameSession();
        session.setVariantId("go9");
        session.setBoardState(b);
        GameResult r = session.checkGameOver();
        assertTrue(r == GameResult.RED_WIN || r == GameResult.BLACK_WIN,
                "go finished game should be a win, was " + r);
    }

    @Test
    @DisplayName("Go: 空盘两次 pass 后 scoreDelta=-7.5 (KOMI 给白), BLACK_WIN (白子在黑方座位)")
    void goTwoPassesOnEmptyBoardWhiteWinsByKomi() {
        // First joiner (RED seat) plays Go BLACK first; after two passes
        // no stones have been placed, white gets KOMI 7.5, so
        // scoreDelta = 0 - 7.5 = -7.5 (Go-WHITE ahead).
        // In our seat convention Go-WHITE sits on the session's BLACK
        // seat → BLACK_WIN.
        GoVariant v9 = (GoVariant) BoardRegistry.getById("go9");
        GoBoard b = (GoBoard) v9.initialState();
        v9.applyPass(b);
        v9.applyPass(b);
        assertTrue(b.finished);

        GameSession session = new GameSession();
        session.setVariantId("go9");
        session.setBoardState(b);
        assertEquals(GameResult.BLACK_WIN, session.checkGameOver());
    }
}