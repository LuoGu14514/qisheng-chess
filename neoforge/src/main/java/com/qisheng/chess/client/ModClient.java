package com.qisheng.chess.client;

/**
 * Platform twin of {@code com.qisheng.chess.client.ModClient} on NeoForge.
 * Loom's source-set merge picks this up at build time. The actual BER
 * registration is done via the {@code RegisterRenderersEvent} listener in
 * {@code QishengChessNeoForge}, so this is a no-op stub.
 */
public final class ModClient {
    private ModClient() {}

    public static void registerBlockEntityRenderers() {
        // NeoForge: actual registration is in QishengChessNeoForge.onRegisterRenderers()
    }
}