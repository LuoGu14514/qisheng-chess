package com.qisheng.chess.fabric.client;

import com.qisheng.chess.QishengChess;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fabric 侧的客户端入口,由 {@code fabric.mod.json} 的 {@code entrypoints.client}
 * 指向本全限定名来挂载。
 *
 * <p>{@link #onInitializeClient()} 目前只打一行日志:棋盘 GUI 由 common 源集里的
 * 客户端类自行实现,这边没有需要注册的 BlockEntityRenderer。旧版 javadoc 声称
 * 这里会调用 {@code ModClient.registerBlockEntityRenderers()} —— 该方法并不存在
 * (两个空的 {@code ModClient} 壳类已一并删除)。
 */
@Environment(EnvType.CLIENT)
public class QishengChessFabricClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger(QishengChess.MOD_ID + "/client");

    @Override
    public void onInitializeClient() {
        LOGGER.info("[" + QishengChess.MOD_ID + "] Fabric client init");
    }
}