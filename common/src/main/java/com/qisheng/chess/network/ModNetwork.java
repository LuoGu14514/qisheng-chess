package com.qisheng.chess.network;

import com.qisheng.chess.QishengChess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * 网络包注册中心 + 跨源集(Fabric ↔ common)的发包桥接点
 *
 * <p><b>两层结构:</b>
 * <ul>
 *   <li><b>本类(本文件)位于 {@code common/}</b>——只声明 16 个 {@link ResourceLocation}
 *       channel 常量 + 两个由 fabric entrypoint 注入实现的桥接 (server / client 分开)。
 *       这样 common 端的 GameBroadcaster / CChessBoardScreen 才能从 fabric 端调用真正的发包 API。</li>
 *   <li><b>{@code fabric/.../fabric/FabricServerNetworkBridge.java}</b> 与
 *       <b>{@code FabricClientNetworkBridge.java}</b>——各持一份 {@link FabricSender}
 *       的 Fabric 实现,分别调用 {@code ServerPlayNetworking.send} /
 *       {@code ClientPlayNetworking.send},并各自挂载到对应的 Fabric API entrypoint。
 *       两个类各带 {@link net.fabricmc.api.Environment} 标注,只在自己侧加载,
 *       避免任何"main entrypoint 跨 env 引用"造成的 NoClassDefFoundError。</li>
 * </ul>
 *
 * <p><b>v0.4.7 起改用 Fabric API 直接注册,不再走 Architectury 的
 * {@code NetworkManager}。</b>原因见服务器侧报错诊断:服务器
 * (286 mods 之中) 某个 mod shade 了旧版 Architectury,导致
 * {@code NetworkManagerImpl.registerS2CReceiver} 签名错位,任何
 * {@code NetworkManager.registerReceiver(...)} 都会触发 {@code NoSuchMethodError}
 * 服务器启动失败。Fabric API 的 {@code registerGlobalReceiver} 走 fabric 自带
 * CustomPayload 注册,与 ARK 类路径完全无关。
 *
 * <p><b>v0.4.7 进一步把 server / client 注入拆成两个 holder</b>,因为
 * 在集成服(单人游戏)里 {@code server} 和 {@code client} 两个 entrypoint
 * 都会跑,需要一个 holder 让 {@code ModNetwork.sendToPlayer} 走 server 实现、
 * 另一个 holder 让 {@code ModNetwork.sendToServer} 走 client 实现。
 *
 * <p>包清单 (16 条):
 * <ul>
 *   <li>CHESS_INTERACT      (C2S)  玩家在 GUI 里点选 / 走子
 *   <li>CHESS_SYNC          (S2C)  服务端广播棋盘状态
 *   <li>CHESS_OPEN_SCREEN   (S2C)  服务端告诉客户端打开棋盘 GUI
 *   <li>CHESS_POPUP         (S2C)  中文提示通过 in-GUI 弹窗显示
 *   <li>CHESS_PLAYER_INFO   (S2C)  红/黑 + 旁观者名单 + 各自 name
 *   <li>CHESS_DRAW_REQUEST  (C2S)  发起求和申请
 *   <li>CHESS_DRAW_INVITE   (S2C)  通知对方有人求和
 *   <li>CHESS_DRAW_RESPONSE (C2S)  接受/拒绝求和
 *   <li>CHESS_DRAW_RESULT   (S2C)  双方告知求和结果
 *   <li>CHESS_RESIGN        (C2S)  认输
 *   <li>CHESS_SWITCH_REQUEST  (C2S)  发起切换身份(申请方填 targetId)
 *   <li>CHESS_SWITCH_INVITE   (S2C)  通知对方有人想换
 *   <li>CHESS_SWITCH_RESPONSE (C2S)  接受/拒绝
 *   <li>CHESS_SWITCH_RESULT   (S2C)  双方告知切换结果
 *   <li>CHESS_CHAT          (C2S + S2C)  局内聊天
 * </ul>
 */
public final class ModNetwork {

    public static final ResourceLocation CHESS_INTERACT =
            new ResourceLocation(QishengChess.MOD_ID, "chess_interact");
    public static final ResourceLocation CHESS_SYNC =
            new ResourceLocation(QishengChess.MOD_ID, "chess_sync");
    public static final ResourceLocation CHESS_OPEN_SCREEN =
            new ResourceLocation(QishengChess.MOD_ID, "chess_open_screen");
    public static final ResourceLocation CHESS_POPUP =
            new ResourceLocation(QishengChess.MOD_ID, "chess_popup");
    public static final ResourceLocation CHESS_PLAYER_INFO =
            new ResourceLocation(QishengChess.MOD_ID, "chess_player_info");
    public static final ResourceLocation CHESS_DRAW_REQUEST =
            new ResourceLocation(QishengChess.MOD_ID, "chess_draw_request");
    public static final ResourceLocation CHESS_DRAW_INVITE =
            new ResourceLocation(QishengChess.MOD_ID, "chess_draw_invite");
    public static final ResourceLocation CHESS_DRAW_RESPONSE =
            new ResourceLocation(QishengChess.MOD_ID, "chess_draw_response");
    public static final ResourceLocation CHESS_DRAW_RESULT =
            new ResourceLocation(QishengChess.MOD_ID, "chess_draw_result");
    public static final ResourceLocation CHESS_RESIGN =
            new ResourceLocation(QishengChess.MOD_ID, "chess_resign");
    public static final ResourceLocation CHESS_SWITCH_REQUEST =
            new ResourceLocation(QishengChess.MOD_ID, "chess_switch_request");
    public static final ResourceLocation CHESS_SWITCH_INVITE =
            new ResourceLocation(QishengChess.MOD_ID, "chess_switch_invite");
    public static final ResourceLocation CHESS_SWITCH_RESPONSE =
            new ResourceLocation(QishengChess.MOD_ID, "chess_switch_response");
    public static final ResourceLocation CHESS_SWITCH_RESULT =
            new ResourceLocation(QishengChess.MOD_ID, "chess_switch_result");
    public static final ResourceLocation CHESS_CHAT =
            new ResourceLocation(QishengChess.MOD_ID, "chess_chat");

    private ModNetwork() {}

    /**
     * Fabric 端的发包实现接口。Fabric entrypoint 在初始化时调用
     * {@link #setServerSender(FabricSender)} / {@link #setClientSender(FabricSender)}
     * 各自注入实现;common 端的 GameBroadcaster / CChessBoardScreen 通过
     * {@link #sendToPlayer(ServerPlayer, ResourceLocation, FriendlyByteBuf)} 与
     * {@link #sendToServer(ResourceLocation, FriendlyByteBuf)} 间接调用。
     *
     * <p>每个 {@link FabricSender} 实现类带 {@link net.fabricmc.api.Environment}
     * 标注,只在自己侧加载,集成服(单人)里两侧 holder 都会被设置。
     */
    public interface FabricSender {
        void sendToPlayer(ServerPlayer player, ResourceLocation channel, FriendlyByteBuf buf);
        void sendToServer(ResourceLocation channel, FriendlyByteBuf buf);
    }

    /** Throwing placeholder, replaced by FabricServerNetworkBridge.initServer(). */
    private static volatile FabricSender serverSender = new FabricSender() {
        @Override
        public void sendToPlayer(ServerPlayer player, ResourceLocation channel, FriendlyByteBuf buf) {
            throw new IllegalStateException(
                    "FabricSender (server side) not yet initialized; fabric server entrypoint must call"
                            + " ModNetwork.setServerSender(...) before any networking. channel=" + channel);
        }
        @Override
        public void sendToServer(ResourceLocation channel, FriendlyByteBuf buf) {
            // serverSender 上的 sendToServer 不会被调用 (common 端只走 clientSender)
            throw new UnsupportedOperationException(
                    "server-side FabricSender does not implement sendToServer. channel=" + channel);
        }
    };

    /** Throwing placeholder, replaced by FabricClientNetworkBridge.initClient(). */
    private static volatile FabricSender clientSender = new FabricSender() {
        @Override
        public void sendToPlayer(ServerPlayer player, ResourceLocation channel, FriendlyByteBuf buf) {
            // clientSender 上的 sendToPlayer 不会被调用 (common 端只走 serverSender)
            throw new UnsupportedOperationException(
                    "client-side FabricSender does not implement sendToPlayer. channel=" + channel);
        }
        @Override
        public void sendToServer(ResourceLocation channel, FriendlyByteBuf buf) {
            throw new IllegalStateException(
                    "FabricSender (client side) not yet initialized; fabric client entrypoint must call"
                            + " ModNetwork.setClientSender(...) before any networking. channel=" + channel);
        }
    };

    /** Called by {@code QishengChessFabricServer.onInitializeServer}. */
    public static void setServerSender(FabricSender impl) {
        serverSender = impl;
    }

    /** Called by {@code QishengChessFabricClient.onInitializeClient}. */
    public static void setClientSender(FabricSender impl) {
        clientSender = impl;
    }

    public static void sendToPlayer(ServerPlayer player, ResourceLocation channel, FriendlyByteBuf buf) {
        FabricSender s = serverSender;
        if (s == null) throw new IllegalStateException("server-side FabricSender not initialized");
        s.sendToPlayer(player, channel, buf);
    }

    public static void sendToServer(ResourceLocation channel, FriendlyByteBuf buf) {
        FabricSender s = clientSender;
        if (s == null) throw new IllegalStateException("client-side FabricSender not initialized");
        s.sendToServer(channel, buf);
    }
}