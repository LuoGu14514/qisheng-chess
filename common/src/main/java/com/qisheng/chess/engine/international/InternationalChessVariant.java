package com.qisheng.chess.engine.international;

import com.qisheng.chess.engine.BoardState;
import com.qisheng.chess.engine.BoardVariant;
import com.qisheng.chess.engine.Move;

/**
 * {@link BoardVariant} for international chess (FIDE).
 *
 * <p><b>Scope of v0.3.1.</b> Move generation, legality (check filtering),
 * castling, en-passant, under-promotion-as-queen (auto-queen for v0.3.1; a UI
 * for picking a piece comes in v0.3.2), checkmate / stalemate / 50-move
 * draw. The full FEN round-trips through all six fields.
 *
 * <p><b>What is <em>not</em> here yet.</b>
 * <ul>
 *   <li>Threefold repetition — the halfmove clock drives draws, no Zobrist
 *   hashing. Adding it is a future task.</li>
 *   <li>Alpha-beta search — {@link #searchBestMove} returns {@link Move#NONE}.
 *   A computer opponent comes in v0.3.2 alongside the international-chess
 *   GUI.</li>
 *   <li>Promotion piece selection — pawns always promote to a queen for now.
 *   {@link #firstLegalMove} follows the same auto-queen rule.</li>
 * </ul>
 *
 * <p><b>Move encoding.</b> When this variant returns a raw move
 * integer (used internally for the search slot — currently just
 * {@link Move}), it packs {@code src + (dst<<6)} so both indices fit in
 * 12 bits. Decoding helpers are at {@link #srcOf(int)} and {@link #dstOf(int)}.
 *
 * <p><b>Wire format.</b> The legal-destinations bitmap is 8 bytes (64
 * squares, LSB-first within each byte), per
 * {@link #legalDestsBitmapSize}.
 */
public final class InternationalChessVariant implements BoardVariant {

    /** Stable id, persisted in NBT and shipped on the wire. */
    public static final String ID = "international";

    /** Standard opening position. */
    public static final String INIT_FEN =
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    public InternationalChessVariant() {}

    @Override public String id() { return ID; }
    @Override public String displayNameKey() { return "qisheng.chess.variant.international"; }

    @Override public int boardFiles() { return 8; }
    @Override public int boardRanks() { return 8; }
    @Override public int totalSquares() { return 64; }

    @Override public String initialFen() {
        return INIT_FEN;
    }

    @Override public BoardState initialState() {
        IntChessBoard b = new IntChessBoard();
        loadFen(b, INIT_FEN);
        return b;
    }

    @Override public BoardState parseState(String fen) {
        if (fen == null) return null;
        IntChessBoard b = new IntChessBoard();
        if (!loadFen(b, fen)) return null;
        return b;
    }

    @Override public boolean isValidSquare(int sq) {
        return sq >= 0 && sq < 64;
    }

    @Override public byte pieceAt(BoardState state, int sq) {
        if (!(state instanceof IntChessBoard b)) return 0;
        return isValidSquare(sq) ? b.squares[sq] : 0;
    }

    @Override public int sideOfPiece(BoardState state, int sq) {
        if (!(state instanceof IntChessBoard b)) return -1;
        if (!isValidSquare(sq)) return -1;
        byte pc = b.squares[sq];
        return IntChessBoard.colorOf(pc);
    }

    @Override public boolean canMove(BoardState state, int src, int dst) {
        if (!(state instanceof IntChessBoard b)) return false;
        if (!isValidSquare(src) || !isValidSquare(dst)) return false;
        if (src == dst) return false;
        byte piece = b.squares[src];
        if (piece == 0) return false;
        int color = IntChessBoard.colorOf(piece);
        if (color != b.sdPlayer) return false;
        if (!isPseudoLegal(b, src, dst)) return false;
        return !wouldExposeKing(b, src, dst);
    }

    @Override public boolean applyMove(BoardState state, int src, int dst) {
        if (!(state instanceof IntChessBoard b)) return false;
        if (!canMove(b, src, dst)) return false;
        makeMove(b, src, dst);
        return true;
    }

    @Override public String toFen(BoardState state) {
        if (!(state instanceof IntChessBoard b)) return initialFen();
        StringBuilder sb = new StringBuilder();
        // 1. Piece placement
        for (int rank = 7; rank >= 0; rank--) {
            int empty = 0;
            for (int file = 0; file < 8; file++) {
                byte pc = b.squares[IntChessBoard.sq(file, rank)];
                if (pc == 0) {
                    empty++;
                } else {
                    if (empty > 0) { sb.append(empty); empty = 0; }
                    sb.append(pieceFenChar(b, IntChessBoard.sq(file, rank)));
                }
            }
            if (empty > 0) sb.append(empty);
            if (rank > 0) sb.append('/');
        }
        // 2. Side
        sb.append(' ').append(b.sdPlayer == 0 ? 'w' : 'b');
        // 3. Castling
        sb.append(' ');
        if (b.castling == 0) {
            sb.append('-');
        } else {
            if ((b.castling & IntChessBoard.CASTLE_W_K) != 0) sb.append('K');
            if ((b.castling & IntChessBoard.CASTLE_W_Q) != 0) sb.append('Q');
            if ((b.castling & IntChessBoard.CASTLE_B_K) != 0) sb.append('k');
            if ((b.castling & IntChessBoard.CASTLE_B_Q) != 0) sb.append('q');
        }
        // 4. En-passant target
        sb.append(' ');
        if (b.enPassantSq < 0) {
            sb.append('-');
        } else {
            int file = IntChessBoard.fileOf(b.enPassantSq);
            int rank = IntChessBoard.rankOf(b.enPassantSq);
            sb.append((char) ('a' + file)).append(rank + 1);
        }
        // 5. Halfmove clock
        sb.append(' ').append(b.halfmoveClock);
        // 6. Fullmove number
        sb.append(' ').append(b.fullmoveNumber);
        return sb.toString();
    }

    @Override public int sideToMove(BoardState state) {
        if (state instanceof IntChessBoard b) return b.sdPlayer;
        return 0;
    }

    @Override public void setSideToMove(BoardState state, int sd) {
        if (state instanceof IntChessBoard b) b.sdPlayer = sd == 1 ? 1 : 0;
    }

    @Override public Move searchBestMove(String fen, int depth, int millis) {
        // v0.3.1 ships no alpha-beta — the engine for international chess is
        // a v0.3.2 milestone. Without it the computer opponent just hands
        // back the first legal move, which is what PvcController plays.
        return firstLegalMove(fen);
    }

    @Override public Move firstLegalMove(String fen) {
        BoardState state = parseState(fen);
        if (!(state instanceof IntChessBoard b)) return Move.NONE;
        int[] mvs = new int[128];
        int n = generateAllMoves(b, mvs);
        for (int i = 0; i < n; i++) {
            int mv = mvs[i];
            int src = srcOf(mv);
            int dst = dstOf(mv);
            if (canMove(b, src, dst)) return new Move(src, dst);
        }
        return Move.NONE;
    }

    @Override public boolean isInCheck(BoardState state, int side) {
        if (!(state instanceof IntChessBoard b)) return false;
        int kingSq = findKing(b, side);
        if (kingSq < 0) return false;
        int opp = 1 - side;
        return isSquareAttacked(b, kingSq, opp);
    }

    @Override public boolean isCheckmate(BoardState state) {
        if (!(state instanceof IntChessBoard b)) return false;
        if (!isInCheck(b, b.sdPlayer)) return false;
        int[] mvs = new int[128];
        return findAnyLegalMove(b, mvs) < 0;
    }

    @Override public boolean isStalemate(BoardState state) {
        if (!(state instanceof IntChessBoard b)) return false;
        if (isInCheck(b, b.sdPlayer)) return false;
        if (b.halfmoveClock >= 100) return true;     // 50-move rule
        int[] mvs = new int[128];
        return findAnyLegalMove(b, mvs) < 0;
    }

    @Override public char pieceFenChar(BoardState state, int sq) {
        if (!(state instanceof IntChessBoard b)) return '.';
        if (!isValidSquare(sq)) return '.';
        byte pc = b.squares[sq];
        if (pc == 0) return '.';
        char base = switch (IntChessBoard.typeOf(pc)) {
            case IntChessBoard.PAWN   -> 'p';
            case IntChessBoard.KNIGHT -> 'n';
            case IntChessBoard.BISHOP -> 'b';
            case IntChessBoard.ROOK   -> 'r';
            case IntChessBoard.QUEEN  -> 'q';
            case IntChessBoard.KING   -> 'k';
            default -> '?';
        };
        // White = uppercase, black = lowercase — standard FEN.
        return IntChessBoard.colorOf(pc) == 0 ? Character.toUpperCase(base) : base;
    }

    @Override public int legalDestsBitmapSize() {
        return 64 / 8;
    }

    // ====================================================================
    // Internal helpers
    // ====================================================================

    /** Pack src in low 6 bits, dst in bits 6..11. */
    public static int encodeMove(int src, int dst) {
        return (src & 0x3F) | ((dst & 0x3F) << 6);
    }

    public static int srcOf(int mv) { return mv & 0x3F; }
    public static int dstOf(int mv) { return (mv >>> 6) & 0x3F; }

    // ---- FEN ----

    /**
     * Load a FEN into {@code b}, returning {@code true} on success.
     *
     * <p>Accepts one to six space-separated fields:
     * <ol>
     *   <li>Piece placement (required)</li>
     *   <li>Side to move ({@code w} or {@code b}; default {@code w})</li>
     *   <li>Castling rights ({@code KQkq}, subset, or {@code -}; default {@code -})</li>
     *   <li>En-passant target (algebraic like {@code e3}, or {@code -})</li>
     *   <li>Halfmove clock (integer; default 0)</li>
     *   <li>Fullmove number (integer; default 1)</li>
     * </ol>
     *
     * <p>Returns {@code false} on any structural problem, leaving the board
     * untouched (caller is responsible for falling back to a fresh board).
     */
    public static boolean loadFen(IntChessBoard b, String fen) {
        if (fen == null) return false;
        String[] fields = fen.trim().split("\\s+");
        if (fields.length < 1 || fields.length > 6) return false;
        String placement = fields[0];
        String[] ranks = placement.split("/", -1);
        if (ranks.length != 8) return false;
        byte[] next = new byte[64];
        for (int i = 0; i < 8; i++) {
            String rank = ranks[i];
            int file = 0;
            for (int j = 0; j < rank.length(); j++) {
                char c = rank.charAt(j);
                if (c >= '1' && c <= '8') {
                    file += c - '0';
                } else {
                    int piece = pieceFromFen(c);
                    if (piece == 0) return false;
                    if (file >= 8) return false;
                    next[file + (7 - i) * 8] = (byte) piece;
                    file++;
                }
            }
            if (file != 8) return false;
        }
        // Side
        int sdPlayer = 0;
        if (fields.length >= 2 && !fields[1].isEmpty()) {
            char side = fields[1].charAt(0);
            if (side == 'w') sdPlayer = 0;
            else if (side == 'b') sdPlayer = 1;
            else return false;
        }
        // Castling
        int castling = 0;
        if (fields.length >= 3 && !fields[2].isEmpty()) {
            String c = fields[2];
            if (!c.equals("-")) {
                if (c.indexOf('K') >= 0) castling |= IntChessBoard.CASTLE_W_K;
                if (c.indexOf('Q') >= 0) castling |= IntChessBoard.CASTLE_W_Q;
                if (c.indexOf('k') >= 0) castling |= IntChessBoard.CASTLE_B_K;
                if (c.indexOf('q') >= 0) castling |= IntChessBoard.CASTLE_B_Q;
            }
        }
        // En-passant
        int ep = -1;
        if (fields.length >= 4 && !fields[3].isEmpty()) {
            String ep_s = fields[3];
            if (!ep_s.equals("-")) {
                if (ep_s.length() != 2) return false;
                int epFile = ep_s.charAt(0) - 'a';
                int epRank = ep_s.charAt(1) - '1';
                if (epFile < 0 || epFile >= 8 || epRank < 0 || epRank >= 8) return false;
                ep = IntChessBoard.sq(epFile, epRank);
            }
        }
        int halfmove = 0;
        if (fields.length >= 5 && !fields[4].isEmpty()) {
            try { halfmove = Integer.parseInt(fields[4]); }
            catch (NumberFormatException e) { return false; }
            if (halfmove < 0) return false;
        }
        int fullmove = 1;
        if (fields.length >= 6 && !fields[5].isEmpty()) {
            try { fullmove = Integer.parseInt(fields[5]); }
            catch (NumberFormatException e) { return false; }
            if (fullmove < 1) return false;
        }

        // Commit only after every field parses — half-loaded boards are worse than
        // a clean failure.
        b.squares = next;
        b.sdPlayer = sdPlayer;
        b.castling = castling;
        b.enPassantSq = ep;
        b.halfmoveClock = halfmove;
        b.fullmoveNumber = fullmove;
        return true;
    }

    private static int pieceFromFen(char c) {
        // White = uppercase, black = lowercase.
        boolean white = Character.isUpperCase(c);
        char lower = Character.toLowerCase(c);
        int base = switch (lower) {
            case 'p' -> IntChessBoard.PAWN;
            case 'n' -> IntChessBoard.KNIGHT;
            case 'b' -> IntChessBoard.BISHOP;
            case 'r' -> IntChessBoard.ROOK;
            case 'q' -> IntChessBoard.QUEEN;
            case 'k' -> IntChessBoard.KING;
            default -> 0;
        };
        if (base == 0) return 0;
        return white ? (base + 8) : (base + 16);
    }

    // ---- Pseudo-legal generation ----

    /**
     * True if {@code src → dst} is pseudo-legal for {@code b.sdPlayer}. Does not
     * filter out moves that leave the king in check — that's
     * {@link #wouldExposeKing}.
     */
    static boolean isPseudoLegal(IntChessBoard b, int src, int dst) {
        byte piece = b.squares[src];
        if (piece == 0) return false;
        int color = IntChessBoard.colorOf(piece);
        int type = IntChessBoard.typeOf(piece);
        int df = IntChessBoard.fileOf(dst) - IntChessBoard.fileOf(src);
        int dr = IntChessBoard.rankOf(dst) - IntChessBoard.rankOf(src);
        int adf = Math.abs(df);
        int adr = Math.abs(dr);

        switch (type) {
            case IntChessBoard.PAWN:   return pawnPseudoLegal(b, src, dst, df, dr);
            case IntChessBoard.KNIGHT: return adf * adr == 2;
            case IntChessBoard.BISHOP:  return adf == adr && adf > 0 && clearPath(b, src, dst);
            case IntChessBoard.ROOK:    return (adf == 0 || adr == 0) && (adf + adr > 0) && clearPath(b, src, dst);
            case IntChessBoard.QUEEN:   return (adf == adr || adf == 0 || adr == 0) && (adf + adr > 0) && clearPath(b, src, dst);
            case IntChessBoard.KING:    return kingMoveOrCastle(b, src, dst, df, adf, color);
            default: return false;
        }
    }

    private static boolean pawnPseudoLegal(IntChessBoard b, int src, int dst, int df, int dr) {
        int color = IntChessBoard.colorOf(b.squares[src]);
        int dir = color == 0 ? 1 : -1;
        byte dstPc = b.squares[dst];
        int targetRank = IntChessBoard.rankOf(dst);

        if (df == 0) {
            // Single push (must be empty).
            if (dr == dir && dstPc == 0) return true;
            // Double push from starting rank, both squares empty.
            int startRank = color == 0 ? 1 : 6;
            if (IntChessBoard.rankOf(src) == startRank && dr == 2 * dir && dstPc == 0
                    && b.squares[src + dir * 8] == 0) return true;
            return false;
        }
        if (Math.abs(df) == 1 && dr == dir) {
            // Diagonal capture (own piece is filtered upstream by canMove).
            if (dstPc != 0) return true;
            // En-passant: target square is the recorded en-passant square.
            if (dst == b.enPassantSq) return true;
            return false;
        }
        return false;
    }

    private static boolean kingMoveOrCastle(IntChessBoard b, int src, int dst, int df, int adf, int color) {
        if (adf <= 1 && Math.abs(IntChessBoard.rankOf(dst) - IntChessBoard.rankOf(src)) <= 1) {
            return true; // normal 1-square king move
        }
        // Castling: king moves exactly 2 files along its back rank with no
        // rank change, both squares between king and rook are empty, both
        // pieces are on their original squares, the king isn't currently in
        // check, and it doesn't pass through an attacked square.
        if (adf != 2 || IntChessBoard.rankOf(dst) != IntChessBoard.rankOf(src)) return false;
        int homeRank = color == 0 ? 0 : 7;
        if (IntChessBoard.rankOf(src) != homeRank) return false;
        int rightsMask = color == 0
                ? (df > 0 ? IntChessBoard.CASTLE_W_K : IntChessBoard.CASTLE_W_Q)
                : (df > 0 ? IntChessBoard.CASTLE_B_K : IntChessBoard.CASTLE_B_Q);
        if ((b.castling & rightsMask) == 0) return false;
        int rookSq = color == 0
                ? (df > 0 ? IntChessBoard.H1 : IntChessBoard.A1)
                : (df > 0 ? IntChessBoard.H8 : IntChessBoard.A8);
        byte rook = color == 0 ? IntChessBoard.W_ROOK : IntChessBoard.B_ROOK;
        if (b.squares[rookSq] != rook) return false;
        int step = df > 0 ? 1 : -1;
        for (int f = IntChessBoard.fileOf(src) + step; f != IntChessBoard.fileOf(rookSq); f += step) {
            if (b.squares[IntChessBoard.sq(f, homeRank)] != 0) return false;
        }
        // King not in check, doesn't pass through check, doesn't end in check
        int opp = 1 - color;
        if (isSquareAttacked(b, src, opp)) return false;
        if (isSquareAttacked(b, src + step, opp)) return false;
        if (isSquareAttacked(b, dst, opp)) return false;
        return true;
    }

    private static boolean clearPath(IntChessBoard b, int src, int dst) {
        int sf = IntChessBoard.fileOf(src), sr = IntChessBoard.rankOf(src);
        int df = IntChessBoard.fileOf(dst), dr = IntChessBoard.rankOf(dst);
        int stepF = Integer.signum(df - sf);
        int stepR = Integer.signum(dr - sr);
        int f = sf + stepF, r = sr + stepR;
        while (f != df || r != dr) {
            if (b.squares[IntChessBoard.sq(f, r)] != 0) return false;
            f += stepF; r += stepR;
        }
        return true;
    }

    // ---- Game-ending helpers ----

    /** Generate every pseudo-legal move for the side to move; returns how many. */
    static int generateAllMoves(IntChessBoard b, int[] mvs) {
        int n = 0;
        for (int sq = 0; sq < 64; sq++) {
            byte pc = b.squares[sq];
            if (pc == 0) continue;
            if (IntChessBoard.colorOf(pc) != b.sdPlayer) continue;
            for (int dst = 0; dst < 64; dst++) {
                if (sq == dst) continue;
                if (!isPseudoLegal(b, sq, dst)) continue;
                // Don't auto-include own-piece targets; canMove would reject them.
                byte dstPc = b.squares[dst];
                if (dstPc != 0 && IntChessBoard.colorOf(dstPc) == b.sdPlayer) continue;
                // En-passant pseudo-move has dst==b.enPassantSq and an empty dst;
                // isPseudoLegal lets it through. Pawn forward pushes must land
                // on empty — already guaranteed by isPseudoLegal.
                mvs[n++] = encodeMove(sq, dst);
            }
        }
        return n;
    }

    /** Returns the first legal move's index in {@code mvs}, or {@code -1}. */
    static int findAnyLegalMove(IntChessBoard b, int[] mvs) {
        int n = generateAllMoves(b, mvs);
        for (int i = 0; i < n; i++) {
            int src = srcOf(mvs[i]);
            int dst = dstOf(mvs[i]);
            if (!wouldExposeKing(b, src, dst)) return i;
        }
        return -1;
    }

    /**
     * True if making {@code src → dst} on {@code b} would leave the mover's
     * king in check (or, equivalently, if the king is already in check and the
     * move doesn't address it).
     */
    static boolean wouldExposeKing(IntChessBoard b, int src, int dst) {
        byte piece = b.squares[src];
        byte captured = b.squares[dst];
        int capturedEP = -1;
        boolean wasEnPassant = false;

        // Apply
        b.squares[dst] = piece;
        b.squares[src] = 0;
        if (piece != 0 && IntChessBoard.typeOf(piece) == IntChessBoard.PAWN && dst == b.enPassantSq) {
            // Captured pawn sits behind the en-passant square.
            int capSq = dst + (b.sdPlayer == 0 ? -8 : 8);
            capturedEP = capSq;
            b.squares[capSq] = 0;
            wasEnPassant = true;
        }

        // King location for the side that just moved
        int color = b.sdPlayer;
        int kingSq = findKingSquareInCurrent(b, color);
        boolean inCheck;
        if (kingSq < 0) {
            inCheck = true; // no king = the position is broken; reject
        } else {
            inCheck = isSquareAttacked(b, kingSq, 1 - color);
        }

        // Undo
        b.squares[src] = piece;
        b.squares[dst] = captured;
        if (wasEnPassant) {
            byte pawnColor = (byte) (b.sdPlayer == 0 ? IntChessBoard.B_PAWN : IntChessBoard.W_PAWN);
            b.squares[capturedEP] = pawnColor;
        }
        return inCheck;
    }

    private static int findKingSquareInCurrent(IntChessBoard b, int color) {
        byte king = color == 0 ? IntChessBoard.W_KING : IntChessBoard.B_KING;
        for (int i = 0; i < 64; i++) {
            if (b.squares[i] == king) return i;
        }
        return -1;
    }

    private static int findKing(IntChessBoard b, int color) {
        return findKingSquareInCurrent(b, color);
    }

    /** True if {@code sq} is attacked by side {@code attackerColor}. */
    static boolean isSquareAttacked(IntChessBoard b, int sq, int attackerColor) {
        int file = IntChessBoard.fileOf(sq);
        int rank = IntChessBoard.rankOf(sq);

        // Pawns
        int pawnDir = attackerColor == 0 ? -1 : 1; // pawns attack toward the opposite side
        for (int df : new int[]{-1, 1}) {
            int pf = file + df, pr = rank + pawnDir;
            if (pf < 0 || pf >= 8 || pr < 0 || pr >= 8) continue;
            byte pc = b.squares[IntChessBoard.sq(pf, pr)];
            if (pc != 0 && IntChessBoard.colorOf(pc) == attackerColor
                    && IntChessBoard.typeOf(pc) == IntChessBoard.PAWN) return true;
        }

        // Knights
        int[][] knightOffsets = {{-2, -1}, {-2, 1}, {-1, -2}, {-1, 2}, {1, -2}, {1, 2}, {2, -1}, {2, 1}};
        for (int[] o : knightOffsets) {
            int f = file + o[0], r = rank + o[1];
            if (f < 0 || f >= 8 || r < 0 || r >= 8) continue;
            byte pc = b.squares[IntChessBoard.sq(f, r)];
            if (pc != 0 && IntChessBoard.colorOf(pc) == attackerColor
                    && IntChessBoard.typeOf(pc) == IntChessBoard.KNIGHT) return true;
        }

        // Sliders: bishops, rooks, queens
        // Diagonals
        int[][] dirs = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        boolean[] isDiagonal = {true, true, true, true, false, false, false, false};
        boolean[] isStraight = {false, false, false, false, true, true, true, true};
        for (int d = 0; d < 8; d++) {
            int df = dirs[d][0], dr = dirs[d][1];
            int f = file + df, r = rank + dr;
            while (f >= 0 && f < 8 && r >= 0 && r < 8) {
                byte pc = b.squares[IntChessBoard.sq(f, r)];
                if (pc != 0) {
                    if (IntChessBoard.colorOf(pc) != attackerColor) break;
                    int type = IntChessBoard.typeOf(pc);
                    if (type == IntChessBoard.QUEEN) return true;
                    if (isDiagonal[d] && type == IntChessBoard.BISHOP) return true;
                    if (isStraight[d] && type == IntChessBoard.ROOK) return true;
                    break;
                }
                f += df; r += dr;
            }
        }

        // King (1 square in any direction)
        for (int df = -1; df <= 1; df++) {
            for (int dr = -1; dr <= 1; dr++) {
                if (df == 0 && dr == 0) continue;
                int f = file + df, r = rank + dr;
                if (f < 0 || f >= 8 || r < 0 || r >= 8) continue;
                byte pc = b.squares[IntChessBoard.sq(f, r)];
                if (pc != 0 && IntChessBoard.colorOf(pc) == attackerColor
                        && IntChessBoard.typeOf(pc) == IntChessBoard.KING) return true;
            }
        }

        return false;
    }

    // ---- Apply a legal move ----

    /** Mutate {@code b} to reflect a legal move; assumes {@link #canMove} already verified. */
    static void makeMove(IntChessBoard b, int src, int dst) {
        byte piece = b.squares[src];
        int type = IntChessBoard.typeOf(piece);
        int color = IntChessBoard.colorOf(piece);

        boolean isCapture = b.squares[dst] != 0
                || (type == IntChessBoard.PAWN && dst == b.enPassantSq);

        // Pawn double-push records the en-passant target square.
        int newEp = -1;
        if (type == IntChessBoard.PAWN) {
            int df = IntChessBoard.fileOf(dst) - IntChessBoard.fileOf(src);
            int dr = IntChessBoard.rankOf(dst) - IntChessBoard.rankOf(src);
            if (df == 0 && Math.abs(dr) == 2) {
                int midRank = (IntChessBoard.rankOf(src) + IntChessBoard.rankOf(dst)) / 2;
                newEp = IntChessBoard.sq(IntChessBoard.fileOf(src), midRank);
            }
        }

        // En-passant capture: take the pawn sitting behind the target square.
        boolean wasEnPassant = type == IntChessBoard.PAWN && b.squares[dst] == 0 && dst == b.enPassantSq;
        if (wasEnPassant) {
            int capturedSq = dst + (color == 0 ? -8 : 8);
            b.squares[capturedSq] = 0;
        }

        b.squares[dst] = piece;
        b.squares[src] = 0;

        // Promotion: pawn reaching the last rank becomes a queen. v0.3.1 has
        // no UI for picking a piece; v0.3.2 adds it.
        if (type == IntChessBoard.PAWN) {
            int lastRank = color == 0 ? 7 : 0;
            if (IntChessBoard.rankOf(dst) == lastRank) {
                b.squares[dst] = (byte) (color == 0 ? IntChessBoard.W_QUEEN : IntChessBoard.B_QUEEN);
            }
        }

        // Castling: also move the rook.
        if (type == IntChessBoard.KING && Math.abs(IntChessBoard.fileOf(dst) - IntChessBoard.fileOf(src)) == 2) {
            int homeRank = color == 0 ? 0 : 7;
            int df = IntChessBoard.fileOf(dst) - IntChessBoard.fileOf(src);
            if (df > 0) {
                // Kingside: rook h-file → f-file
                int rookSq = IntChessBoard.sq(7, homeRank);
                int newRookSq = IntChessBoard.sq(5, homeRank);
                b.squares[newRookSq] = b.squares[rookSq];
                b.squares[rookSq] = 0;
            } else {
                // Queenside: rook a-file → d-file
                int rookSq = IntChessBoard.sq(0, homeRank);
                int newRookSq = IntChessBoard.sq(3, homeRank);
                b.squares[newRookSq] = b.squares[rookSq];
                b.squares[rookSq] = 0;
            }
        }

        // Castling rights: king move drops both rights for that color; rook
        // move from its home square drops the matching right; opponent
        // capturing that rook drops the matching right too.
        if (type == IntChessBoard.KING) {
            if (color == 0) b.castling &= ~(IntChessBoard.CASTLE_W_K | IntChessBoard.CASTLE_W_Q);
            else b.castling &= ~(IntChessBoard.CASTLE_B_K | IntChessBoard.CASTLE_B_Q);
        }
        if (type == IntChessBoard.ROOK) {
            if (src == IntChessBoard.A1) b.castling &= ~IntChessBoard.CASTLE_W_Q;
            else if (src == IntChessBoard.H1) b.castling &= ~IntChessBoard.CASTLE_W_K;
            else if (src == IntChessBoard.A8) b.castling &= ~IntChessBoard.CASTLE_B_Q;
            else if (src == IntChessBoard.H8) b.castling &= ~IntChessBoard.CASTLE_B_K;
        }
        if (!wasEnPassant) {
            // Capture of opponent's rook on its home square.
            if (dst == IntChessBoard.A1) b.castling &= ~IntChessBoard.CASTLE_W_Q;
            else if (dst == IntChessBoard.H1) b.castling &= ~IntChessBoard.CASTLE_W_K;
            else if (dst == IntChessBoard.A8) b.castling &= ~IntChessBoard.CASTLE_B_Q;
            else if (dst == IntChessBoard.H8) b.castling &= ~IntChessBoard.CASTLE_B_K;
        }

        b.enPassantSq = newEp;
        if (isCapture || type == IntChessBoard.PAWN) {
            b.halfmoveClock = 0;
        } else {
            b.halfmoveClock++;
        }
        if (b.sdPlayer == 1) b.fullmoveNumber++;
        b.sdPlayer = 1 - b.sdPlayer;
    }
}