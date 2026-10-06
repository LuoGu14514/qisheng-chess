package com.qisheng.chess.engine;

/**
 * One chess-like game (xiangqi, international chess, …) expressed as the set
 * of operations a {@code GameSession} needs to apply a move, broadcast a
 * board, and let the computer opponent play.
 *
 * <h2>Square indices</h2>
 * Each variant picks its own indexing scheme:
 * <ul>
 *   <li>Xiangqi: the 256-element {@code Position} coordinate
 *   ({@code COORD_XY(file+3, rank+3)}).</li>
 *   <li>International chess: a 0..63 a1-anchored index
 *   ({@code file + rank*8} or {@code rank*8 + file} — whichever the variant
 *       documents).</li>
 * </ul>
 * Cross-variant code never sees a raw index; the variant decides.
 *
 * <h2>Piece encoding</h2>
 * Both xiangqi and chess happen to use the same byte shape — base type in
 * the low 3 bits, side in bits 3-4 — so a piece byte travels between
 * variants without translation. That is a happy coincidence, not an
 * invariant the rest of the mod relies on: new variants may pick anything
 * they like.
 *
 * <h2>Search &amp; computer opponent</h2>
 * {@link #searchBestMove} and {@link #firstLegalMove} take a <b>FEN string</b>
 * rather than a {@code BoardState}, exactly like
 * {@link ChineseChessEngine#searchBestMove}. The engine mutates the board it
 * is handed, so a search must never see the live session board; the FEN is
 * re-parsed onto a private copy.
 *
 * <h2>Wire format</h2>
 * The legal-destinations bitmap is shipped as
 * {@link #legalDestsBitmapSize} bytes — 32 for xiangqi, 8 for chess —
 * LSB-first within each byte. The variant never needs to know how the wire
 * is framed; only how many squares it owns.
 */
public interface BoardVariant {

    /** Stable, lowercase identifier used in NBT and on the wire ("xiangqi", "international"). */
    String id();

    /** Human-facing name; resolved through the {@code qisheng.chess.variant.*} lang key. */
    String displayNameKey();

    /** Files per rank (xiangqi = 9, chess = 8). */
    int boardFiles();

    /** Ranks per board (xiangqi = 10, chess = 8). */
    int boardRanks();

    /** Total playable squares — for {@link #legalDestsBitmapSize}. */
    int totalSquares();

    /** Standard starting position in the variant's FEN dialect. */
    String initialFen();

    /** A fresh board in its starting position. */
    BoardState initialState();

    /**
     * Parse a FEN string. Returns {@code null} for malformed input rather than
     * producing a nonsense board (see {@link ChineseChessEngine#isWellFormedFen}
     * for the xiangqi precedent).
     */
    BoardState parseState(String fen);

    /** True if {@code sq} is a valid square index for this variant. */
    boolean isValidSquare(int sq);

    /**
     * Variant-local square index for {@code (file, rank)} with
     * {@code 0 ≤ file < boardFiles()} and {@code 0 ≤ rank < boardRanks()}.
     *
     * <p>The encoding is variant-defined. Xiangqi maps to a 256-element padded
     * coordinate; international chess uses {@code file + rank*8}; the gomoku
     * and go variants use {@code file + rank*files}. Callers that need the
     * inverse use {@link #fileOf} and {@link #rankOf}.
     */
    int indexForFileRank(int file, int rank);

    /** File (column) component of a variant-local square index. */
    int fileOf(int sq);

    /** Rank (row) component of a variant-local square index. */
    int rankOf(int sq);

    /** Piece byte at sq, or {@code 0} for empty / off-board. */
    byte pieceAt(BoardState state, int sq);

    /** {@code 0} for red/white, {@code 1} for black, {@code -1} for empty / off-board. */
    int sideOfPiece(BoardState state, int sq);

    /**
     * True when moving {@code src → dst} is legal for the side to move, and
     * would not leave the mover's own king in check.
     */
    boolean canMove(BoardState state, int src, int dst);

    /**
     * True when this variant accepts a single-click placement action instead
     * of a select→move flow (gomoku, go). Default is {@code false}; placement
     * variants override this so the server can skip the {@code selectPoint}
     * matching check in {@code GameLogic.tryMove}.
     */
    default boolean isPlacementOnly() { return false; }

    /**
     * Make {@code src → dst} on {@code state} (mutating it). Returns
     * {@code true} on success, {@code false} when the move is illegal /
     *   off-board / exposes the mover's king (in which case {@code state} is
     *   left exactly as it was).
     */
    boolean applyMove(BoardState state, int src, int dst);

    /** The variant's FEN string for the current position. */
    String toFen(BoardState state);

    /** Side to move: {@code 0} for red/white, {@code 1} for black. */
    int sideToMove(BoardState state);

    /** Replace the side-to-move on a fresh {@code BoardState} (used by FEN loading). */
    void setSideToMove(BoardState state, int sd);

    /** Best move from {@code fen} within the time budget, or {@link Move#NONE}. */
    Move searchBestMove(String fen, int depth, int millis);

    /** First legal move from {@code fen}, or {@link Move#NONE} when none exists. */
    Move firstLegalMove(String fen);

    /** True when {@code side}'s king is currently attacked. */
    boolean isInCheck(BoardState state, int side);

    /** True when the side to move has no escape from check. */
    boolean isCheckmate(BoardState state);

    /** True when the side to move has no legal move and is not in check. */
    boolean isStalemate(BoardState state);

    /** ASCII character for the piece on {@code sq}, used by FEN round-trip tests. */
    char pieceFenChar(BoardState state, int sq);

    /** Bytes per side of the legal-destinations bitmap on the wire. */
    int legalDestsBitmapSize();
}