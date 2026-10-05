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
        // Fabric API 直接注册 C2S packet receivers(不走 Architectury NetworkManager,
        // 避免其他 mod shade 旧版 ARK 引起的 NoSuchMethodError on server start)。
        FabricNetworkBridge.initServer();
        FabricEvents.register();
        LOGGER.info("[" + QishengChess.MOD_ID + "] Fabric mod initialized");
    }
}