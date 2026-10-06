package com.qisheng.chess.fabric;

import com.qisheng.chess.ModRegistry;
import com.qisheng.chess.QishengChess;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fabric 侧的"main" entrypoint,在 client + server 两侧都会加载。
 *
 * <p>本类刻意保持极简,只做 <b>env-无关</b> 的注册:任何对
 * {@code FabricServerNetworkBridge} / {@code FabricClientNetworkBridge} 的引用
 * 都会让"main"在错侧加载时触发 NoClassDefFoundError (因为这两个类分别带
 * {@link net.fabricmc.api.Environment EnvType.SERVER} / {@code CLIENT} 标注,
 * 会在错侧被 Fabric Loader 剥离)。Server / client 各自有独立的 entrypoint:
 * <ul>
 *   <li>{@code QishengChessFabricServer.onInitializeServer} —
 *       仅 server 加载,调用 {@link FabricServerNetworkBridge#initServer()}</li>
 *   <li>{@code QishengChessFabricClient.onInitializeClient} —
 *       仅 client 加载,调用 {@link FabricClientNetworkBridge#initClient()}</li>
 * </ul>
 */
public class QishengChessFabric implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger(QishengChess.MOD_ID);

    @Override
    public void onInitialize() {
        ModRegistry.init();
        FabricEvents.register();
        LOGGER.info("[" + QishengChess.MOD_ID + "] Fabric mod initialized");
    }
}