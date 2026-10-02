package com.qisheng.chess.engine.international;

import com.qisheng.chess.engine.BoardState;

/**
 * Mutable 8x8 chess board for the international-chess variant.
 *
 * <p><b>Indexing.</b> Square {@code file + rank*8}: a1 = 0, h1 = 7, a2 = 8,
 * …, h8 = 63. Files run 0..7 from a to h, ranks run 0..7 from White's side
 * to Black's side. Helpers on the variant class convert from the standard
 * "e2" notation when needed.
 *
 * <p><b>Piece bytes.</b> The low 3 bits hold the base type (1..6 = pawn,
 * knight, bishop, rook, queen, king). Side sits in bits 3-4 (bit 3 = white,
 * bit 4 = black). This matches the byte layout xiangqi uses, so the same
 * mask arithmetic — {@code piece & 8} for white, {@code piece & 16} for
 * black — works for both games without per-variant branching at call sites
 * that already need the dichotomy.
 *
 * <p><b>Castling.</b> A 4-bit field, one bit per right: bit 0 = K (white
 * kingside), bit 1 = Q (white queenside), bit 2 = k (black kingside),
 * bit 3 = q (black queenside). FEN's {@code KQkq} (or {@code -}) maps
 * 1-to-1.
 *
 * <p><b>En-passant.</b> The square BEHIND the pawn that just pushed two
 * squares — i.e. the square a following en-passant capture will land on.
 * -1 means "no en-passant opportunity on the next move".
 *
 * <p><b>50-move rule.</b> {@code halfmoveClock} ticks on every non-capture,
 * non-pawn-push move. The game is drawn when it reaches 100.
 */
public final class IntChessBoard implements BoardState {

    // --- Square indexing helpers ---

    public static final int A1 = 0, B1 = 1, C1 = 2, D1 = 3, E1 = 4, F1 = 5, G1 = 6, H1 = 7;
    public static final int A8 = 56, B8 = 57, C8 = 58, D8 = 59, E8 = 60, F8 = 61, G8 = 62, H8 = 63;

    public static int fileOf(int sq) { return sq & 7; }
    public static int rankOf(int sq) { return sq >>> 3; }
    public static int sq(int file, int rank) { return file + (rank << 3); }

    // --- Piece bytes ---

    public static final byte EMPTY = 0;
    public static final byte PAWN = 1, KNIGHT = 2, BISHOP = 3, ROOK = 4, QUEEN = 5, KING = 6;
    /** White = base + 8 (bit 3). */
    public static final byte W_PAWN = 9, W_KNIGHT = 10, W_BISHOP = 11, W_ROOK = 12, W_QUEEN = 13, W_KING = 14;
    /** Black = base + 16 (bit 4). */
    public static final byte B_PAWN = 17, B_KNIGHT = 18, B_BISHOP = 19, B_ROOK = 20, B_QUEEN = 21, B_KING = 22;

    public static byte colorOf(byte piece) {
        return (byte) ((piece & 8) != 0 ? 0 : ((piece & 16) != 0 ? 1 : -1));
    }

    public static byte typeOf(byte piece) {
        return (byte) (piece & 7);
    }

    /** SIDE_TAG(side) → colour mask: red/white = 8, black = 16. */
    public static int sideTag(int sd) {
        return sd == 0 ? 8 : 16;
    }

    // --- Castling bits ---

    public static final int CASTLE_W_K = 1, CASTLE_W_Q = 2, CASTLE_B_K = 4, CASTLE_B_Q = 8;

    // --- State ---

    public byte[] squares = new byte[64];
    public int sdPlayer = 0;       // 0 = white, 1 = black
    public int castling = 0;
    public int enPassantSq = -1;
    public int halfmoveClock = 0;
    public int fullmoveNumber = 1;

    /** A pristine board ready for {@code initialFen()} to be loaded. */
    public IntChessBoard() {}
}