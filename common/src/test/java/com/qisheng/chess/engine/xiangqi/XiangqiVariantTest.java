package com.qisheng.chess.engine.xiangqi;

import com.qisheng.chess.engine.BoardRegistry;
import com.qisheng.chess.engine.BoardVariant;
import com.qisheng.chess.engine.Move;
import com.qisheng.chess.engine.xqwlight.Position;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.qisheng.chess.util.CChessUtil.INIT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the {@link XiangqiVariant} wrapper around xqwlight's
 * {@link Position}. Every assertion exercises the variant API rather than the
 * raw {@code Position} methods, so the abstraction layer stays correct.
 */
class XiangqiVariantTest {

    private static final BoardVariant V = BoardRegistry.getById("xiangqi");

    @Test
    @DisplayName("ID 是稳定的 'xiangqi'")
    void idIsStable() {
        assertEquals("xiangqi", V.id());
    }

    @Test
    @DisplayName("尺寸是 9×10 = 90 有效格,wire 位图 32 字节")
    void sizes() {
        assertEquals(9, V.boardFiles());
        assertEquals(10, V.boardRanks());
        assertEquals(256, V.totalSquares());
        assertEquals(32, V.legalDestsBitmapSize());
    }

    @Test
    @DisplayName("INIT_FEN 走 toFen/parseState 回到自己")
    void fenRoundTripInitial() {
        String fen = V.initialFen();
        var state = V.parseState(fen);
        assertNotNull(state);
        String back = V.toFen(state);
        // Position.toFen() 总是带 ' w'/' b' 后缀;INIT 是没有后缀的纯棋盘 FEN。
        // 把后缀补上再做对比,避免 toFen 多走一步。
        assertEquals(fen + " w", back);
    }

    @Test
    @DisplayName("Position 实现了 BoardState 标记接口")
    void positionIsBoardState() {
        var state = V.parseState(INIT);
        assertNotNull(state);
        assertTrue(state instanceof Position);
    }

    @Test
    @DisplayName("canMove 通过合法车代理总门")
    void canMoveGoesThroughFacade() {
        var state = V.parseState(INIT);
        assertNotNull(state);
        // 红车 a-file = sq(0, 9) = 195。INIT 红底整排 RNBAKABNR 满员,所以车不能
        // 横走(a→b 是红马,b→c 是红相...),只能向上走一格到 sq(0, 8) = y=11 = 空行 "9"。
        int redRookA = sq(0, 9);
        assertTrue(V.canMove(state, redRookA, sq(0, 8)));
        // 红车不能斜走
        assertFalse(V.canMove(state, redRookA, sq(1, 8)));
    }

    @Test
    @DisplayName("applyMove 走的是 Position.makeMove,xqwlight 翻 sdPlayer")
    void applyMoveFlipsSdPlayer() {
        var state = V.parseState(INIT);
        assertNotNull(state);
        assertEquals(0, V.sideToMove(state));
        // 红车 a-file → 上空一格 (同 fix 见 canMoveGoesThroughFacade)
        int src = sq(0, 9);
        int dst = sq(0, 8);
        assertTrue(V.applyMove(state, src, dst));
        assertEquals(1, V.sideToMove(state));
    }

    @Test
    @DisplayName("isInCheck 不污染 caller state(sdPlayer 临时翻转后还原)")
    void isInCheckDoesNotPollute() {
        // "将帅对脸" 经典例:rhebtc + bhebtc 无其他子,红王 e1 = e4 黑王 e10 = e7
        // FEN: 4k4/9/9/9/9/9/9/9/9/4K4 w → 红回合,红王 (4,4),黑王(44),不在对面。
        // 这里只验证 API 行为:开局非将。
        var state = V.parseState(INIT);
        assertNotNull(state);
        boolean inCheck = V.isInCheck(state, 0);
        // 开局红方不可能被将军(红王在九宫,周围全是己方己方子)
        assertFalse(inCheck);
        // sdPlayer 不能被改写
        assertEquals(0, V.sideToMove(state));
    }

    @Test
    @DisplayName("firstLegalMove 总是返一个 Move,即使 from=Move.NONE)")
    void firstLegalMoveReturnsAtLeastOne() {
        Move mv = V.firstLegalMove(INIT);
        // xqwlight generateAllMoves 返 mv 整数 = src + (dst<<8);Move 包装 (src, dst)
        assertTrue(mv.src() >= 0 && mv.dst() >= 0 && mv.src() != mv.dst());
    }

    @Test
    @DisplayName("searchBestMove 退到 firstLegalMove,从 INIT 返合法 Move")
    void searchBestMoveReturnsLegalMove() {
        Move mv = V.searchBestMove(INIT, 1, 100);
        assertTrue(mv.src() >= 0 && mv.dst() >= 0 && mv.src() != mv.dst());
    }

    @Test
    @DisplayName("isStalemate 总是 false(xiangqi 没有逼和判定)")
    void xiangqiHasNoStalemate() {
        var state = V.parseState(INIT);
        assertNotNull(state);
        assertFalse(V.isStalemate(state));
    }

    // ---- helpers ----

    private static int sq(int file, int rank) {
        // 与 ChineseChessEngineTest 相同约定:file 0..8, rank 0..9 但 rank 0 = 黑顶
        // (与 FEN 第一段对应的视角)。Position 内部 COORD_XY(x,y) = x + (y<<4),
        // FILE_LEFT=3, RANK_TOP=3。rank 0 → y = 3 (黑底 back rank),rank 9 → y = 12
        // (红底 back rank)。
        return Position.COORD_XY(file + Position.FILE_LEFT,
                rank + Position.RANK_TOP);
    }
}