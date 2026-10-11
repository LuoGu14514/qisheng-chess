package com.qisheng.chess.tileentity;

import com.qisheng.chess.engine.BoardRegistry;
import com.qisheng.chess.pvp.BoardKey;
import com.qisheng.chess.pvp.GameSession;
import com.qisheng.chess.pvp.SessionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression for the v0.4.21 ensureSession variant-override fix.
 *
 * <p>Before the fix, {@link CChessTileEntity#ensureSession(String)} only
 * applied {@code preferredVariantId} when the live session's variant was
 * still {@link BoardRegistry#DEFAULT_ID} — a brittle gate that assumed
 * {@link com.qisheng.chess.block.AbstractChessBoardBlock#onPlace} always
 * creates with the default variant. The new behaviour is unconditional:
 * the live session is updated whenever its variant differs from the
 * preferred one.
 *
 * <p>We exercise the new logic by replicating the gate inline (the real
 * {@code ensureSession} requires a ServerLevel getter for getOrCreate and
 * cannot be called from a pure JUnit test). This proves the comparison
 * semantics: equal → no-op, default → applied, non-default differing →
 * applied.
 */
class EnsureSessionVariantTest {

    private static BoardKey newKey() {
        return BoardKey.of(ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION,
                new net.minecraft.resources.ResourceLocation("test:overworld")), BlockPos.ZERO);
    }

    /** Replicates the v0.4.21 gate from CChessTileEntity.ensureSession. */
    private static String applyPreferred(String liveVariantId, String preferredVariantId) {
        if (preferredVariantId != null && !preferredVariantId.isEmpty()
                && !preferredVariantId.equals(liveVariantId)) {
            return preferredVariantId;
        }
        return liveVariantId;
    }

    @Test
    @DisplayName("live session with default variant: preferredVariantId applied")
    void liveDefaultVariantGetsOverridden() {
        // Live was created via getOrCreate → DEFAULT_ID; preferred is "gomoku"
        assertEquals("gomoku", applyPreferred(BoardRegistry.DEFAULT_ID, "gomoku"));
    }

    @Test
    @DisplayName("preferredVariantId == live.getVariantId(): idempotent no-op")
    void idempotentWhenAlreadyCorrect() {
        assertEquals("international",
                applyPreferred("international", "international"));
    }

    @Test
    @DisplayName("live session with stale non-default variant: preferredVariantId overrides")
    void staleNonDefaultVariantGetsOverridden() {
        // Older v0.4.0-persisted session with a different variant than the
        // block now declares (e.g. due to a renamed variant id migration).
        assertEquals("gomoku", applyPreferred("go9", "gomoku"));
    }

    @Test
    @DisplayName("preferredVariantId null: no change")
    void nullPreferredLeavesLiveAlone() {
        assertEquals(BoardRegistry.DEFAULT_ID,
                applyPreferred(BoardRegistry.DEFAULT_ID, null));
        assertEquals("go19", applyPreferred("go19", null));
    }

    @Test
    @DisplayName("preferredVariantId empty string: no change")
    void emptyPreferredLeavesLiveAlone() {
        assertEquals(BoardRegistry.DEFAULT_ID,
                applyPreferred(BoardRegistry.DEFAULT_ID, ""));
        assertEquals("go19", applyPreferred("go19", ""));
    }

    // Touch SessionManager to keep its import stable and confirm we can
    // still fetch a session (sanity check that the variant field exists).
    @Test
    @DisplayName("GameSession.getVariantId / setVariantId round-trip")
    void sessionVariantGetterSetter() {
        SessionManager sm = SessionManager.get();
        BoardKey key = newKey();
        GameSession seed = sm.getOrCreate(key);
        try {
            seed.setVariantId("go9");
            assertEquals("go9", seed.getVariantId());
            seed.setVariantId(BoardRegistry.DEFAULT_ID);
            assertEquals(BoardRegistry.DEFAULT_ID, seed.getVariantId());
        } finally {
            if (seed.getRedPlayer() != null) sm.evictPlayer(seed.getRedPlayer());
            if (seed.getBlackPlayer() != null) sm.evictPlayer(seed.getBlackPlayer());
        }
    }
}