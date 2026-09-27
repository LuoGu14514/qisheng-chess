package com.qisheng.chess.fabric.client;

import com.qisheng.chess.QishengChess;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fabric-side client entry. Hooked by {@code fabric.mod.json}
 * ({@code "client": ["com.qisheng.chess.fabric.client.QishengChessFabricClient"]}).
 * Calls into the cross-platform {@code ModClient.registerBlockEntityRenderers()}
 * which Architectury routes to the Fabric implementation in
 * {@code fabric/.../client/ModClient.java}.
 */
@Environment(EnvType.CLIENT)
public class QishengChessFabricClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger(QishengChess.MOD_ID + "/client");

    @Override
    public void onInitializeClient() {
        LOGGER.info("[" + QishengChess.MOD_ID + "] Fabric client init");
    }
}