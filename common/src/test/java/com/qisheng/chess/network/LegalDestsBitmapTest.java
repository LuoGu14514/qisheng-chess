package com.qisheng.chess.network;

import com.qisheng.chess.engine.ChineseChessEngine;
import com.qisheng.chess.engine.xqwlight.Position;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static com.qisheng.chess.util.CChessUtil.INIT;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the 256-square legal-destinations bitmap codec.
 *
 * <p>The point is a round-trip: a server-side {@code boolean[256]} packed into
 * {@link LegalDestsBitmap#write(FriendlyByteBuf, boolean[])} must come back out
 * of {@link LegalDestsBitmap#read(FriendlyByteBuf)} byte-for-byte equal. Plus
 * the real-world case: computing the bitmap for a piece at the standard opening
 * must agree with {@link ChineseChessEngine#canMove}.
 */
class LegalDestsBitmapTest {

    /** Round-trip a fully-empty bitmap; read should give the same. */
    @Test
    @DisplayName("Round-trip an all-empty bitmap")
    void roundTripEmpty() {
        boolean[] in = new boolean[256];
        FriendlyByteBuf buf = buf();
        LegalDestsBitmap.write(buf, in);
        assertEquals(LegalDestsBitmap.XIANGQI_WIRE_SIZE, buf.readableBytes(),
                "wire size is 32 bytes for 256 bits");
        boolean[] read = LegalDestsBitmap.read(buf);
        assertArrayEquals(in, read);
    }

    /** Round-trip a fully-set bitmap. */
    @Test
    @DisplayName("Round-trip an all-set bitmap")
    void roundTripAllSet() {
        boolean[] in = new boolean[256];
        java.util.Arrays.fill(in, true);
        FriendlyByteBuf buf = buf();
        LegalDestsBitmap.write(buf, in);
        boolean[] read = LegalDestsBitmap.read(buf);
        assertArrayEquals(in, read);
    }

    /** Round-trip a random pattern — catches byte-alignment bugs the constants would miss. */
    @Test
    @DisplayName("Round-trip a random pattern (1000 iterations)")
    void roundTripRandom() {
        Random r = new Random(0xC4E55BFFL);
        for (int trial = 0; trial < 1000; trial++) {
            boolean[] in = new boolean[256];
            for (int i = 0; i < 256; i++) {
                in[i] = r.nextBoolean();
            }
            FriendlyByteBuf buf = buf();
            LegalDestsBitmap.write(buf, in);
            boolean[] read = LegalDestsBitmap.read(buf);
            assertArrayEquals(in, read,
                    "round-trip mismatch on iteration " + trial);
        }
    }

    /** Round-trip with a single-bit set at every byte boundary (8, 127, 128, 255). */
    @Test
    @DisplayName("Each individual square round-trips")
    void roundTripPerSquare() {
        for (int sq = 0; sq < 256; sq++) {
            boolean[] in = new boolean[256];
            in[sq] = true;
            FriendlyByteBuf buf = buf();
            LegalDestsBitmap.write(buf, in);
            boolean[] read = LegalDestsBitmap.read(buf);
            int ones = 0;
            for (int i = 0; i < 256; i++) if (read[i]) ones++;
            assertEquals(1, ones, "exactly one bit must survive for sq=" + sq);
            assertTrue(read[sq], "the sq=" + sq + " bit must survive");
        }
    }

    /** A real rook's destinations at the opening position: must match {@code canMove}. */
    @Test
    @DisplayName("Server-side bitmap for the opening red rook matches canMove")
    void rookOpeningMatchesCanMove() {
        Position pos = ChineseChessEngine.parseFen(INIT);
        assertTrue(pos != null, "INIT must parse");
        // Red to move, red rook at the bottom-left. The FEN walks y from
        // RANK_TOP (black's back rank) downward, so red's back rank lives at
        // RANK_BOTTOM. file=0 → x = FILE_LEFT.
        int rookSq = Position.COORD_XY(0 + Position.FILE_LEFT, Position.RANK_BOTTOM);
        // Sanity: there's a red-side piece there. We don't pin the type code —
        // the engine uses an internal mapping for the lower 3 bits, and asserting
        // it would couple the test to a constant we don't otherwise depend on.
        byte pc = pos.squares[rookSq];
        assertTrue(pc != 0, "the bottom-left of red's back rank must hold a piece");
        assertTrue((pc & Position.SIDE_TAG(0)) != 0, "the piece belongs to red (sd=0)");
        // Compute the destinations through the engine, pack, unpack, and compare.
        boolean[] in = new boolean[256];
        for (int dst = 0; dst < 256; dst++) {
            in[dst] = ChineseChessEngine.canMove(pos, rookSq, dst);
        }
        FriendlyByteBuf buf = buf();
        LegalDestsBitmap.write(buf, in);
        boolean[] read = LegalDestsBitmap.read(buf);
        assertArrayEquals(in, read);
    }

    /** Defensive: writer rejects null and the wrong-sized array. */
    @Test
    @DisplayName("Writer rejects null and out-of-shape input")
    void writerRejectsBadInput() {
        FriendlyByteBuf buf = buf();
        assertThrows(IllegalArgumentException.class, () -> LegalDestsBitmap.write(buf, null));
        assertThrows(IllegalArgumentException.class,
                () -> LegalDestsBitmap.write(buf, new boolean[100]));
    }

    private static FriendlyByteBuf buf() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }
}