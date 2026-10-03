package com.qisheng.chess.engine.gomoku;

import com.qisheng.chess.engine.BoardState;
import com.qisheng.chess.engine.BoardVariant;
import com.qisheng.chess.engine.Move;

/**
 * {@link BoardVariant} for freestyle Gomoku on a 15x15 board.
 *
 * <p><b>Rules in scope (v0.4).</b>
 * <ul>
 *   <li>Black plays first, then white. A move places a stone on an empty
 *   intersection.</li>
 *   <li>Win condition: five or more of your stones in a continuous line in
 *   any of the four directions (horizontal, vertical, two diagonals).
 *   Exactly five is the standard rule; this engine accepts six+ for the
 *   rare Renju-style overline, matching freestlye Gomoku.</li>
 *   <li>No captures — stones stay until the board resets.</li>
 *   <li>Draw: every intersection filled without a five-in-a-row.</li>
 * </ul>
 *
 * <p><b>Not in scope yet.</b> Renju opening restrictions, swap2 protocol,
 * pro opening rules — those are tournament variants; the freestyle game this
 * ships is the most common family-rule version.
 *
 * <p><b>Engine strength.</b> No alpha-beta search in v0.4. The computer
 * opponent picks the first empty intersection — adequate for board setup
 * and a teach-yourself game, but trivially beatable. A real Gomoku engine
 * (threat-space search, heuristic evaluation) is a future milestone.
 *
 * <p><b>FEN dialect.</b> Standard FEN doesn't cover Gomoku; this variant
 * uses {@code <board-rows> <side>} where each row is 15 characters from
 * {@code .xo} (empty, black, white) read from rank 15 down to rank 1 (FEN's
 * top-down convention).
 */
public final class GomokuVariant implements BoardVariant {

    public static final String ID = "gomoku";

    public GomokuVariant() {}

    @Override public String id() { return ID; }
    @Override public String displayNameKey() { return "qisheng.chess.variant.gomoku"; }

    @Override public int boardFiles() { return GomokuBoard.SIZE; }
    @Override public int boardRanks() { return GomokuBoard.SIZE; }
    @Override public int totalSquares() { return GomokuBoard.SIZE * GomokuBoard.SIZE; }

    @Override public String initialFen() {
        StringBuilder sb = new StringBuilder();
        for (int r = GomokuBoard.SIZE - 1; r >= 0; r--) {
            for (int f = 0; f < GomokuBoard.SIZE; f++) sb.append('.');
            if (r > 0) sb.append('/');
        }
        sb.append(' ').append('b');
        return sb.toString();
    }

    @Override public BoardState initialState() {
        return new GomokuBoard();
    }

    @Override public BoardState parseState(String fen) {
        if (fen == null || fen.isEmpty()) return null;
        String[] parts = fen.trim().split("\\s+");
        if (parts.length < 1 || parts.length > 2) return null;
        String[] ranks = parts[0].split("/", -1);
        if (ranks.length != GomokuBoard.SIZE) return null;
        GomokuBoard b = new GomokuBoard();
        for (int i = 0; i < GomokuBoard.SIZE; i++) {
            String row = ranks[i];
            if (row.length() != GomokuBoard.SIZE) return null;
            for (int j = 0; j < GomokuBoard.SIZE; j++) {
                char c = row.charAt(j);
                byte stone = switch (c) {
                    case '.' -> GomokuBoard.EMPTY;
                    case 'x', 'X' -> GomokuBoard.BLACK;
                    case 'o', 'O' -> GomokuBoard.WHITE;
                    default -> -1;
                };
                if (stone == -1) return null;
                // ranks[0] is rank 15 (top), ranks[14] is rank 1 (bottom)
                int sq = GomokuBoard.sq(j, GomokuBoard.SIZE - 1 - i);
                b.squares[sq] = stone;
            }
        }
        int sd = 0;
        if (parts.length >= 2 && !parts[1].isEmpty()) {
            char side = parts[1].charAt(0);
            if (side == 'b' || side == 'B') sd = 0;
            else if (side == 'w' || side == 'W') sd = 1;
            else return null;
        }
        b.sdPlayer = sd;
        b.moveCount = countStones(b);
        b.winner = detectWinner(b);
        return b;
    }

    private static int countStones(GomokuBoard b) {
        int n = 0;
        for (byte pc : b.squares) if (pc != GomokuBoard.EMPTY) n++;
        return n;
    }

    @Override public boolean isValidSquare(int sq) {
        return sq >= 0 && sq < totalSquares();
    }

    @Override public int indexForFileRank(int file, int rank) {
        return GomokuBoard.sq(file, rank);
    }

    @Override public int fileOf(int sq) {
        return GomokuBoard.fileOf(sq);
    }

    @Override public int rankOf(int sq) {
        return GomokuBoard.rankOf(sq);
    }

    @Override public byte pieceAt(BoardState state, int sq) {
        if (!(state instanceof GomokuBoard b)) return 0;
        return isValidSquare(sq) ? b.squares[sq] : 0;
    }

    @Override public int sideOfPiece(BoardState state, int sq) {
        if (!(state instanceof GomokuBoard b)) return -1;
        if (!isValidSquare(sq)) return -1;
        byte pc = b.squares[sq];
        if (pc == GomokuBoard.BLACK) return 0;
        if (pc == GomokuBoard.WHITE) return 1;
        return -1;
    }

    @Override public boolean canMove(BoardState state, int src, int dst) {
        if (!(state instanceof GomokuBoard b)) return false;
        // Gomoku is place-only: src must equal dst (just naming the square).
        if (src != dst) return false;
        if (!isValidSquare(dst)) return false;
        if (b.squares[dst] != GomokuBoard.EMPTY) return false;
        if (b.winner != GomokuBoard.EMPTY) return false;
        return true;
    }

    @Override public boolean applyMove(BoardState state, int src, int dst) {
        if (!(state instanceof GomokuBoard b)) return false;
        if (!canMove(b, src, dst)) return false;
        b.squares[dst] = (byte) (b.sdPlayer == 0 ? GomokuBoard.BLACK : GomokuBoard.WHITE);
        b.moveCount++;
        b.winner = detectWinner(b);
        b.sdPlayer = 1 - b.sdPlayer;
        return true;
    }

    @Override public String toFen(BoardState state) {
        if (!(state instanceof GomokuBoard b)) return initialFen();
        StringBuilder sb = new StringBuilder();
        for (int i = GomokuBoard.SIZE - 1; i >= 0; i--) {
            for (int f = 0; f < GomokuBoard.SIZE; f++) {
                int sq = GomokuBoard.sq(f, i);
                sb.append(switch (b.squares[sq]) {
                    case GomokuBoard.BLACK -> 'x';
                    case GomokuBoard.WHITE -> 'o';
                    default -> '.';
                });
            }
            if (i > 0) sb.append('/');
        }
        sb.append(' ').append(b.sdPlayer == 0 ? 'b' : 'w');
        return sb.toString();
    }

    @Override public int sideToMove(BoardState state) {
        if (state instanceof GomokuBoard b) return b.sdPlayer;
        return 0;
    }

    @Override public void setSideToMove(BoardState state, int sd) {
        if (state instanceof GomokuBoard b) b.sdPlayer = sd == 1 ? 1 : 0;
    }

    @Override public Move searchBestMove(String fen, int depth, int millis) {
        BoardState state = parseState(fen);
        if (!(state instanceof GomokuBoard b)) return Move.NONE;
        if (b.winner != GomokuBoard.EMPTY) return Move.NONE;
        byte me = (byte) (b.sdPlayer == 0 ? GomokuBoard.BLACK : GomokuBoard.WHITE);
        byte opp = (byte) (me == GomokuBoard.BLACK ? GomokuBoard.WHITE : GomokuBoard.BLACK);
        int bestPriority = Integer.MIN_VALUE;
        int bestSq = -1;
        for (int sq = 0; sq < totalSquares(); sq++) {
            if (b.squares[sq] != GomokuBoard.EMPTY) continue;
            int p = placementPriority(b, sq, me, opp);
            if (p > bestPriority) {
                bestPriority = p;
                bestSq = sq;
            }
        }
        return bestSq >= 0 ? new Move(bestSq, bestSq) : Move.NONE;
    }

    @Override public Move firstLegalMove(String fen) {
        BoardState state = parseState(fen);
        if (!(state instanceof GomokuBoard b)) return Move.NONE;
        if (b.winner != GomokuBoard.EMPTY) return Move.NONE;
        for (int sq = 0; sq < totalSquares(); sq++) {
            if (b.squares[sq] == GomokuBoard.EMPTY) return new Move(sq, sq);
        }
        return Move.NONE;
    }

    /**
     * Threat-scan for one empty square. Priority is decided by what placing our
     * stone there would create vs. what we would leave as a threat for the
     * opponent. The opponent's threat is treated as the pattern we must block:
     * if the square would let the opponent complete a 5 (or 4-open) we treat
     * that as the higher priority. We try the move twice in memory (my stone,
     * opponent stone) so we don't actually mutate the board.
     */
    static int placementPriority(GomokuBoard b, int sq, byte me, byte opp) {
        int myScore = patternScore(b, sq, me);
        int oppScore = patternScore(b, sq, opp);
        // If I can win here, that overrides any consideration of the opponent.
        if (myScore >= WIN_OWN) return WIN_OWN;
        // If placing opp stone here lets them win, we MUST block (or play our own win, handled above).
        if (oppScore >= WIN_OWN) return WIN_BLOCK;
        if (myScore >= OPEN4_OWN) return myScore;
        if (oppScore >= OPEN4_OWN) return Math.max(myScore, OPEN4_BLOCK);
        return Math.max(myScore, oppScore);
    }

    /** Count contiguous stones around {@code sq} as if {@code stone} were placed there.
     *  Returns the best (runLength + openSides) combination as a priority bucket. */
    static int patternScore(GomokuBoard b, int sq, byte stone) {
        int bestBucket = 0;
        int[][] dirs = {{1, 0}, {0, 1}, {1, 1}, {1, -1}};
        int f = GomokuBoard.fileOf(sq), r = GomokuBoard.rankOf(sq);
        for (int[] d : dirs) {
            int df = d[0], dr = d[1];
            int forward = 0;
            int ff = f + df, rr = r + dr;
            while (ff >= 0 && ff < GomokuBoard.SIZE && rr >= 0 && rr < GomokuBoard.SIZE
                    && b.squares[GomokuBoard.sq(ff, rr)] == stone) {
                forward++;
                ff += df;
                rr += dr;
            }
            boolean forwardOpen = ff >= 0 && ff < GomokuBoard.SIZE
                    && rr >= 0 && rr < GomokuBoard.SIZE
                    && b.squares[GomokuBoard.sq(ff, rr)] == GomokuBoard.EMPTY;
            int backward = 0;
            int bf = f - df, br = r - dr;
            while (bf >= 0 && bf < GomokuBoard.SIZE && br >= 0 && br < GomokuBoard.SIZE
                    && b.squares[GomokuBoard.sq(bf, br)] == stone) {
                backward++;
                bf -= df;
                br -= dr;
            }
            boolean backwardOpen = bf >= 0 && bf < GomokuBoard.SIZE
                    && br >= 0 && br < GomokuBoard.SIZE
                    && b.squares[GomokuBoard.sq(bf, br)] == GomokuBoard.EMPTY;
            int len = forward + backward + 1;
            boolean openBoth = forwardOpen && backwardOpen;
            boolean openOne = forwardOpen || backwardOpen;
            int bucket;
            if (len >= 5) bucket = WIN_OWN;
            else if (len == 4 && openBoth) bucket = OPEN4_OWN;
            else if (len == 4 && openOne) bucket = CLOSED4_OWN;
            else if (len == 3 && openBoth) bucket = OPEN3_OWN;
            else if (len == 3 && openOne) bucket = CLOSED3_OWN;
            else if (len == 2 && openBoth) bucket = OPEN2_OWN;
            else bucket = 0;
            if (bucket > bestBucket) bestBucket = bucket;
        }
        return bestBucket;
    }

    /** Priority buckets — higher number wins. */
    static final int WIN_OWN = 100000;
    static final int WIN_BLOCK = 90000;
    static final int OPEN4_OWN = 8000;
    static final int OPEN4_BLOCK = 7000;
    static final int CLOSED4_OWN = 500;
    static final int OPEN3_OWN = 400;
    static final int CLOSED3_OWN = 50;
    static final int OPEN2_OWN = 10;

    @Override public boolean isInCheck(BoardState state, int side) {
        // Gomoku has no "check" concept.
        return false;
    }

    @Override public boolean isCheckmate(BoardState state) {
        if (!(state instanceof GomokuBoard b)) return false;
        return b.winner != GomokuBoard.EMPTY;
    }

    @Override public boolean isStalemate(BoardState state) {
        if (!(state instanceof GomokuBoard b)) return false;
        if (b.winner != GomokuBoard.EMPTY) return false;
        return b.moveCount >= totalSquares();
    }

    @Override public char pieceFenChar(BoardState state, int sq) {
        if (!(state instanceof GomokuBoard b)) return '.';
        if (!isValidSquare(sq)) return '.';
        return switch (b.squares[sq]) {
            case GomokuBoard.BLACK -> 'x';
            case GomokuBoard.WHITE -> 'o';
            default -> '.';
        };
    }

    @Override public int legalDestsBitmapSize() {
        // 225 bits → ceil(225/8) = 29 bytes
        return (totalSquares() + 7) / 8;
    }

    // ---- Win detection ----

    /**
     * Walk the four directions from each placed stone; if any stone is part of
     * a run of five or more, the game is over and the colour wins. Called
     * after each placement, so the worst case is one board scan per move.
     */
    static byte detectWinner(GomokuBoard b) {
        int[][] dirs = {{1, 0}, {0, 1}, {1, 1}, {1, -1}};
        for (int sq = 0; sq < GomokuBoard.SIZE * GomokuBoard.SIZE; sq++) {
            byte stone = b.squares[sq];
            if (stone == GomokuBoard.EMPTY) continue;
            int f0 = GomokuBoard.fileOf(sq), r0 = GomokuBoard.rankOf(sq);
            for (int[] d : dirs) {
                int df = d[0], dr = d[1];
                // Walk backward to the start of the run, then forward count.
                int fStart = f0, rStart = r0;
                while (true) {
                    int prevF = fStart - df, prevR = rStart - dr;
                    if (prevF < 0 || prevF >= GomokuBoard.SIZE
                            || prevR < 0 || prevR >= GomokuBoard.SIZE) break;
                    if (b.squares[GomokuBoard.sq(prevF, prevR)] != stone) break;
                    fStart = prevF;
                    rStart = prevR;
                }
                int len = 0;
                int f = fStart, r = rStart;
                while (f >= 0 && f < GomokuBoard.SIZE && r >= 0 && r < GomokuBoard.SIZE
                        && b.squares[GomokuBoard.sq(f, r)] == stone) {
                    len++;
                    f += df;
                    r += dr;
                }
                if (len >= 5) return stone;
            }
        }
        return GomokuBoard.EMPTY;
    }
}
