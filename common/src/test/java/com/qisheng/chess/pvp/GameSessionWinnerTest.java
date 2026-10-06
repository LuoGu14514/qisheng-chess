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
 * Regression for the v0.4.8 winner-determination bug.
 *
 * <p>Before the fix, {@link GameSession#checkGameOver()} declared the
 * winner by reading {@code sdPlayer} using the Xiangqi convention (the
 * mated side has no legal moves, so {@code sdPlayer} points at the loser).
 * That mapping inverts for Gomoku and Go: in those variants the winner is
 * stored on the board itself ({@code GomokuBoard.winner} or the area-score
 * delta), and {@code sdPlayer} after a winning move points at the side
 * whose turn just <em>began</em> — which is the loser. The naive
 * sdPlayer-flip would declare the wrong colour the victor.
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
            variant.applyMove(b, idx, idx);  // single-click placement
        }
        return b;
    }

    @Test
    @DisplayName("Gomoku: 黑 5 连后 checkGameOver 报 BLACK_WIN,不是 RED_WIN")
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
        // sdPlayer is now 1 (White's turn) after Black's winning move.
        // The old Xiangqi-flip code would have returned RED_WIN.
        assertEquals(GameResult.BLACK_WIN, session.checkGameOver());
    }

    @Test
    @DisplayName("Gomoku: 白 5 连后 checkGameOver 报 RED_WIN")
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
        // sdPlayer is now 0 (Black's turn) after White's winning move.
        assertEquals(GameResult.RED_WIN, session.checkGameOver());
    }

    @Test
    @DisplayName("Go: 两次 pass 后 finished=true, 检测出胜负,不报 DRAW")
    void goTwoPassesEndsGame() {
        GoVariant v9 = (GoVariant) BoardRegistry.getById("go9");
        GoBoard b = (GoBoard) v9.initialState();
        v9.applyMove(b, -1, -1);  // black pass
        v9.applyMove(b, -1, -1);  // white pass
        assertTrue(b.finished);

        GameSession session = new GameSession();
        session.setVariantId("go9");
        session.setBoardState(b);
        GameResult r = session.checkGameOver();
        assertTrue(r == GameResult.RED_WIN || r == GameResult.BLACK_WIN,
                "go finished game should be a win, was " + r);
    }
}