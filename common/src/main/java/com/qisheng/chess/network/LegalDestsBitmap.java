package com.qisheng.chess.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * Wire codec for the 256-square legal-destinations bitmap shipped with
 * {@link ChessSyncS2CPacket} and {@link ChessOpenScreenS2CPacket}.
 *
 * <p>The bitmap is {@code boolean[256]}: index = {@code Position} internal
 * coordinate (0..255). 256 booleans bit-pack into 32 bytes, LSB-first within
 * each byte (bit 0 = index 0 of that byte, bit 7 = index 7).
 *
 * <p>The server only ships the bitmap when {@code selectedPoint} holds a piece
 * of the side to move; in every other case (no selection, opponent's piece,
 * finished game) the field is omitted and the client falls back to its local
 * cached compute.
 */
public final class LegalDestsBitmap {

    /** Total cells in the engine's {@code Position#squares} array. */
    public static final int SIZE = 256;
    /** Bytes on the wire = {@code SIZE / 8}. */
    public static final int WIRE_SIZE = SIZE / 8;

    private LegalDestsBitmap() {}

    /** Encode {@code dests} into 32 wire bytes, in-place on {@code buf}. */
    public static void write(FriendlyByteBuf buf, boolean[] dests) {
        if (dests == null) throw new IllegalArgumentException("dests must not be null");
        if (dests.length != SIZE) {
            throw new IllegalArgumentException("dests.length must be " + SIZE + ", got " + dests.length);
        }
        for (int byteIdx = 0; byteIdx < WIRE_SIZE; byteIdx++) {
            int b = 0;
            int base = byteIdx * 8;
            for (int bit = 0; bit < 8; bit++) {
                if (dests[base + bit]) b |= (1 << bit);
            }
            buf.writeByte(b);
        }
    }

    /** Decode 32 wire bytes from {@code buf} back into a {@code boolean[256]}. */
    public static boolean[] read(FriendlyByteBuf buf) {
        boolean[] dests = new boolean[SIZE];
        for (int byteIdx = 0; byteIdx < WIRE_SIZE; byteIdx++) {
            int b = buf.readByte() & 0xFF;
            int base = byteIdx * 8;
            for (int bit = 0; bit < 8; bit++) {
                dests[base + bit] = (b & (1 << bit)) != 0;
            }
        }
        return dests;
    }
}