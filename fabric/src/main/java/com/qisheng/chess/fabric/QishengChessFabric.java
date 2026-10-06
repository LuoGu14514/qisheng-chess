package com.qisheng.chess.fabric;

import com.qisheng.chess.ModRegistry;
import com.qisheng.chess.QishengChess;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fabric 侧的"main" entrypoint,在 client + server 两侧都会加载。
 *
 * <p>本类刻意保持极简,只做 <b>env-无关</b> 的注册;server / client 各自有
 * 独立的 entrypoint:
 * <ul>
 *   <li>{@code QishengChessFabricServer.onInitializeServer} —
 *       仅 dedicated server 加载,调用 {@link FabricServerNetworkBridge#initServer()}</li>
 *   <li>{@code QishengChessFabricClient.onInitializeClient} —
 *       仅 client 加载,调用 {@link FabricClientNetworkBridge#initClient()}</li>
 * </ul>
 *
 * <p>v0.4.11 起,{@link FabricServerNetworkBridge} 已去除
 * {@code @Environment(EnvType.SERVER)} 注解(否则 Fabric Loader 的 Knot classloader
 * 会在 singleplayer (env=CLIENT) 上 STRIP 该类,导致 initServer 永远不被调用,
 * GUI 不开)。本 main entrypoint 现在直接调用 {@code initServer()};其内部的 env gate
 * 会跳过纯 multiplayer CLIENT 情况(无 SERVER 类在 classpath)。两端 init 方法都做幂等处理。
 *
 * <p>{@link FabricClientNetworkBridge} 仍然带 {@code @Environment(EnvType.CLIENT)},
 * 因此 dedicated server (env=SERVER) 上该类被 Knot 剥离,直接调用会失败,继续用反射。
 */
public class QishengChessFabric implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger(QishengChess.MOD_ID);

    @Override
    public void onInitialize() {
        ModRegistry.init();
        FabricEvents.register();
        // Server init: direct call now works on dedicated + singleplayer.
        // The env gate inside initServer() bails out on pure multiplayer CLIENT.
        FabricServerNetworkBridge.initServer();
        // Client init: must use reflection because on dedicated server the CLIENT
        // bridge class is still @EnvType(CLIENT) and stripped by Knot.
        tryInvoke("com.qisheng.chess.fabric.FabricClientNetworkBridge", "initClient");
        LOGGER.info("[" + QishengChess.MOD_ID + "] Fabric mod initialized");
    }

    /**
     * 通过反射调用 env-specific 的 init 方法。失败时记录 warning 以便诊断:
     * 在 dedicated server (env=SERVER) 上 {@code FabricClientNetworkBridge} 类因
     * {@link net.fabricmc.api.Environment EnvType.CLIENT} 标注被剥离,
     * Class.forName 会抛 {@code RuntimeException("Cannot load class ...")},
     * 这是预期行为 — dedicated server 没有客户端。
     */
    private static void tryInvoke(String className, String methodName) {
        try {
            Class<?> cls = Class.forName(className);
            LOGGER.info("[{}] resolved {} -> {}", QishengChess.MOD_ID, className, cls.getName());
            cls.getMethod(methodName).invoke(null);
            LOGGER.info("[{}] invoked {}.{} OK", QishengChess.MOD_ID, className, methodName);
        } catch (Throwable t) {
            // On dedicated server, FabricClientNetworkBridge is env-stripped; we just log info-level.
            // On singleplayer CLIENT, FabricClientNetworkBridge IS loadable; initClient() runs.
            LOGGER.info("[{}] skipped {}.{}: {}", QishengChess.MOD_ID, className, methodName, t.toString());
        }
    }
}