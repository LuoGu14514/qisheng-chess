package com.qisheng.chess.engine.gomoku;

import com.qisheng.chess.engine.BoardRegistry;
import com.qisheng.chess.engine.BoardState;
import com.qisheng.chess.engine.BoardVariant;
import com.qisheng.chess.engine.Move;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the {@link GomokuVariant} wrapper. All assertions go
 * through the {@link BoardVariant} facade rather than the raw
 * {@link GomokuBoard} fields, so the abstraction layer stays correct.
 */
class GomokuVariantTest {

    private static final BoardVariant V = BoardRegistry.getById("gomoku");

    @Test
    @DisplayName("ID 是稳定的 'gomoku'")
    void idIsStable() {
        assertEquals("gomoku", V.id());
    }

    @Test
    @DisplayName("尺寸是 15×15 = 225 有效格,wire 位图 29 字节")
    void sizes() {
        assertEquals(15, V.boardFiles());
        assertEquals(15, V.boardRanks());
        assertEquals(225, V.totalSquares());
        assertEquals(29, V.legalDestsBitmapSize());
    }

    @Test
    @DisplayName("INIT_FEN 是 15 行 '.' + ' b',parseState 回到 toFen")
    void fenRoundTripInitial() {
        String fen = V.initialFen();
        BoardState state = V.parseState(fen);
        assertNotNull(state);
        assertTrue(state instanceof GomokuBoard);
        assertEquals(fen, V.toFen(state));
    }

    @Test
    @DisplayName("开局黑先,sdPlayer=0")
    void blackPlaysFirst() {
        BoardState state = V.initialState();
        assertNotNull(state);
        assertEquals(0, V.sideToMove(state));
    }

    @Test
    @DisplayName("落子:src == dst,空子可下,非空子不可下")
    void placementBasics() {
        BoardState state = V.initialState();
        int h8 = V.indexForFileRank(7, 7);
        // 黑下 h8
        assertTrue(V.canMove(state, h8, h8));
        assertTrue(V.applyMove(state, h8, h8));
        assertEquals(1, V.sideToMove(state));
        // 已下不可再下
        assertFalse(V.canMove(state, h8, h8));
        // 白下方 a1
        int a1 = V.indexForFileRank(0, 0);
        assertTrue(V.canMove(state, a1, a1));
        assertTrue(V.applyMove(state, a1, a1));
        assertEquals(0, V.sideToMove(state));
    }

    @Test
    @DisplayName("canMove 拒绝 src != dst(五子棋只能落子)")
    void canMoveRejectsSrcNeqDst() {
        BoardState state = V.initialState();
        int h8 = V.indexForFileRank(7, 7);
        int a1 = V.indexForFileRank(0, 0);
        assertFalse(V.canMove(state, h8, a1));
    }

    @Test
    @DisplayName("胜局检测:水平五连,黑胜")
    void blackWinsHorizontal() {
        // 构造水平五连:黑 h8-h12,白先不挡
        BoardState state = V.initialState();
        int[] black = {sq(7, 7), sq(7, 8), sq(7, 9), sq(7, 10), sq(7, 11)};
        int[] white = {sq(0, 0), sq(1, 0), sq(2, 0), sq(3, 0), sq(0, 1)};
        for (int i = 0; i < 4; i++) {
            // 黑落
            assertTrue(V.applyMove(state, black[i], black[i]));
            // 白落
            assertTrue(V.applyMove(state, white[i], white[i]));
        }
        // 黑第 5 颗
        assertTrue(V.applyMove(state, black[4], black[4]));
        // 此时胜局已立
        assertTrue(V.isCheckmate(state));
        // winner 字段:detectWinner 应返回 BLACK
        GomokuBoard b = (GomokuBoard) state;
        assertEquals(GomokuBoard.BLACK, b.winner);
        // 黑方后续不能再下
        assertFalse(V.canMove(state, sq(0, 14), sq(0, 14)));
    }

    @Test
    @DisplayName("胜局检测:垂直五连,白胜")
    void whiteWinsVertical() {
        // 构造:白 5 子垂直 a1-a5。黑先 4 子不让挡;然后白下 a5
        BoardState state = V.initialState();
        int[] black = {sq(1, 5), sq(2, 5), sq(3, 5), sq(4, 5)};
        int[] white = {sq(0, 0), sq(0, 1), sq(0, 2), sq(0, 3), sq(0, 4)};
        for (int i = 0; i < 4; i++) {
            assertTrue(V.applyMove(state, black[i], black[i]));
            assertTrue(V.applyMove(state, white[i], white[i]));
        }
        // 黑补一手 (6,6) → sdPlayer=1 (白),但不构成五连
        assertTrue(V.applyMove(state, sq(6, 6), sq(6, 6)));
        // 白下 (0,4) 完成 5 连
        assertTrue(V.applyMove(state, sq(0, 4), sq(0, 4)));
        GomokuBoard b = (GomokuBoard) state;
        assertEquals(GomokuBoard.WHITE, b.winner);
        assertTrue(V.isCheckmate(state));
    }

    @Test
    @DisplayName("胜局检测:对角线五连(↘),黑胜")
    void diagonalWin() {
        BoardState state = V.initialState();
        int[] black = {sq(0, 0), sq(1, 1), sq(2, 2), sq(3, 3), sq(4, 4)};
        int[] white = {sq(8, 0), sq(8, 1), sq(8, 2), sq(8, 3)};
        for (int i = 0; i < 4; i++) {
            assertTrue(V.applyMove(state, black[i], black[i]));
            assertTrue(V.applyMove(state, white[i], white[i]));
        }
        // 循环 8 步:sdPlayer=0(黑),黑先
        assertTrue(V.applyMove(state, black[4], black[4]));
        GomokuBoard b = (GomokuBoard) state;
        assertEquals(GomokuBoard.BLACK, b.winner);
    }

    @Test
    @DisplayName("firstLegalMove 返第一个空格子的 Move(sq, sq)")
    void firstLegalMovePicksFirstEmpty() {
        // 开局 full empty:第一个空 = (0,0)
        Move mv = V.firstLegalMove(V.initialFen());
        assertEquals(0, mv.src());
        assertEquals(0, mv.dst());
    }

    @Test
    @DisplayName("searchBestMove 落在第一个空格上(空盘)")
    void searchBestMoveOnEmptyBoard() {
        Move a = V.searchBestMove(V.initialFen(), 1, 100);
        assertEquals(0, a.src());
        assertEquals(0, a.dst());
    }

    @Test
    @DisplayName("AI 必胜:己方四连开放端点 → 必下到五连")
    void aiTakesWinningFour() {
        // Black to move; black has open-4 at row 7 columns 3..6. The winning
        // move is column 7 (right edge of the 4, completing 5 in a row).
        // Build a FEN where only that one move wins and the AI must take it.
        StringBuilder rows = new StringBuilder();
        for (int r = 15; r >= 1; r--) {
            for (int c = 0; c < 15; c++) rows.append('.');
            if (r > 1) rows.append('/');
        }
        String fen = rows + " b";
        BoardState state = V.parseState(fen);
        assertTrue(state instanceof GomokuBoard);
        GomokuBoard b = (GomokuBoard) state;
        // Place four black stones on row 7 (rank index 6), columns 3,4,5,6.
        for (int c = 3; c <= 6; c++) b.squares[GomokuBoard.sq(c, 6)] = GomokuBoard.BLACK;
        b.moveCount = 4;
        b.sdPlayer = 0;
        String nearWin = V.toFen(state);
        Move a = V.searchBestMove(nearWin, 1, 100);
        // The winning move completes 5-in-a-row at column 7 (or 2).
        int win1 = GomokuBoard.sq(7, 6);
        int win2 = GomokuBoard.sq(2, 6);
        assertTrue(a.src() == win1 || a.src() == win2,
                "expected AI to take one of the two open ends of the 4, got " + a.src());
    }

    @Test
    @DisplayName("isInCheck 永远 false(五子棋无将军)")
    void noCheckConcept() {
        BoardState state = V.initialState();
        assertFalse(V.isInCheck(state, 0));
        assertFalse(V.isInCheck(state, 1));
    }

    @Test
    @DisplayName("pieceAt 返回 stone 字节,sideOfPiece 映射 0/1/-1")
    void pieceAndSideAccessors() {
        BoardState state = V.initialState();
        int h8 = V.indexForFileRank(7, 7);
        V.applyMove(state, h8, h8);
        // 黑刚落,黑子
        assertEquals(GomokuBoard.BLACK, V.pieceAt(state, h8));
        assertEquals(0, V.sideOfPiece(state, h8));
        // 空子
        assertEquals(GomokuBoard.EMPTY, V.pieceAt(state, V.indexForFileRank(0, 0)));
        assertEquals(-1, V.sideOfPiece(state, V.indexForFileRank(0, 0)));
    }

    @Test
    @DisplayName("parseState 拒绝空串和错形状 FEN")
    void parseStateRejectsJunk() {
        assertEquals(null, V.parseState(""));
        assertEquals(null, V.parseState(null));
        // 14 行而非 15
        StringBuilder bad = new StringBuilder();
        for (int i = 0; i < 14; i++) {
            bad.append("...............").append('/');
        }
        bad.setLength(bad.length() - 1);
        assertEquals(null, V.parseState(bad.toString()));
        // 不合法字符
        assertEquals(null, V.parseState("z==============/.............../.............../.............../.............../.............../.............../.............../.............../.............../.............../.............../.............../.............../............... b"));
    }

    @Test
    @DisplayName("开局 14 手后 sdPlayer = 0(14 是偶数,翻 14 次回 0)")
    void sdPlayerFlipsCorrectly() {
        BoardState state = V.initialState();
        for (int i = 0; i < 14; i++) {
            int f = i % 15;
            int r = i / 15;
            int s = V.indexForFileRank(f, r);
            assertTrue(V.applyMove(state, s, s));
        }
        // 14 = 偶数 → 翻 14 次 → sdPlayer = 0 (黑)
        assertEquals(0, V.sideToMove(state));
    }

    @Test
    @DisplayName("isCheckmate 后 pieceFenChar 仍能正常读")
    void pieceFenCharAfterWin() {
        BoardState state = V.initialState();
        int[] black = {sq(7, 7), sq(7, 8), sq(7, 9), sq(7, 10), sq(7, 11)};
        int[] white = {sq(0, 0), sq(1, 0), sq(2, 0), sq(3, 0), sq(0, 1)};
        for (int i = 0; i < 4; i++) {
            V.applyMove(state, black[i], black[i]);
            V.applyMove(state, white[i], white[i]);
        }
        V.applyMove(state, black[4], black[4]);
        assertEquals('x', V.pieceFenChar(state, sq(7, 7)));
        assertEquals('x', V.pieceFenChar(state, sq(7, 11)));
        assertEquals('o', V.pieceFenChar(state, sq(0, 0)));
    }

    @Test
    @DisplayName("toFen 后能再次 parseState 还原")
    void fenRoundTripAfterMove() {
        BoardState state = V.initialState();
        V.applyMove(state, V.indexForFileRank(3, 4), V.indexForFileRank(3, 4));
        V.applyMove(state, V.indexForFileRank(7, 7), V.indexForFileRank(7, 7));
        String fen = V.toFen(state);
        BoardState back = V.parseState(fen);
        assertNotNull(back);
        assertEquals(fen, V.toFen(back));
    }

    @Test
    @DisplayName("Move 包装在 (0,0)..(224,224) 范围内不抛错")
    void moveEncodingSafe() {
        Move m = new Move(112, 112);
        assertEquals(112, m.src());
        assertEquals(112, m.dst());
        // Move.NONE
        assertNotEquals(0, Move.NONE.src());
    }

    @Test
    @DisplayName("平局:empty board 上 isStalemate=false(未满)")
    void stalemateFalseOnEmpty() {
        BoardState empty = V.initialState();
        assertFalse(V.isStalemate(empty));
    }

    // ---- helpers ----

    private static int sq(int file, int rank) {
        return V.indexForFileRank(file, rank);
    }
}
