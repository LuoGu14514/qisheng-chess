package com.qisheng.chess.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * Wire codec for the legal-destinations bitmap shipped with
 * {@link ChessSyncS2CPacket} and {@link ChessOpenScreenS2CPacket}.
 *
 * <p>The bitmap length is variant-dependent: xiangqi uses 256 squares (32 wire
 * bytes), international chess uses 64 (8 wire bytes). Booleans bit-pack LSB-
 * first within each byte (bit 0 = index 0 of that byte, bit 7 = index 7).
 * Indices above {@code totalSquares} are silently truncated on read and must not
 * be set on write.
 *
 * <p>The server only ships the bitmap when {@code selectedPoint} holds a piece
 * of the side to move; in every other case (no selection, opponent's piece,
 * finished game) the field is omitted and the client falls back to its local
 * cached compute.
 */
public final class LegalDestsBitmap {

    /** Total cells in xiangqi's {@code Position#squares} array (kept for tests). */
    public static final int XIANGQI_SIZE = 256;
    /** Bytes on the wire for a xiangqi bitmap: {@code XIANGQI_SIZE / 8}. */
    public static final int XIANGQI_WIRE_SIZE = XIANGQI_SIZE / 8;

    private LegalDestsBitmap() {}

    /** Compute {@code ceil(totalSquares / 8)} bytes that the bitmap will occupy on the wire. */
    public static int wireSize(int totalSquares) {
        return (totalSquares + 7) / 8;
    }

    /**
     * Encode {@code dests} into wire bytes, in-place on {@code buf}. The number
     * of bits actually written is {@code totalSquares}; trailing bits in the
     * last partial byte are zero. {@code dests.length} must be ≥ {@code totalSquares}.
     */
    public static void write(FriendlyByteBuf buf, boolean[] dests, int totalSquares) {
        if (dests == null) throw new IllegalArgumentException("dests must not be null");
        if (totalSquares < 0 || totalSquares > dests.length) {
            throw new IllegalArgumentException("totalSquares out of range: " + totalSquares);
        }
        int bytes = wireSize(totalSquares);
        for (int byteIdx = 0; byteIdx < bytes; byteIdx++) {
            int b = 0;
            int base = byteIdx * 8;
            int bitsThisByte = Math.min(8, totalSquares - base);
            for (int bit = 0; bit < bitsThisByte; bit++) {
                if (dests[base + bit]) b |= (1 << bit);
            }
            buf.writeByte(b);
        }
    }

    /**
     * Convenience overload for xiangqi's 256-square bitmap. Throws if
     * {@code dests.length} is not exactly 256.
     */
    public static void write(FriendlyByteBuf buf, boolean[] dests) {
        if (dests == null) throw new IllegalArgumentException("dests must not be null");
        if (dests.length != XIANGQI_SIZE) {
            throw new IllegalArgumentException("dests.length must be " + XIANGQI_SIZE + ", got " + dests.length);
        }
        write(buf, dests, XIANGQI_SIZE);
    }

    /**
     * Decode {@code totalSquares} bits (packed into {@code wireSize(totalSquares)}
     * bytes) from {@code buf} into a freshly-allocated {@code boolean[totalSquares]}.
     */
    public static boolean[] read(FriendlyByteBuf buf, int totalSquares) {
        if (totalSquares < 0) {
            throw new IllegalArgumentException("totalSquares must be non-negative: " + totalSquares);
        }
        boolean[] dests = new boolean[totalSquares];
        int bytes = wireSize(totalSquares);
        for (int byteIdx = 0; byteIdx < bytes; byteIdx++) {
            int b = buf.readByte() & 0xFF;
            int base = byteIdx * 8;
            int bitsThisByte = Math.min(8, totalSquares - base);
            for (int bit = 0; bit < bitsThisByte; bit++) {
                dests[base + bit] = (b & (1 << bit)) != 0;
            }
        }
        return dests;
    }

    /**
     * Convenience overload for xiangqi's 256-square bitmap.
     */
    public static boolean[] read(FriendlyByteBuf buf) {
        return read(buf, XIANGQI_SIZE);
    }
}