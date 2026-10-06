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
 * <p>本 main entrypoint 还会在两端都尝试通过反射调用两端的 init 方法。Fabric 的
 * {@code server} entrypoint 在 <b>singleplayer (集成服)</b> 模式下不会触发,因为
 * 集成服属于 env=CLIENT;若不在 main 这里也调用 {@code initServer()},用户点击棋盘
 * 时 {@code ModNetwork.sendToPlayer} 会抛 {@code IllegalStateException}("serverSender
 * not yet initialized")、被 silent-catch 吞掉,客户端只看到 "已加入红方" popup,
 * 但 GUI 永远不开。两端 init 方法都已做幂等处理,多次调用安全。
 */
public class QishengChessFabric implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger(QishengChess.MOD_ID);

    @Override
    public void onInitialize() {
        ModRegistry.init();
        FabricEvents.register();
        tryInvoke("com.qisheng.chess.fabric.FabricServerNetworkBridge", "initServer");
        tryInvoke("com.qisheng.chess.fabric.FabricClientNetworkBridge", "initClient");
        LOGGER.info("[" + QishengChess.MOD_ID + "] Fabric mod initialized");
    }

    /**
     * 通过反射调用 env-specific 的 init 方法。失败时只记录 warning,绝不抛出:
     * 在 dedicated server 上 {@code FabricClientNetworkBridge} 类因
     * {@link net.fabricmc.api.Environment EnvType.CLIENT} 标注被剥离,
     * Class.forName 会抛 {@code ClassNotFoundException},这是预期行为。
     */
    private static void tryInvoke(String className, String methodName) {
        try {
            Class.forName(className).getMethod(methodName).invoke(null);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            // 在错侧 env 类被剥离时正常发生;无需打扰用户。
            LOGGER.debug("[{}] skipped {} (env-stripped): {}", QishengChess.MOD_ID, methodName, e.toString());
        } catch (Throwable t) {
            LOGGER.warn("[{}] failed to call {}.{} via reflection", QishengChess.MOD_ID, className, methodName, t);
        }
    }
}