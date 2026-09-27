package com.qisheng.chess.client;

import dev.architectury.injectables.annotations.ExpectPlatform;

/**
 * Cross-platform client-side entry point. Common-side stub; each loader
 * subproject provides a twin {@code ModClient} class (same FQN, picked up
 * by Loom's source-set merge) whose {@code registerBlockEntityRenderers}
 * has the matching signature. At runtime the platform twin's body wins —
 * the {@code @ExpectPlatform} stub below is rewritten by Architectury's
 * plugin so the call dispatches to the right implementation.
 *
 * <p>The fabric entry-point ({@code QishengChessFabricClient}) calls
 * {@link #registerBlockEntityRenderers()}; on NeoForge, the same call is
 * made from {@code QishengChessNeoForge} but the actual registration
 * happens via the {@code RegisterRenderersEvent} listener (the platform
 * twin here is a no-op).
 */
public final class ModClient {
    private ModClient() {}

    @ExpectPlatform
    public static void registerBlockEntityRenderers() {
        // Never runs; Architectury's plugin rewrites this to call the
        // platform twin at build time. If you ever see this throw, the
        // platform twin is missing or has the wrong signature.
        throw new AssertionError("ModClient.registerBlockEntityRenderers() not implemented for this platform");
    }
}