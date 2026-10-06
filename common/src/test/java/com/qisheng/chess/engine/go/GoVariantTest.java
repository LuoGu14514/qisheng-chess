package com.qisheng.chess.engine.go;

import com.qisheng.chess.engine.BoardRegistry;
import com.qisheng.chess.engine.BoardState;
import com.qisheng.chess.engine.BoardVariant;
import com.qisheng.chess.engine.Move;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for {@link GoVariant} (both 9x9 and 19x19 sizes).
 * All assertions go through the {@link BoardVariant} facade rather than the
 * raw {@link GoBoard} fields.
 */
class GoVariantTest {

    private static final BoardVariant V9  = BoardRegistry.getById("go9");
    private static final BoardVariant V19 = BoardRegistry.getById("go19");

    // ---- Identity / sizes ----

    @Test
    @DisplayName("go9 / go19 ID 稳定,尺寸 9x9 / 19x19")
    void idsAndSizes() {
        assertEquals("go9", V9.id());
        assertEquals("go19", V19.id());
        assertEquals(9,  V9.boardFiles());
        assertEquals(9,  V9.boardRanks());
        assertEquals(81, V9.totalSquares());
        // go9 bitmap = ceil(81/8) = 11
        assertEquals(11, V9.legalDestsBitmapSize());
        assertEquals(19, V19.boardFiles());
        assertEquals(19, V19.boardRanks());
        assertEquals(361, V19.totalSquares());
        // go19 bitmap = ceil(361/8) = 46
        assertEquals(46, V19.legalDestsBitmapSize());
    }

    @Test
    @DisplayName("INIT_FEN 是 size 行 '.' + ' b 0 - 0 0',parseState round-trip")
    void fenRoundTripInitial() {
        String fen = V9.initialFen();
        BoardState state = V9.parseState(fen);
        assertNotNull(state);
        assertTrue(state instanceof GoBoard);
        assertEquals(fen, V9.toFen(state));
    }

    @Test
    @DisplayName("go19 round-trip 同样工作")
    void fenRoundTrip19() {
        String fen = V19.initialFen();
        BoardState state = V19.parseState(fen);
        assertNotNull(state);
        assertEquals(19, ((GoBoard) state).size);
        assertEquals(fen, V19.toFen(state));
    }

    @Test
    @DisplayName("开局黑先,sdPlayer=0")
    void blackPlaysFirst() {
        BoardState state = V9.initialState();
        assertEquals(0, V9.sideToMove(state));
    }

    // ---- Placement ----

    @Test
    @DisplayName("基本落子:黑 e5,白落 a1,翻转 sdPlayer")
    void basicPlacementAndSdFlip() {
        BoardState state = V9.initialState();
        int e5 = V9.indexForFileRank(4, 4);
        assertTrue(V9.canMove(state, e5, e5));
        assertTrue(V9.applyMove(state, e5, e5));
        assertEquals(GoBoard.BLACK, V9.pieceAt(state, e5));
        assertEquals(1, V9.sideToMove(state));
        int a1 = V9.indexForFileRank(0, 0);
        assertTrue(V9.applyMove(state, a1, a1));
        assertEquals(GoBoard.WHITE, V9.pieceAt(state, a1));
        assertEquals(0, V9.sideToMove(state));
    }

    @Test
    @DisplayName("自杀禁止:4 边全黑时不能落中间子")
    void suicideForbidden() {
        // 构造 4 边围 1:黑 (2,1) c2, (2,3) c4, (1,2) b3, (3,2) d3 围 (2,2) c3
        // 步骤1 黑 (2,1) → 白 (任意) → 黑 (2,3) → 白 (任意) → 黑 (1,2) → 白 (任意) → 黑 (3,2) → 白
        // 然后 sdPlayer=1 白手,白下 (2,2) c3 → 自杀被拒
        BoardState s = V9.initialState();
        // 黑 (2,1)
        V9.applyMove(s, V9.indexForFileRank(2, 1), V9.indexForFileRank(2, 1));
        // 白 (4,4)
        V9.applyMove(s, V9.indexForFileRank(4, 4), V9.indexForFileRank(4, 4));
        // 黑 (2,3)
        V9.applyMove(s, V9.indexForFileRank(2, 3), V9.indexForFileRank(2, 3));
        // 白 (4,5)
        V9.applyMove(s, V9.indexForFileRank(4, 5), V9.indexForFileRank(4, 5));
        // 黑 (1,2)
        V9.applyMove(s, V9.indexForFileRank(1, 2), V9.indexForFileRank(1, 2));
        // 白 (4,6)
        V9.applyMove(s, V9.indexForFileRank(4, 6), V9.indexForFileRank(4, 6));
        // 黑 (3,2)
        V9.applyMove(s, V9.indexForFileRank(3, 2), V9.indexForFileRank(3, 2));
        // 白手 sdPlayer=1,白下 c3 (2,2) 自杀被拒
        assertFalse(V9.canMove(s, V9.indexForFileRank(2, 2), V9.indexForFileRank(2, 2)));
    }

    // ---- Capture ----

    @Test
    @DisplayName("提子吃 1 子:黑下填最后一气,黑 captures 计数 +1,白子被移除")
    void captureTakesOneStone() {
        // 构造:黑 c4 e4 d5 三子围白 d4 — 白手时让白下 d4 (1 气),黑下 d3 吃白 d4
        // 步骤:1 黑 c4 / 2 白 任意 / 3 黑 e4 / 4 白 任意 / 5 黑 d5 / 6 白 d4 / 7 黑 d3
        BoardState s = V9.initialState();
        // 1 黑 c4 (2,3)
        assertTrue(V9.applyMove(s, V9.indexForFileRank(2, 3), V9.indexForFileRank(2, 3)));
        // 2 白 任意 (5,5)
        assertTrue(V9.applyMove(s, V9.indexForFileRank(5, 5), V9.indexForFileRank(5, 5)));
        // 3 黑 e4 (4,3)
        assertTrue(V9.applyMove(s, V9.indexForFileRank(4, 3), V9.indexForFileRank(4, 3)));
        // 4 白 任意 (5,6)
        assertTrue(V9.applyMove(s, V9.indexForFileRank(5, 6), V9.indexForFileRank(5, 6)));
        // 5 黑 d5 (3,4)
        assertTrue(V9.applyMove(s, V9.indexForFileRank(3, 4), V9.indexForFileRank(3, 4)));
        // 此时 sdPlayer=1 (白),白下 d4 (3,3):周 (2,3)黑,(4,3)黑,(3,2)空,(3,4)黑 → 1 气 → 合法
        assertTrue(V9.applyMove(s, V9.indexForFileRank(3, 3), V9.indexForFileRank(3, 3)));
        // 此时 sdPlayer=0 (黑),黑下 d3 (3,2):吃白 d4
        GoBoard beforeCap = (GoBoard) s;
        int beforeCount = beforeCap.blackCaptures;
        assertTrue(V9.applyMove(s, V9.indexForFileRank(3, 2), V9.indexForFileRank(3, 2)));
        GoBoard afterCap = (GoBoard) s;
        assertEquals(1, afterCap.blackCaptures - beforeCount);
        // d4 应该是空了
        assertEquals(GoBoard.EMPTY, V9.pieceAt(s, V9.indexForFileRank(3, 3)));
    }

    // ---- Pass / end ----

    @Test
    @DisplayName("Pass:两次 pass 后 finished=true,isCheckmate=true")
    void twoPassesEndsGame() {
        BoardState state = V9.initialState();
        GoBoard b = (GoBoard) state;
        assertFalse(b.finished);
        // 第一次 pass
        ((GoVariant) V9).applyPass(state);
        assertEquals(1, b.passes);
        assertFalse(b.finished);
        assertFalse(V9.isCheckmate(state));
        // 第二次 pass
        ((GoVariant) V9).applyPass(state);
        assertEquals(2, b.passes);
        assertTrue(b.finished);
        assertTrue(V9.isCheckmate(state));
    }

    @Test
    @DisplayName("落子时 passes 重置为 0")
    void moveResetsPasses() {
        BoardState state = V9.initialState();
        GoBoard b = (GoBoard) state;
        b.passes = 1;  // 模拟已经 pass 过一次
        V9.applyMove(state, V9.indexForFileRank(4, 4), V9.indexForFileRank(4, 4));
        assertEquals(0, b.passes);
    }

    @Test
    @DisplayName("finished 棋盘不能再落子也不能 pass")
    void finishedBoardRejectsMoves() {
        BoardState state = V9.initialState();
        GoBoard b = (GoBoard) state;
        b.finished = true;
        assertFalse(V9.canMove(state, V9.indexForFileRank(4, 4), V9.indexForFileRank(4, 4)));
        assertFalse(((GoVariant) V9).applyPass(state));
    }

    // ---- FEN ----

    @Test
    @DisplayName("FEN round-trip preserves captures + passes")
    void fenRoundTripPreservesState() {
        BoardState state = V9.initialState();
        GoBoard b = (GoBoard) state;
        b.blackCaptures = 5;
        b.whiteCaptures = 3;
        b.passes = 1;
        String fen = V9.toFen(state);
        BoardState back = V9.parseState(fen);
        assertNotNull(back);
        GoBoard bb = (GoBoard) back;
        assertEquals(5, bb.blackCaptures);
        assertEquals(3, bb.whiteCaptures);
        assertEquals(1, bb.passes);
    }

    @Test
    @DisplayName("FEN round-trip preserves koSquare = 'a1'")
    void fenRoundTripPreservesKoSquare() {
        BoardState state = V9.initialState();
        GoBoard b = (GoBoard) state;
        b.koSquare = V9.indexForFileRank(0, 0);  // a1
        String fen = V9.toFen(state);
        BoardState back = V9.parseState(fen);
        assertNotNull(back);
        GoBoard bb = (GoBoard) back;
        assertEquals(V9.indexForFileRank(0, 0), bb.koSquare);
    }

    @Test
    @DisplayName("go19 koSquare 在 rank 10..19 (FEN 'a'..'j') round-trip 正确")
    void fenRoundTripGo19KoSquareHighRank() {
        // 回归测试:rank 10-19 的 koSquare 必须编码成 'a'..'j' 才能 round-trip
        // 之前的 charAt(1)-'1' 实现只接受 '1'..'9',会把 koSquare 丢光
        for (int rank = 9; rank < 19; rank++) {  // internal rank 9..18 = FEN rank 10..19
            BoardState s = V19.initialState();
            GoBoard b = (GoBoard) s;
            int sq = V19.indexForFileRank(3, rank);  // d10..d19
            b.koSquare = sq;
            String fen = V19.toFen(s);
            BoardState back = V19.parseState(fen);
            assertNotNull(back, "FEN 解析失败 for rank " + rank + ": " + fen);
            GoBoard bb = (GoBoard) back;
            assertEquals(sq, bb.koSquare, "koSquare round-trip 错 for rank " + rank);
        }
        // go9 不接受 2 字符 rank —— 'a1' 是 file=0, rank=0 (合法 9x9 坐标)
        // 用 'aa' 才能触发 2 字符 rank 拒绝 (charAt(1)='a' 不在 '1'..'9' 也不在 go9 允许范围)
        String go9Fen = "........./........./........./........./........./........./........./........./......... b 0 aa 0 0";
        assertEquals(null, V9.parseState(go9Fen), "go9 应该拒绝 'aa' 这种 2 字符 rank");
    }

    @Test
    @DisplayName("parseState 拒绝错形状 FEN")
    void parseStateRejectsJunk() {
        // 字段数不够
        assertEquals(null, V9.parseState("........./........./........./........./........./........./........./........./......... b 0 - 0"));
        // 行数错 (10 行)
        assertEquals(null, V9.parseState("........../........../........../........../........../........../........../........../........../.......... b 0 - 0 0"));
        // 不合法字符
        assertEquals(null, V9.parseState("z========/========/========/========/========/========/========/========/======== b 0 - 0 0"));
        // koSquare 越界 (z9 在 9x9 越界)
        assertEquals(null, V9.parseState("........./........./........./........./........./........./........./........./......... b 0 z9 0 0"));
        // 负 captures
        assertEquals(null, V9.parseState("........./........./........./........./........./........./........./........./......... b 0 - -1 0"));
    }

    // ---- Simple ko ----

    @Test
    @DisplayName("simple ko:koSquare 指向的格子不可立即落子")
    void simpleKoGuard() {
        BoardState state = V9.initialState();
        GoBoard b = (GoBoard) state;
        b.koSquare = V9.indexForFileRank(0, 0);  // a1
        // a1 不可下 (koSquare guard)
        assertFalse(V9.canMove(state, V9.indexForFileRank(0, 0), V9.indexForFileRank(0, 0)));
        // a2 不受 koSquare 影响
        assertTrue(V9.canMove(state, V9.indexForFileRank(0, 1), V9.indexForFileRank(0, 1)));
    }

    // ---- Search ----

    @Test
    @DisplayName("firstLegalMove 在空棋盘返 (file=0, rank=size-1) 角落")
    void firstLegalMoveEmpty() {
        Move mv = V9.firstLegalMove(V9.initialFen());
        // firstLegalMove 扫描顺序:rank size-1..0, file 0..size-1
        // 空棋盘:第一个空 = (file=0, rank=size-1) = indexForFileRank(0, 8) = 72 (size=9)
        assertEquals(72, mv.src());
        assertEquals(72, mv.dst());
    }

    @Test
    @DisplayName("searchBestMove 走 placementPriority,空盘时挑接近中心的空点")
    void searchBestMovePrefersCentreOnEmptyBoard() {
        Move a = V9.searchBestMove(V9.initialFen(), 1, 100);
        // Empty 9x9 board: every empty square scores >= 0; centre (4,4) = 4 + 4*9 = 40.
        assertEquals(40, a.src());
    }

    // ---- Accessors ----

    @Test
    @DisplayName("pieceAt / sideOfPiece:黑 0,白 1,空 -1")
    void pieceAndSideAccessors() {
        BoardState state = V9.initialState();
        V9.applyMove(state, V9.indexForFileRank(4, 4), V9.indexForFileRank(4, 4));  // 黑
        assertEquals(GoBoard.BLACK, V9.pieceAt(state, V9.indexForFileRank(4, 4)));
        assertEquals(0, V9.sideOfPiece(state, V9.indexForFileRank(4, 4)));
        // 空
        assertEquals(-1, V9.sideOfPiece(state, V9.indexForFileRank(0, 0)));
    }

    @Test
    @DisplayName("go9 与 go19 用相同 API")
    void differentSizesUseSameApi() {
        BoardState s9 = V9.initialState();
        BoardState s19 = V19.initialState();
        assertEquals(0, V9.sideToMove(s9));
        assertEquals(0, V19.sideToMove(s19));
        assertTrue(V9.canMove(s9, V9.indexForFileRank(4, 4), V9.indexForFileRank(4, 4)));
        assertTrue(V19.canMove(s19, V19.indexForFileRank(9, 9), V19.indexForFileRank(9, 9)));
    }

    @Test
    @DisplayName("pieceFenChar 返回 x/o/.")
    void pieceFenCharMapping() {
        BoardState state = V9.initialState();
        V9.applyMove(state, V9.indexForFileRank(4, 4), V9.indexForFileRank(4, 4));
        assertEquals('x', V9.pieceFenChar(state, V9.indexForFileRank(4, 4)));
        assertEquals('.', V9.pieceFenChar(state, V9.indexForFileRank(0, 0)));
    }

    @Test
    @DisplayName("isInCheck 永远 false(围棋无将军)")
    void noCheckConcept() {
        BoardState state = V9.initialState();
        assertFalse(V9.isInCheck(state, 0));
        assertFalse(V9.isInCheck(state, 1));
    }

    @Test
    @DisplayName("AI 1-ply:可提子时必提,不在空盘浪费手")
    void aiTakesObviousCapture() {
        // Black to move. White single stone at (4,4) with no liberties
        // except (4,3), where black is in atari after black's hypothetical
        // placement. Build a board:
        //   row 5 (rank 4): ....W....  (one white stone at file 4)
        //   row 4 (rank 3): ........X (already one black stone blocking south)
        //   row 3 (rank 2): all walls, i.e. (4,2) occupied by black already
        // We'll set up a simpler atari: white stone at (3,3), black stones
        // surrounding it on 3 of 4 sides; the empty side is (4,3) — placing
        // black there captures the white stone.
        GoBoard b = new GoBoard(9);
        b.squares[b.sq(3, 3)] = GoBoard.WHITE;
        b.squares[b.sq(2, 3)] = GoBoard.BLACK;
        b.squares[b.sq(3, 2)] = GoBoard.BLACK;
        b.squares[b.sq(3, 4)] = GoBoard.BLACK;
        // The empty intersection (4,3) is the white stone's only liberty.
        // black to move.
        b.sdPlayer = 0;
        String fen = V9.toFen(b);
        Move a = V9.searchBestMove(fen, 1, 100);
        // AI must take the capture at (4,3).
        assertEquals(b.sq(4, 3), a.src());
    }

    // ---- Scoring ----

    @Test
    @DisplayName("scoreDelta:黑 1 子 vs 白 1 子,komi 让白得 7.5 → delta = -7.5")
    void scoreDeltaBasic() {
        BoardState state = V9.initialState();
        V9.applyMove(state, V9.indexForFileRank(4, 4), V9.indexForFileRank(4, 4));
        V9.applyMove(state, V9.indexForFileRank(4, 5), V9.indexForFileRank(4, 5));
        // 黑子 1,白子 1,territory 0,黑分 1 - 白分 (1 + 7.5) = -7.5
        double delta = ((GoVariant) V9).scoreDelta((GoBoard) state);
        assertEquals(-7.5, delta, 0.0001);
    }
}
