package com.qisheng.chess.client;

import com.qisheng.chess.tileentity.ModBlockEntities;
import net.fabricmc.fabric.api.client.rendereregistry.v1.BlockEntityRendererRegistry;

/**
 * Platform twin of {@code com.qisheng.chess.client.ModClient} on Fabric.
 * Loom's source-set merge picks this up at build time and folds it into
 * the common class — so callers see a single
 * {@code ModClient.registerBlockEntityRenderers()} that runs this
 * Fabric-specific body at runtime.
 */
public final class ModClient {
    private ModClient() {}

    public static void registerBlockEntityRenderers() {
        BlockEntityRendererRegistry.INSTANCE.register(
                ModBlockEntities.CCHESS.get(), CChessBoardBER::new);
    }
}