package com.qisheng.chess.engine.go;

import com.qisheng.chess.engine.BoardState;
import com.qisheng.chess.engine.BoardVariant;
import com.qisheng.chess.engine.Move;

/**
 * {@link BoardVariant} for the game of Go, on boards of size 9 and 19.
 *
 * <p><b>Variants in scope (v0.4).</b> Two pre-registered ids share this class:
 * {@link #GO_9} ({@code go9}) and {@link #GO_19} ({@code go19}). They differ
 * only in {@link #boardFiles()}, {@link #boardRanks()}, and {@link #totalSquares()};
 * every rule below is identical.
 *
 * <p><b>Rules in scope.</b>
 * <ul>
 *   <li><b>Placement.</b> Place a stone of your colour on an empty
 *   intersection, or pass. Placement is allowed only if it would not leave
 *   the placed stone (or, after captures, the placed stone's group) without
 *   liberties — the suicide rule.</li>
 *   <li><b>Capture.</b> Adjacent opponent groups with no liberties after the
 *   placement are removed; each removed stone increments the mover's
 *   capture counter.</li>
 *   <li><b>Ko.</b> A move that recreates the position from one ply ago is
 *   forbidden. We track just the single {@code koSquare} (the recapture
 *   target) — full positional superko is a future milestone.</li>
 *   <li><b>Pass.</b> Pass is legal at any time. Two consecutive passes end
 *   the game; the GUI then enters the finished state and the variant's
 *   {@link #isCheckmate} predicate returns true.</li>
 *   <li><b>Scoring (Chinese).</b> Area scoring with komi 7.5 for 9x9 and
 *   7.5 for 19x19. Not in this class — the GUI computes it from the final
 *   board plus captures + komi.</li>
 * </ul>
 *
 * <p><b>Not in scope yet.</b> Japanese rules (territory scoring), positional
 * superko beyond the simple ko-square guard, handicap stones, dead-stone
 * removal phase, byo-yomi, life-and-death detection. The mod currently
 * scores Chinese-area and ignores dead stones — a simplification good
 * enough for casual play.
 *
 * <p><b>Engine strength.</b> No search. {@link #searchBestMove} returns
 * {@code firstLegalMove}, which places the first empty intersection
 * roughly in the upper-left quadrant. A real Go engine (Monte Carlo, MCTS,
 * or even just heuristic eye/capture) is a future milestone.
 *
 * <p><b>FEN dialect.</b> Standard FEN doesn't cover Go. We use
 * {@code <board-rows> <side> <passes> <koSquare-or--> <bCap> <wCap>}
 * where each row is {@code size} characters from {@code .xo} read from
 * rank {@code size} down to rank 1. The capture counters round-trip so a
 * saved game resumes with the same captured-stone totals.
 */
public final class GoVariant implements BoardVariant {

    public static final String ID_9  = "go9";
    public static final String ID_19 = "go19";

    /** Komi in points. 7.5 is the standard modern value; the half avoids
     *  draws under area scoring. */
    public static final double KOMI = 7.5;

    /** Pre-registered 9x9 instance. */
    public static final GoVariant GO_9  = new GoVariant(9,  ID_9);
    /** Pre-registered 19x19 instance. */
    public static final GoVariant GO_19 = new GoVariant(19, ID_19);

    private final int size;
    private final String id;

    public GoVariant(int size, String id) {
        if (size != 9 && size != 19) {
            throw new IllegalArgumentException("go variant supports size 9 or 19, got " + size);
        }
        this.size = size;
        this.id = id;
    }

    public int getSize() { return size; }

    @Override public String id() { return id; }
    @Override public String displayNameKey() {
        return size == 9 ? "qisheng.chess.variant.go9" : "qisheng.chess.variant.go19";
    }

    @Override public int boardFiles() { return size; }
    @Override public int boardRanks() { return size; }
    @Override public int totalSquares() { return size * size; }

    @Override public String initialFen() {
        StringBuilder sb = new StringBuilder();
        for (int r = size - 1; r >= 0; r--) {
            for (int f = 0; f < size; f++) sb.append('.');
            if (r > 0) sb.append('/');
        }
        sb.append(" b 0 - 0 0");
        return sb.toString();
    }

    @Override public BoardState initialState() {
        GoBoard b = new GoBoard(size);
        return b;
    }

    @Override public BoardState parseState(String fen) {
        if (fen == null) return null;
        String[] parts = fen.trim().split("\\s+");
        if (parts.length < 6) return null;
        String[] ranks = parts[0].split("/", -1);
        if (ranks.length != size) return null;
        GoBoard b = new GoBoard(size);
        for (int i = 0; i < size; i++) {
            String row = ranks[i];
            if (row.length() != size) return null;
            for (int j = 0; j < size; j++) {
                char c = row.charAt(j);
                byte stone = switch (c) {
                    case '.' -> GoBoard.EMPTY;
                    case 'x', 'X' -> GoBoard.BLACK;
                    case 'o', 'O' -> GoBoard.WHITE;
                    default -> -1;
                };
                if (stone == -1) return null;
                int sq = b.sq(j, size - 1 - i);
                b.squares[sq] = stone;
            }
        }
        // side
        char side = parts[1].isEmpty() ? 'b' : parts[1].charAt(0);
        if (side != 'b' && side != 'B' && side != 'w' && side != 'W') return null;
        b.sdPlayer = (side == 'b' || side == 'B') ? 0 : 1;
        // passes
        int passes;
        try { passes = Integer.parseInt(parts[2]); }
        catch (NumberFormatException e) { return null; }
        if (passes < 0 || passes > 2) return null;
        b.passes = passes;
        // koSquare
        if (!parts[3].equals("-") && !parts[3].isEmpty()) {
            String ks = parts[3];
            if (ks.length() != 2) return null;
            int kf = ks.charAt(0) - 'a';
            int kr = ks.charAt(1) - '1';
            if (kf < 0 || kf >= size || kr < 0 || kr >= size) return null;
            b.koSquare = b.sq(kf, kr);
        } else {
            b.koSquare = -1;
        }
        // captures
        try {
            b.blackCaptures = Integer.parseInt(parts[4]);
            b.whiteCaptures = Integer.parseInt(parts[5]);
        } catch (NumberFormatException e) { return null; }
        if (b.blackCaptures < 0 || b.whiteCaptures < 0) return null;
        b.finished = b.passes >= 2;
        return b;
    }

    @Override public boolean isValidSquare(int sq) {
        return sq >= 0 && sq < totalSquares();
    }

    @Override public int indexForFileRank(int file, int rank) {
        return file + rank * size;
    }

    @Override public int fileOf(int sq) {
        return sq % size;
    }

    @Override public int rankOf(int sq) {
        return sq / size;
    }

    @Override public byte pieceAt(BoardState state, int sq) {
        if (!(state instanceof GoBoard b)) return 0;
        return isValidSquare(sq) ? b.squares[sq] : 0;
    }

    @Override public int sideOfPiece(BoardState state, int sq) {
        if (!(state instanceof GoBoard b)) return -1;
        if (!isValidSquare(sq)) return -1;
        byte pc = b.squares[sq];
        if (pc == GoBoard.BLACK) return 0;
        if (pc == GoBoard.WHITE) return 1;
        return -1;
    }

    @Override public boolean canMove(BoardState state, int src, int dst) {
        if (!(state instanceof GoBoard b)) return false;
        if (src != dst) return false;          // Go: place-only, src == dst
        if (!isValidSquare(dst)) return false;
        if (b.finished) return false;
        if (b.squares[dst] != GoBoard.EMPTY) return false;
        if (dst == b.koSquare) return false;    // simple ko guard
        return placementLeavesLiberty(b, dst);
    }

    @Override public boolean applyMove(BoardState state, int src, int dst) {
        if (!(state instanceof GoBoard b)) return false;
        if (!canMove(b, src, dst)) return false;
        byte stone = (byte) (b.sdPlayer == 0 ? GoBoard.BLACK : GoBoard.WHITE);
        byte oppStone = (byte) (b.sdPlayer == 0 ? GoBoard.WHITE : GoBoard.BLACK);
        b.squares[dst] = stone;
        int capturedCount = 0;
        int singleCaptureSq = -1;
        // Capture opponent groups adjacent to dst with no liberties.
        int[] adj = adjacent(b, dst);
        for (int a : adj) {
            if (b.squares[a] != oppStone) continue;
            if (groupHasLiberty(b, a)) continue;
            int removed = removeGroup(b, a);
            capturedCount += removed;
            singleCaptureSq = a;
        }
        if (b.sdPlayer == 0) b.blackCaptures += capturedCount;
        else                  b.whiteCaptures += capturedCount;
        // Ko rule: a move that captures exactly one stone, leaving that
        // square empty and only the placed stone on its own, sets koSquare
        // to the captured square — the standard simple-ko shape.
        if (capturedCount == 1 && groupHasOnlyOneStone(b, dst)) {
            b.koSquare = singleCaptureSq;
        } else {
            b.koSquare = -1;
        }
        b.passes = 0;
        b.sdPlayer = 1 - b.sdPlayer;
        return true;
    }

    @Override public String toFen(BoardState state) {
        if (!(state instanceof GoBoard b)) return initialFen();
        StringBuilder sb = new StringBuilder();
        for (int i = size - 1; i >= 0; i--) {
            for (int f = 0; f < size; f++) {
                int sq = b.sq(f, i);
                sb.append(switch (b.squares[sq]) {
                    case GoBoard.BLACK -> 'x';
                    case GoBoard.WHITE -> 'o';
                    default -> '.';
                });
            }
            if (i > 0) sb.append('/');
        }
        sb.append(' ').append(b.sdPlayer == 0 ? 'b' : 'w');
        sb.append(' ').append(b.passes);
        sb.append(' ');
        if (b.koSquare < 0) {
            sb.append('-');
        } else {
            int kf = b.fileOf(b.koSquare), kr = b.rankOf(b.koSquare);
            sb.append((char) ('a' + kf)).append(kr + 1);
        }
        sb.append(' ').append(b.blackCaptures);
        sb.append(' ').append(b.whiteCaptures);
        return sb.toString();
    }

    @Override public int sideToMove(BoardState state) {
        if (state instanceof GoBoard b) return b.sdPlayer;
        return 0;
    }

    @Override public void setSideToMove(BoardState state, int sd) {
        if (state instanceof GoBoard b) b.sdPlayer = sd == 1 ? 1 : 0;
    }

    /**
     * "Pass" is exposed as a move with {@code src = dst = -1}. The wire /
     * GUI paths route to {@link #applyPass}, but the search API takes a
     * Move — {@code Move.NONE} is reserved for "no move", so we use
     * {@code Move(-1, -1)} as the sentinel for pass.
     *
     * <p>v0.4 ships no GUI for passing — passing in a GUI-only game is
     * unusual, and the AI doesn't pass on its own yet. We still implement
     * it so the variant is rules-correct for future milestones.
     */
    public boolean applyPass(BoardState state) {
        if (!(state instanceof GoBoard b)) return false;
        if (b.finished) return false;
        b.koSquare = -1;
        b.passes++;
        if (b.passes >= 2) {
            b.finished = true;
        } else {
            b.sdPlayer = 1 - b.sdPlayer;
        }
        return true;
    }

    @Override public Move searchBestMove(String fen, int depth, int millis) {
        return firstLegalMove(fen);
    }

    @Override public Move firstLegalMove(String fen) {
        BoardState state = parseState(fen);
        if (!(state instanceof GoBoard b)) return Move.NONE;
        if (b.finished) return Move.NONE;
        // Try a sensible first move near the upper-left (rank size-1, file 0).
        for (int r = size - 1; r >= 0; r--) {
            for (int f = 0; f < size; f++) {
                int sq = b.sq(f, r);
                if (canMove(b, sq, sq)) return new Move(sq, sq);
            }
        }
        return Move.NONE;
    }

    @Override public boolean isInCheck(BoardState state, int side) {
        // Go has no check concept.
        return false;
    }

    @Override public boolean isCheckmate(BoardState state) {
        // In Go we model "game over" (two passes) as a checkmate-equivalent.
        // isStalemate stays false for the finished board so the caller can
        // distinguish "ended" from "drawn".
        if (!(state instanceof GoBoard b)) return false;
        return b.finished;
    }

    @Override public boolean isStalemate(BoardState state) {
        // Two-pass finish: white gets komi so black almost always loses;
        // we don't model draws separately. Future: detect triple-ko,
        // four-pass seki, etc.
        return false;
    }

    @Override public char pieceFenChar(BoardState state, int sq) {
        if (!(state instanceof GoBoard b)) return '.';
        if (!isValidSquare(sq)) return '.';
        return switch (b.squares[sq]) {
            case GoBoard.BLACK -> 'x';
            case GoBoard.WHITE -> 'o';
            default -> '.';
        };
    }

    @Override public int legalDestsBitmapSize() {
        return (totalSquares() + 7) / 8;
    }

    // ---- Capture / liberty helpers ----

    private static int[] adjacent(GoBoard b, int sq) {
        int f = b.fileOf(sq), r = b.rankOf(sq);
        int[] out = new int[4];
        int n = 0;
        if (f > 0)          out[n++] = b.sq(f - 1, r);
        if (f < b.size - 1) out[n++] = b.sq(f + 1, r);
        if (r > 0)          out[n++] = b.sq(f, r - 1);
        if (r < b.size - 1) out[n++] = b.sq(f, r + 1);
        int[] trimmed = new int[n];
        System.arraycopy(out, 0, trimmed, 0, n);
        return trimmed;
    }

    /** True if the group containing {@code sq} has at least one liberty. */
    private static boolean groupHasLiberty(GoBoard b, int sq) {
        byte target = b.squares[sq];
        if (target == GoBoard.EMPTY) return true;
        boolean[] seen = new boolean[b.size * b.size];
        int[] stack = new int[b.size * b.size];
        int sp = 0;
        stack[sp++] = sq;
        seen[sq] = true;
        while (sp > 0) {
            int cur = stack[--sp];
            int f = b.fileOf(cur), r = b.rankOf(cur);
            int[] adj = adjacent(b, cur);
            for (int a : adj) {
                byte pc = b.squares[a];
                if (pc == GoBoard.EMPTY) return true;
                if (pc == target && !seen[a]) {
                    seen[a] = true;
                    stack[sp++] = a;
                }
            }
        }
        return false;
    }

    /**
     * Returns true if placing at {@code dst} on {@code b} would not leave the
     * placed stone's group without liberties, taking captures into account.
     * Captures may rescue the placement from suicide — that's the standard
     * Go rule.
     */
    private static boolean placementLeavesLiberty(GoBoard b, int dst) {
        int f = b.fileOf(dst), r = b.rankOf(dst);
        byte stone = (byte) (b.sdPlayer == 0 ? GoBoard.BLACK : GoBoard.WHITE);
        byte opp = (byte) (b.sdPlayer == 0 ? GoBoard.WHITE : GoBoard.BLACK);
        // If any adjacent opponent group has no liberties, the placement
        // captures and is therefore legal even if our own stone has no
        // liberties.
        int[] adj = adjacent(b, dst);
        for (int a : adj) {
            if (b.squares[a] == opp && !groupHasLiberty(b, a)) return true;
        }
        // Otherwise the placed stone must itself have at least one liberty
        // (it counts its own neighbours). Temporarily place it and check.
        b.squares[dst] = stone;
        boolean ok = groupHasLiberty(b, dst);
        b.squares[dst] = GoBoard.EMPTY;
        return ok;
    }

    /** Remove the entire group containing {@code sq}; return how many stones were removed. */
    private static int removeGroup(GoBoard b, int sq) {
        byte target = b.squares[sq];
        int count = 0;
        boolean[] seen = new boolean[b.size * b.size];
        int[] stack = new int[b.size * b.size];
        int sp = 0;
        stack[sp++] = sq;
        seen[sq] = true;
        while (sp > 0) {
            int cur = stack[--sp];
            b.squares[cur] = GoBoard.EMPTY;
            count++;
            int[] adj = adjacent(b, cur);
            for (int a : adj) {
                if (b.squares[a] == target && !seen[a]) {
                    seen[a] = true;
                    stack[sp++] = a;
                }
            }
        }
        return count;
    }

    private static boolean groupHasOnlyOneStone(GoBoard b, int sq) {
        int f = b.fileOf(sq), r = b.rankOf(sq);
        int[] adj = adjacent(b, sq);
        for (int a : adj) {
            byte pc = b.squares[a];
            if (pc == b.squares[sq]) return false;
        }
        return true;
    }

    /**
     * Score for the game after both players passed. Returns black's score
     * minus white's; a positive result means black leads. The GUI flips
     * the sign for display.
     *
     * <p>Chinese area scoring: each player's score = stones on the board +
     * territory (empty intersections they completely surround). White
     * receives {@link #KOMI} as a base offset.
     */
    public double scoreDelta(GoBoard b) {
        int blackStones = 0, whiteStones = 0;
        int blackTerritory = 0, whiteTerritory = 0;
        boolean[] visited = new boolean[b.size * b.size];
        for (int sq = 0; sq < b.size * b.size; sq++) {
            byte pc = b.squares[sq];
            if (pc == GoBoard.BLACK) blackStones++;
            else if (pc == GoBoard.WHITE) whiteStones++;
            else if (!visited[sq]) {
                // Flood-fill empty region: who surrounds it?
                int[] region = new int[b.size * b.size];
                int rp = 0;
                boolean touchesBlack = false, touchesWhite = false;
                int[] stack = new int[b.size * b.size];
                int sp = 0;
                stack[sp++] = sq;
                visited[sq] = true;
                region[rp++] = sq;
                while (sp > 0) {
                    int cur = stack[--sp];
                    int[] adj = adjacent(b, cur);
                    for (int a : adj) {
                        byte n = b.squares[a];
                        if (n == GoBoard.EMPTY && !visited[a]) {
                            visited[a] = true;
                            stack[sp++] = a;
                            region[rp++] = a;
                        } else if (n == GoBoard.BLACK) touchesBlack = true;
                        else if (n == GoBoard.WHITE) touchesWhite = true;
                    }
                }
                if (touchesBlack && !touchesWhite) blackTerritory += rp;
                else if (touchesWhite && !touchesBlack) whiteTerritory += rp;
                // neutral regions (touches both, or neither) score nothing.
            }
        }
        double blackScore = blackStones + blackTerritory;
        double whiteScore = whiteStones + whiteTerritory + KOMI;
        return blackScore - whiteScore;
    }
}
