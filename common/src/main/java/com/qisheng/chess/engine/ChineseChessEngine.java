package com.qisheng.chess.engine;

import com.qisheng.chess.engine.xqwlight.Position;
import com.qisheng.chess.engine.xqwlight.Search;

/**
 * Guarded facade over the bundled xqwlight engine
 * ({@link com.qisheng.chess.engine.xqwlight.Position}).
 *
 * <h2>Why this exists</h2>
 * {@code Position} is a direct port of C code and its public mutators assume a
 * well-behaved caller:
 * <ul>
 *   <li>{@code Position.IN_BOARD(sq)} / {@code IN_FORT(sq)} index a lookup table
 *       with no bounds check, so a negative or &gt;255 square throws
 *       {@link ArrayIndexOutOfBoundsException}.</li>
 *   <li>{@code Position.makeMove(mv)} performs <em>no</em> validation. It does
 *       not check that the source square holds a piece of the side to move, or
 *       that the destination is reachable. Called on an empty source square it
 *       goes {@code movePiece() → delPiece(sq, 0) → PIECE_VALUE[0 - 8]} and
 *       throws. It only returns {@code false} when the move is geometrically
 *       meaningless <em>and</em> leaves the mover's own king in check.</li>
 *   <li>{@code legalMove(mv)} is the real validator, but it must never be handed
 *       a square pair where the source is empty or off-board — the piece-type
 *       switch then runs with {@code pcSrc - pcSelfSide} outside its expected
 *       range.</li>
 * </ul>
 *
 * <p>Every path in this mod that turns player input into a board mutation goes
 * through {@link #canMove} / {@link #applyMove} instead of touching the engine
 * directly, so a malformed packet or command can only ever be answered with
 * {@code false} — never with a server-side exception on the tick thread.
 *
 * <p>Deliberately Minecraft-free so it can be unit tested on a plain JVM.
 */
public final class ChineseChessEngine {

    private ChineseChessEngine() {}

    /** The engine's square array is {@code byte[256]}; nothing may index past it. */
    public static final int SQUARE_ARRAY_SIZE = 256;

    /**
     * True when {@code sq} is one of the 90 playable points.
     *
     * <p>Safe for any {@code int} — unlike {@link Position#IN_BOARD(int)}, which
     * indexes a table first.
     */
    public static boolean isSquare(int sq) {
        return sq >= 0 && sq < SQUARE_ARRAY_SIZE && Position.IN_BOARD(sq);
    }

    /** The piece byte on {@code sq}, or {@code 0} when {@code sq} is not playable. */
    public static byte pieceAt(Position pos, int sq) {
        return isSquare(sq) ? pos.squares[sq] : 0;
    }

    /** True when {@code sq} holds a piece belonging to the side to move. */
    public static boolean isOwnPiece(Position pos, int sq) {
        if (!isSquare(sq)) return false;
        int pc = pos.squares[sq];
        return pc != 0 && (pc & Position.SIDE_TAG(pos.sdPlayer)) != 0;
    }

    /**
     * True when moving {@code src → dst} is a legal move for the side to move.
     *
     * <p>Rejects — without ever throwing — everything {@code Position} assumes
     * away: off-board squares, a null/empty source, {@code src == dst}, and
     * captures of one's own piece.
     */
    public static boolean canMove(Position pos, int src, int dst) {
        if (pos == null) return false;
        if (src == dst) return false;
        // isSquare() must run before p.squares[...] and before IN_BOARD's table.
        if (!isSquare(src) || !isSquare(dst)) return false;
        if (!isOwnPiece(pos, src)) return false;
        // Own-piece destination: rejected here as well as inside legalMove so the
        // contract holds even if the engine's checks ever change.
        int selfTag = Position.SIDE_TAG(pos.sdPlayer);
        if ((pos.squares[dst] & selfTag) != 0) return false;
        return pos.legalMove(Position.MOVE(src, dst));
    }

    /**
     * Validate and apply a move in one step.
     *
     * @return {@code true} when the board was mutated; {@code false} when the
     *         move was malformed, illegal, or would expose the mover's own king
     *         (in which case the board is left exactly as it was).
     */
    public static boolean applyMove(Position pos, int src, int dst) {
        if (!canMove(pos, src, dst)) return false;
        return pos.makeMove(Position.MOVE(src, dst));
    }

    // ---- FEN ----

    private static final String PIECE_LETTERS = "rnbakcpRNBAKCP";

    /**
     * Structural check of a FEN string.
     *
     * <p>{@link Position#fromFenString(String)} performs <em>no</em> validation
     * and never returns {@code null}: unparseable input silently yields an empty
     * or nonsense board (the letter {@code g} is ignored, {@code a r b a} becomes
     * four black pieces on red's back rank) and {@code null} input throws. This
     * is the gate that has to run first.
     *
     * <p>Accepts both the two-field form this mod's {@code toFen()} emits
     * ({@code board side}) and the full six-field FEN.
     */
    public static boolean isWellFormedFen(String fen) {
        if (fen == null) return false;
        int sp = fen.indexOf(' ');
        String board = (sp < 0 ? fen : fen.substring(0, sp)).trim();
        String[] ranks = board.split("/", -1);
        if (ranks.length != 10) return false;
        for (String rank : ranks) {
            int width = 0;
            for (int i = 0; i < rank.length(); i++) {
                char c = rank.charAt(i);
                if (c >= '1' && c <= '9') {
                    width += c - '0';
                } else if (PIECE_LETTERS.indexOf(c) >= 0) {
                    width += 1;
                } else {
                    return false;
                }
            }
            if (width != 9) return false;
        }
        if (sp >= 0) {
            String rest = fen.substring(sp + 1).trim();
            if (!rest.isEmpty()) {
                char side = rest.charAt(0);
                if (side != 'w' && side != 'b') return false;
            }
        }
        return true;
    }

    /**
     * Parse a FEN, returning {@code null} instead of a nonsense board when the
     * input is missing or structurally invalid.
     */
    public static Position parseFen(String fen) {
        return isWellFormedFen(fen) ? Position.fromFenString(fen) : null;
    }

    // ---- Search (computer opponent) ----

    /**
     * Transposition-table size for one search, as a base-2 exponent
     * ({@code 1 << 14} slots ≈ 400 KB). Large enough that a few-hundred-millisecond
     * search is not crippled by hash pressure, small enough that allocating a
     * fresh table per move is cheaper than pooling one.
     */
    private static final int SEARCH_HASH_LEVEL = 14;

    /** The engine's own iterative-deepening cap; the time budget is what stops it. */
    public static final int SEARCH_MAX_DEPTH = 64;

    /**
     * Best move for the side to move, encoded by {@link Position#MOVE}, or
     * {@code 0} when the FEN is unusable or the side to move has no legal move
     * (checkmate, or stalemate — which is also a loss in xiangqi).
     *
     * <p>This takes a <b>FEN string</b> rather than a {@link Position} on purpose.
     * The search makes and unmakes moves on the board it is handed and resets
     * {@code distance}, so a caller's live board must never be passed in.
     * Searching a private parse is also what makes this safe to run on a worker
     * thread while the server keeps mutating its own board — and a search must
     * not run on the tick thread at all.
     *
     * <p>Never throws for a malformed {@code fen}: {@link #parseFen} gates it.
     *
     * @param depth  maximum iterative-deepening depth ({@link #SEARCH_MAX_DEPTH})
     * @param millis hard time budget; the search also self-limits internally,
     *               so a single deep iteration cannot overrun it
     */
    public static int searchBestMove(String fen, int depth, int millis) {
        Position pos = parseFen(fen);
        if (pos == null) return 0;
        return new Search(pos, SEARCH_HASH_LEVEL).searchMain(depth, millis);
    }

    /**
     * The first legal move for the side to move, in the engine's own generation
     * order, or {@code 0} when the side is mated.
     *
     * <p>Deliberately weak — it exists so the computer opponent has a move to
     * play when the search returns something the board rejects. A search bug
     * should cost the computer a good move, not wedge the game on a turn nobody
     * will ever take.
     *
     * <p>Like {@link #searchBestMove}, it works on a private parse: finding the
     * move means making and unmaking moves, which must never happen on a board
     * the server is still using.
     */
    public static int firstLegalMove(String fen) {
        Position pos = parseFen(fen);
        if (pos == null) return 0;
        int[] mvs = new int[Position.MAX_GEN_MOVES];
        int count = pos.generateAllMoves(mvs);
        for (int i = 0; i < count; i++) {
            if (pos.makeMove(mvs[i])) {
                pos.undoMakeMove();
                return mvs[i];
            }
        }
        return 0;
    }
}
