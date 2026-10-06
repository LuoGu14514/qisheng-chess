package com.qisheng.chess.fabric.client;

import com.qisheng.chess.QishengChess;
import com.qisheng.chess.fabric.FabricClientNetworkBridge;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fabric 侧的客户端入口,由 {@code fabric.mod.json} 的 {@code entrypoints.client}
 * 指向本全限定名来挂载。
 *
 * <p>{@link #onInitializeClient()} 调用 {@link FabricClientNetworkBridge#initClient()}
 * 把 S2C channel 挂到 Fabric API 的 ClientPlayNetworking。棋盘 GUI 由 common 源集里的
 * 客户端类自行实现,这边没有需要注册的 BlockEntityRenderer。
 */
@Environment(EnvType.CLIENT)
public class QishengChessFabricClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger(QishengChess.MOD_ID + "/client");

    @Override
    public void onInitializeClient() {
        // 与服务器端对应:ClientPlayNetworking 走 Fabric API,与 Architectury 类路径无关
        FabricClientNetworkBridge.initClient();
        LOGGER.info("[" + QishengChess.MOD_ID + "] Fabric client init");
    }
}