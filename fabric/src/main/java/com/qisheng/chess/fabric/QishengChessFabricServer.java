package com.qisheng.chess.fabric;

import com.qisheng.chess.QishengChess;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fabric 侧的服务器入口,由 {@code fabric.mod.json} 的 {@code entrypoints.server}
 * 指向本全限定名来挂载。
 *
 * <p>只挂载到服务器(独立服 + 集成服内的服务端),不挂载到客户端。
 * 本类被 {@link Environment EnvType.SERVER} 标注,在客户端加载时整类被剥离,
 * 故可以安全地引用 {@link FabricServerNetworkBridge} (后者也带 SERVER 标注)。
 *
 * <p>调用 {@link FabricServerNetworkBridge#initServer()} 把 7 个 C2S channel
 * 挂到 Fabric API 的 {@code ServerPlayNetworking},并把
 * {@link FabricServerNetworkBridge#INSTANCE} 注入
 * {@link com.qisheng.chess.network.ModNetwork#setServerSender(FabricSender)},
 * 这样 common 端的 GameBroadcaster 才能从 fabric 端调用真正的发包 API。
 */
@Environment(EnvType.SERVER)
public class QishengChessFabricServer implements DedicatedServerModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger(QishengChess.MOD_ID + "/server");

    @Override
    public void onInitializeServer() {
        // 与客户端入口同版:ServerPlayNetworking 走 Fabric API,与 Architectury 类路径无关
        FabricServerNetworkBridge.initServer();
        LOGGER.info("[" + QishengChess.MOD_ID + "] Fabric server init");
    }
}