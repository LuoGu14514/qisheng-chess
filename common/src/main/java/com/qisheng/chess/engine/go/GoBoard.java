package com.qisheng.chess.engine.go;

import com.qisheng.chess.engine.BoardState;

/**
 * Mutable Go board for the {@code go9} and {@code go19} variants.
 *
 * <p><b>Indexing.</b> Square {@code file + rank*size}: a1 = 0, last-file
 * last-rank = size*size - 1. The size is fixed at construction so the
 * board matches its variant; mixing sizes would corrupt indexing.
 *
 * <p><b>Stone bytes.</b> {@code 0} = empty, {@code 1} = black, {@code 2} =
 * white. Same convention as {@link com.qisheng.chess.engine.gomoku.GomokuBoard}
 * — Go has no "base type".
 *
 * <p><b>Turn.</b> {@code sdPlayer} 0 = black, 1 = white. Black plays first
 * per Chinese rules; the same convention Go engines use worldwide.
 *
 * <p><b>Capture counters.</b> {@code blackCaptures} counts how many white
 * stones black has removed (one per removed stone); same for
 * {@code whiteCaptures}. Stored at game-over time so the GUI can show
 * the score without recomputing.
 *
 * <p><b>Ko.</b> {@code koSquare} is the single intersection the previous
 * move set up as a possible ko recapture point, or {@code -1} if none. A
 * move that would place a stone such that the board repeats the position
 * one ply earlier is rejected by {@code GoVariant.canMove}.
 *
 * <p><b>Pass.</b> {@code passes} is the count of consecutive passes ending
 * the game. When it reaches 2 the game is over; the GUI switches into a
 * finished state and shows the score.
 */
public final class GoBoard implements BoardState {

    public static final byte EMPTY = 0;
    public static final byte BLACK = 1;
    public static final byte WHITE = 2;

    public final int size;
    public byte[] squares;
    public int sdPlayer = 0;          // 0 = black, 1 = white
    public int blackCaptures = 0;
    public int whiteCaptures = 0;
    public int koSquare = -1;
    public int passes = 0;
    public boolean finished = false;

    public GoBoard(int size) {
        this.size = size;
        this.squares = new byte[size * size];
    }

    public int sq(int file, int rank) {
        return file + rank * size;
    }

    public int fileOf(int sq) {
        return sq % size;
    }

    public int rankOf(int sq) {
        return sq / size;
    }
}
