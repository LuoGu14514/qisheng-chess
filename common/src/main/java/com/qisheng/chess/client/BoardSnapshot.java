package com.qisheng.chess.client;

import net.minecraft.core.BlockPos;

/**
 * Lightweight client-side snapshot of a chess session for one board block.
 *
 * <p>Stored in {@link BoardSurfaceCache} keyed by {@link BlockPos}, and
 * consumed by {@link CChessBoardBER} to render the live game state on the
 * top face of the chess block.
 *
 * <p>Only what the block-surface renderer needs (no need to mirror the
 * whole {@link com.qisheng.chess.pvp.GameSession} graph on the client):
 * <ul>
 *   <li>FEN — current piece layout</li>
 *   <li>{@code sdPlayer} — whose turn it is (for a small "red/black" tint)</li>
 *   <li>{@code stateOrd} — WAITING / PLAYING / FINISHED (drives a status glyph)</li>
 *   <li>{@code selectPoint} — the square currently being moved (yellow ring)</li>
 * </ul>
 *
 * Equality is value-based on the four gameplay fields; this lets the BER
 * skip uploading the texture when nothing has changed since the last frame.
 */
public record BoardSnapshot(
        BlockPos pos,
        String fen,
        int sdPlayer,
        int stateOrd,
        int selectPoint,
        long updatedAtMillis
) {
    /**
     * True iff anything that should trigger a redraw has changed.
     * {@code updatedAtMillis} is intentionally excluded so two snapshots
     * captured in the same frame compare equal.
     */
    public boolean sameContent(String otherFen, int otherSd, int otherState, int otherSel) {
        return fen.equals(otherFen)
                && sdPlayer == otherSd
                && stateOrd == otherState
                && selectPoint == otherSel;
    }
}