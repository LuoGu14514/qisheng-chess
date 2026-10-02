package com.qisheng.chess.engine;

/**
 * A single move: source square → destination square, in variant-local square
 * indices (xiangqi's 0..255 {@code Position} coordinate, or international
 * chess's 0..63 a1-anchored index).
 *
 * <p>Carried as a value object so a computer search can hand back a result
 * without committing to any particular bit-packed encoding; the variant
 * does the encoding only where the wire or engine demands it.
 */
public record Move(int src, int dst) {

    /** A null/skip move — never legal, used to signal "no move found". */
    public static final Move NONE = new Move(-1, -1);

    public boolean isLegal() {
        return src >= 0 && dst >= 0 && src != dst;
    }
}