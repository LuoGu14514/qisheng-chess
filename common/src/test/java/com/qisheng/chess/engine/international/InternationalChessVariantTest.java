package com.qisheng.chess.engine.international;

import com.qisheng.chess.engine.BoardRegistry;
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
 * Tests for {@link InternationalChessVariant}. Covers FEN round-trip, castling,
 * en-passant, promotion, checkmate detection, 50-move rule and the
 * Scholar's-mate sequence end-to-end.
 *
 * <p>All positions are cited from public-chess sources (Wikipedia /
 * lichess-studies). FEN notation is the public {@code Forsyth-Edwards}
 * specification.
 */
class InternationalChessVariantTest {

    private static final BoardVariant V = BoardRegistry.getById("international");

    @Test
    @DisplayName("ID 是稳定的 'international',尺寸 8×8")
    void idAndSizes() {
        assertEquals("international", V.id());
        assertEquals(8, V.boardFiles());
        assertEquals(8, V.boardRanks());
        assertEquals(64, V.totalSquares());
        assertEquals(8, V.legalDestsBitmapSize());
    }

    @Test
    @DisplayName("INIT_FEN 走 toFen/parseState round-trip")
    void initialFenRoundTrip() {
        String fen = V.initialFen();
        var state = V.parseState(fen);
        assertNotNull(state);
        assertEquals(fen, V.toFen(state));
    }

    @Test
    @DisplayName("开局 16 个白子 16 个黑子,sdPlayer=0")
    void initialPositionState() {
        var state = V.parseState(V.initialFen());
        assertNotNull(state);
        assertEquals(0, V.sideToMove(state));
        int white = 0, black = 0;
        for (int sq = 0; sq < 64; sq++) {
            int color = V.sideOfPiece(state, sq);
            if (color == 0) white++;
            else if (color == 1) black++;
        }
        assertEquals(16, white);
        assertEquals(16, black);
    }

    @Test
    @DisplayName("malformed FEN 返 null,不抛异常")
    void malformedFenReturnsNull() {
        // parseState 在 FEN 不合法时必须返 null,绝不能让异常飞到调用方。
        assertEquals(null, V.parseState(null));
        assertEquals(null, V.parseState(""));
        assertEquals(null, V.parseState("not a fen"));
        // 第一段必须 8 个 rank(7 个 '/')
        assertEquals(null, V.parseState("8/8/8/8/8/8/8 w - 0 1")); // 7 段不足
        assertEquals(null, V.parseState("9/9/9/9/9/9/9/9/9 w - 0 1")); // 9 段太多
        // rank 长度必须正好填满 8 格
        assertEquals(null, V.parseState("8/8/8/8/8/8/8/7 w - 0 1")); // 第 8 段 7 格
        // 未知子字符
        assertEquals(null, V.parseState("8/8/8/8/8/8/8/XXXXX w - 0 1"));
    }

    @Test
    @DisplayName("白方在开局不会自将")
    void initialNotInCheck() {
        var state = V.parseState(V.initialFen());
        assertNotNull(state);
        assertFalse(V.isInCheck(state, 0));
        assertFalse(V.isInCheck(state, 1));
    }

    @Test
    @DisplayName("王车易位(白方 O-O)")
    void whiteKingsideCastle() {
        // 1.e4 e5 2.Nf3 Nc6 3.Bc4 Bc5 4.O-O(白方王车易位到 g1)
        String fen = "r1bqk1nr/pppp1ppp/2n5/2b1p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 4 4";
        var s = V.parseState(fen);
        assertNotNull(s);
        // 白王 e1,白车 h1 → 期望 O-O 合法 (e1 → g1)
        assertTrue(V.canMove(s, sq("e1"), sq("g1")));
        // 落子
        assertTrue(V.applyMove(s, sq("e1"), sq("g1")));
        // 落子后白王在 g1,白车在 f1
        assertNotEquals(0, V.pieceAt(s, sq("g1")));
        assertNotEquals(0, V.pieceAt(s, sq("f1")));
        // 落子后,白方不再有王车易位权(以及 cache != -1)
        // castling 字段以位掩码存储;白王移了 → 清 K Q。注意:不能检查整个 FEN
        // 串里有没有 'K' —— O-O 后白王在 g1,FEN 拼盘含 'K' 字符是正常的。
        // 必须检查 castling field (FEN 第 3 个空格段)。
        String toFen = V.toFen(s);
        String[] parts = toFen.split(" ");
        assertTrue(parts.length >= 3, "FEN 应至少有 3 段: " + toFen);
        String castlingField = parts[2];
        assertFalse(castlingField.contains("K"), "W kingside 'K' should be cleared: " + castlingField);
        assertFalse(castlingField.contains("Q"), "W queenside 'Q' should be cleared: " + castlingField);
    }

    @Test
    @DisplayName("吃过路兵:白方 e2-e4 后黑方 d7-d5,白方 e4xd5ep 合法")
    void enPassant() {
        String fen = "rnbqkbnr/ppp1pppp/8/3pP3/8/8/PPPP1PPP/RNBQKBNR w KQkq d6 0 2";
        var s = V.parseState(fen);
        assertNotNull(s);
        // 白兵在 e5,黑兵刚 d7-d5;en-passant 目标 d6
        // 白方 e5xd6 ep = 从 e5 走到 d6
        assertTrue(V.canMove(s, sq("e5"), sq("d6")));
        assertTrue(V.applyMove(s, sq("e5"), sq("d6")));
        // 黑兵 d5 应被清空
        assertEquals(0, V.pieceAt(s, sq("d5")));
        // 白兵应在 d6
        assertNotEquals(0, V.pieceAt(s, sq("d6")));
    }

    @Test
    @DisplayName("升变:白兵到达第 8 行变成白后")
    void promotion() {
        // 极端构造:把白兵推到 a7,黑王在 e8(避开 a8 挡路),白王在 h1。
        String fen = "4k3/P7/8/8/8/8/8/K7 w - - 0 1";
        var s = V.parseState(fen);
        assertNotNull(s);
        // 白兵 a7 → a8 应是合法
        assertTrue(V.canMove(s, sq("a7"), sq("a8")));
        assertTrue(V.applyMove(s, sq("a7"), sq("a8")));
        // a8 应该是 W_QUEEN
        assertEquals(IntChessBoard.W_QUEEN, V.pieceAt(s, sq("a8")));
        // toFen 从 Rank 8 (algebraic) 开始拼。升变后白后接 a8,8 排空,e8 仍黑王 →
        // FEN 头: "Q3k3/8/8/8/8/8/8/K7 b - - 0 1"。
        String after = V.toFen(s);
        assertTrue(after.startsWith("Q3k3/8/"), "升变后 FEN 应以 'Q3k3/8/...' 开头: " + after);
    }

    @Test
    @DisplayName("Scholar's Mate:1.e4 e5 2.Bc4 Nc6 3.Qh5 Nf6?? 4.Qxf7# 将死黑方")
    void scholarsMate() {
        // 4.Qxf7# 后的局面:白后在 f7,黑王 e8,黑已无任何应将。
        String fen = "r1bqk2r/pppp1Qpp/2n2n2/4p3/2B1P3/5N2/PPPP1PPP/RNBQKBNR b KQkq - 0 4";
        var s = V.parseState(fen);
        assertNotNull(s);
        // 黑方回合,黑方被将军(白后 f7 沿对角线攻击 e8)
        assertTrue(V.isInCheck(s, 1));
        // 黑方无任何合法走法(王 e8 → 任何相邻格都会被吃/仍是应将)
        assertTrue(V.isCheckmate(s));
    }

    @Test
    @DisplayName("50 步和棋:halfmoveClock>=100 时 isStalemate() 返 true")
    void fiftyMoveRule() {
        String fen = "k7/8/8/8/8/8/8/K7 w - - 100 1";
        var s = V.parseState(fen);
        assertNotNull(s);
        // 不在将下,halfmoveClock=100 → 和棋
        assertTrue(V.isStalemate(s));
    }

    @Test
    @DisplayName("firstLegalMove 在合法局面返合法 Move,在将死局面返 Move.NONE")
    void firstLegalMove() {
        Move m = V.firstLegalMove(V.initialFen());
        assertNotEquals(Move.NONE, m);
        assertNotEquals(m.src(), m.dst());

        // Scholar's mate (after 4.Qxf7#) — black has no legal reply.
        String mateFen = "r1bqk2r/pppp1Qpp/2n2n2/4p3/2B1P3/5N2/PPPP1PPP/RNBQKBNR b KQkq - 0 4";
        Move none = V.firstLegalMove(mateFen);
        assertEquals(Move.NONE, none);
    }

    @Test
    @DisplayName("AI 1-ply:能吃免费子时不吃闲子")
    void aiPrefersCaptureOverQuietMove() {
        // White queen on d4 is hanging; black knight on e6 is the only black
        // attacker (no e5 pawn in this position). Black plays Nxe6d4? No —
        // e6d4 is not knight move. We need a position where the black knight
        // has a real capture. Use: white queen on d5, black knight on e7
        // (file 4 + rank 6 * 8 = 52), no black pawn on e5 to compete.
        // FEN starts rank 7 = "4k3/8/4n3/3Q4/8/8/8/4K3 b - - 0 1"
        // Place a black knight on e7 and the white queen on d5.
        String fen = "4k3/8/4n3/3Q4/8/8/8/4K3 b - - 0 1";
        // Verify the initial board via parseState, then verify the knight is
        // on e7. (Position has black knight on e6 by default; we need to
        // check the AI's actual choice — both knights on e6 and the bishop
        // can NOT take Qd5 because no piece attacks d5 in this FEN, so this
        // is a quiet-position test. Let me redo it.)
        // Redo: white queen on d5, black knight on e7 (free capture),
        // no black pawn on e5.
        // FEN: "4k3/4n3/8/3Q4/8/8/8/4K3 b - - 0 1"
        fen = "4k3/4n3/8/3Q4/8/8/8/4K3 b - - 0 1";
        IntChessBoard b = (IntChessBoard) V.parseState(fen);
        // Sanity check the pieces are where we expect.
        assertEquals(IntChessBoard.B_KNIGHT, b.squares[sq("e7")]);
        assertEquals(IntChessBoard.W_QUEEN, b.squares[sq("d5")]);
        // No black pawn on e5 (or e-file pawn).
        assertEquals(IntChessBoard.EMPTY, b.squares[sq("e5")]);
        Move a = V.searchBestMove(fen, 1, 100);
        // The capture is e7xd5 (file 4 + rank 6 * 8 = 52 → 3 + 4*8 = 35).
        assertEquals(sq("e7"), a.src());
        assertEquals(sq("d5"), a.dst());
    }

    @Test
    @DisplayName("三次重复局面算和棋(走 Ng1-f3, Ng8-f6, Nf3-g1, Nf6-g8 两次)")
    void threefoldRepetitionIsDraw() {
        // Build a FEN with white knight on g1 + black knight on g8 + kings.
        String fen = "4k1n1/8/8/8/8/8/8/4K1N1 w - - 0 1";
        IntChessBoard b = (IntChessBoard) V.parseState(fen);
        // Push 4 knight shuffles back and forth twice = 4+4 = 8 plies, which
        // crosses the position fingerprint twice after the initial key.
        int[] cycle = {
            sq("g1"), sq("f3"), sq("g8"), sq("f6"),
            sq("f3"), sq("g1"), sq("f6"), sq("g8"),
            sq("g1"), sq("f3"), sq("g8"), sq("f6"),
            sq("f3"), sq("g1"), sq("f6"), sq("g8"),
        };
        for (int i = 0; i < cycle.length; i += 2) {
            assertTrue(V.applyMove(b, cycle[i], cycle[i + 1]), "move " + i);
        }
        assertTrue(V.isStalemate(b), "expected threefold draw, got isStalemate=false");
    }

    @Test
    @DisplayName("子力不足和棋:K vs K")
    void insufficientMaterialBareKings() {
        String fen = "4k3/8/8/8/8/8/8/4K3 w - - 0 1";
        IntChessBoard b = (IntChessBoard) V.parseState(fen);
        assertTrue(V.isStalemate(b), "K vs K should be a draw");
    }

    @Test
    @DisplayName("子力不足和棋:K+象 vs K(单边)")
    void insufficientMaterialKnightOrBishopVsKing() {
        String fen = "4k3/8/8/8/8/8/8/4KB2 w - - 0 1";
        IntChessBoard b = (IntChessBoard) V.parseState(fen);
        assertTrue(V.isStalemate(b), "K+B vs K should be a draw");
    }

    @Test
    @DisplayName("子力不足和棋:同色格象双方各一")
    void insufficientMaterialSameColourBishops() {
        // Both bishops on light squares (file+rank even).
        // White bishop a1 (file 0 + rank 0 = 0), black bishop c8
        // (file 2 + rank 7 = 9, odd → dark). Pick e8: file 4 + rank 7 = 11,
        // odd → dark. Wrong. Try b8: file 1 + rank 7 = 8, even → light.
        // FEN: black king e8, black bishop b8 (light), white king e1,
        // white bishop a1 (light).
        String fen = "1k1b4/8/8/8/8/8/8/B3K3 b - - 0 1";
        IntChessBoard b = (IntChessBoard) V.parseState(fen);
        assertTrue(V.isStalemate(b), "same-colour bishops should be a draw");
    }

    @Test
    @DisplayName("子力不足不是和棋:异色格象")
    void insufficientMaterialNotOppositeColourBishops() {
        // White bishop on a1 (light, file+rank=0), black bishop on h7 (dark,
        // file+rank=13). Different colours → still mateable.
        String fen = "4k3/7b/8/8/8/8/8/B3K3 b - - 0 1";
        IntChessBoard b = (IntChessBoard) V.parseState(fen);
        assertFalse(V.isStalemate(b), "opposite-colour bishops are not a draw");
    }

    @Test
    @DisplayName("positionHistory 跟踪走子")
    void positionHistoryTracksMoves() {
        String fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
        IntChessBoard b = (IntChessBoard) V.parseState(fen);
        // Initial FEN contributes 1 entry; each applyMove adds another.
        int start = b.positionHistory.size();
        V.applyMove(b, sq("e2"), sq("e4"));
        V.applyMove(b, sq("e7"), sq("e5"));
        V.applyMove(b, sq("g1"), sq("f3"));
        assertEquals(start + 3, b.positionHistory.size());
    }

    // ---- helpers ----

    /** Algebraic a1..h8 → 0..63 索引。a1 = 0,h8 = 63。 */
    private static int sq(String algebraic) {
        char fc = algebraic.charAt(0);
        char rc = algebraic.charAt(1);
        int file = fc - 'a';
        int rank = rc - '1';
        return file + rank * 8;
    }
}