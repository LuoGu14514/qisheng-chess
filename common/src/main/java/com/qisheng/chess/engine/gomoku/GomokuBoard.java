package com.qisheng.chess.engine.gomoku;

import com.qisheng.chess.engine.BoardState;

/**
 * Mutable 15x15 Gomoku board.
 *
 * <p><b>Indexing.</b> Square {@code file + rank*15}: a1 = 0, o1 = 14,
 * a2 = 15, …, o15 = 224. Files run 0..14 from a to o, ranks run 0..14.
 *
 * <p><b>Stone bytes.</b> {@code 0} = empty, {@code 1} = black, {@code 2} = white.
 * The byte layout deliberately does not match xiangqi / chess: this variant
 * has no concept of "base type", and forcing the {@code base + sideTag}
 * encoding would have left three of the eight base slots wasted.
 *
 * <p><b>Turn.</b> {@code sdPlayer} 0 = black, 1 = white. Black plays first
 * per the standard Gomoku rule.
 *
 * <p><b>Game over.</b> Set by {@link #winner}: {@code 0} = no winner yet,
 * {@code 1} = black wins, {@code 2} = white wins. {@code moveCount} tracks
 * how many stones have been placed; a draw is when {@code moveCount} reaches
 * 225 (full board) without anyone winning.
 */
public final class GomokuBoard implements BoardState {

    /** 15x15 board, file a..o, rank 1..15. */
    public static final int SIZE = 15;

    public static final byte EMPTY  = 0;
    public static final byte BLACK  = 1;
    public static final byte WHITE  = 2;

    public byte[] squares = new byte[SIZE * SIZE];
    public int sdPlayer = 0;          // 0 = black, 1 = white
    public int moveCount = 0;
    public byte winner = EMPTY;       // 0 = ongoing, 1 = black, 2 = white

    public GomokuBoard() {}

    public static int sq(int file, int rank) {
        return file + rank * SIZE;
    }

    public static int fileOf(int sq) {
        return sq % SIZE;
    }

    public static int rankOf(int sq) {
        return sq / SIZE;
    }
}
