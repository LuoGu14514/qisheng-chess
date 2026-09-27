package com.qisheng.chess.client;

import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side cache of board snapshots, one entry per board block in this
 * dimension. Populated by {@code ChessSyncS2CPacket} (server pushes FEN +
 * state on every move), consumed by {@link CChessBoardBER} (which needs the
 * current piece layout to redraw the top-face texture every frame).
 *
 * <p>Why a cache rather than reading the FEN off the screen? Because the
 * BER also runs when no player has the GUI open — and even when no one is
 * near the board. There is no Screen to query, so the only persistent
 * client-side source of "what does the board look like right now" is this
 * map.
 *
 * <p>Threading: writes happen on the render thread (we marshal
 * {@code put()} through {@code Minecraft.execute()} at the packet layer);
 * reads happen on the render thread inside the BER. ConcurrentHashMap is
 * overkill but cheap and avoids any visibility surprises.
 */
public final class BoardSurfaceCache {
    private static final Map<BlockPos, BoardSnapshot> SNAPSHOTS = new ConcurrentHashMap<>();

    private BoardSurfaceCache() {}

    public static void put(BlockPos pos, String fen, int sdPlayer, int stateOrd, int selectPoint) {
        SNAPSHOTS.put(pos, new BoardSnapshot(
                pos.immutable(),
                fen,
                sdPlayer,
                stateOrd,
                selectPoint,
                System.currentTimeMillis()
        ));
    }

    /** Drop the entry when a board is broken. Optional — stale entries
     *  are cheap to keep and harmless (the BER just won't find them). */
    public static void remove(BlockPos pos) {
        SNAPSHOTS.remove(pos);
    }

    public static BoardSnapshot get(BlockPos pos) {
        return SNAPSHOTS.get(pos);
    }

    /** For tests / hot-reload. */
    public static void clear() {
        SNAPSHOTS.clear();
    }
}