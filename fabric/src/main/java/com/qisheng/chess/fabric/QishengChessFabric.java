package com.qisheng.chess.fabric;

import com.qisheng.chess.ModRegistry;
import com.qisheng.chess.QishengChess;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QishengChessFabric implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger(QishengChess.MOD_ID);

    @Override
    public void onInitialize() {
        ModRegistry.init();
        FabricEvents.register();
        LOGGER.info("[" + QishengChess.MOD_ID + "] Fabric mod initialized");
    }
}