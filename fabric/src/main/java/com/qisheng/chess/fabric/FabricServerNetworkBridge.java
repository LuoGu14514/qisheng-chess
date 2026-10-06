package com.qisheng.chess.fabric;

import com.qisheng.chess.network.ChatPackets;
import com.qisheng.chess.network.ChessInteractC2SPacket;
import com.qisheng.chess.network.ChessResignC2SPacket;
import com.qisheng.chess.network.DrawPackets;
import com.qisheng.chess.network.ModNetwork;
import com.qisheng.chess.network.SwitchPackets;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import java.util.function.BiConsumer;

/**
 * Fabric SERVER 端网络包注册与发包实现。
 *
 * <p>本类 <b>故意不带 {@code @Environment(SERVER)} 注解</b>。原因:Fabric Loader
 * 的 Knot classloader 对任何带 {@code @Environment(SERVER)} 标注的 mod 类,在
 * CLIENT env (包括 singleplayer 的集成服,JVM env 仍是 CLIENT)上都会抛出
 * {@code RuntimeException("Cannot load class ...")},导致本类永远无法加载,
 * {@code initServer()} 从未被调用,用户点击棋盘时业务抛
 * {@code IllegalStateException("serverSender not yet initialized")} 被 silent-catch,
 * 客户端只看到 "已加入红方" popup 但 GUI 不开。
 *
 * <p>不带 {@code @Environment} 后,本类在两种 env 上都能加载:
 * <ul>
 *   <li>dedicated server (env=SERVER):所有 SERVER 类型都在 classpath</li>
 *   <li>singleplayer (env=CLIENT + 集成服在同 JVM):Loom 已把
 *       本类编译字节码中的 {@code ServerPlayer} 重映射为 {@code class_3222},
 *       后者在 client-intermediary.jar 中存在,所以能解析</li>
 * </ul>
 *
 * <p>ServerPlayNetworking 本身(以及内部类 PlayChannelHandler 等)在
 * fabric-networking-api-v1 jar 中不带 {@code @Environment} 标注,因此在两种
 * env 上都可加载 — 引用不需要绕开。</p>
 *
 * <p>本类只引用 SERVER-only 的 net.minecraft 类型,不引用
 * {@code net.minecraft.client.*} 或 {@code ClientPlayNetworking}。对应客户端
 * 实现见 {@link FabricClientNetworkBridge};common 端的 GameBroadcaster 经
 * {@link ModNetwork#sendToPlayer} 间接调用本类的 {@link #sendToPlayer}。
 */
public final class FabricServerNetworkBridge implements ModNetwork.FabricSender {
    public static final FabricServerNetworkBridge INSTANCE = new FabricServerNetworkBridge();

    private FabricServerNetworkBridge() {}

    @Override
    public void sendToPlayer(ServerPlayer player, ResourceLocation channel, FriendlyByteBuf buf) {
        if (player == null) return;
        ServerPlayNetworking.send(player, channel, buf);
    }

    @Override
    public void sendToServer(ResourceLocation channel, FriendlyByteBuf buf) {
        // 本类只在 SERVER 端加载,理论上不应被调用;保留抛错以便误用尽早暴露。
        throw new UnsupportedOperationException(
                "FabricServerNetworkBridge.sendToServer called; this side runs on SERVER. channel=" + channel);
    }

    private static final class C2SHandler implements ServerPlayNetworking.PlayChannelHandler {
        final BiConsumer<FriendlyByteBuf, ServerPlayer> body;
        C2SHandler(BiConsumer<FriendlyByteBuf, ServerPlayer> body) { this.body = body; }
        @Override
        public void receive(MinecraftServer server, ServerPlayer player,
                            ServerGamePacketListenerImpl handler,
                            FriendlyByteBuf buf, PacketSender responseSender) {
            body.accept(buf, player);
        }
    }

    private static C2SHandler c2s(BiConsumer<FriendlyByteBuf, ServerPlayer> body) {
        return new C2SHandler(body);
    }

    private static volatile boolean initialized = false;

    /**
     * Server init. <b>Idempotent</b>: registering the same global receiver twice
     * would fail on Fabric, and the {@code FabricServerNetworkBridge#INSTANCE} class
     * cannot be reassigned safely — instead we set it once and skip re-registration
     * on subsequent calls. The method is invoked from two entrypoints:
     * <ul>
     *   <li>{@code QishengChessFabricServer.onInitializeServer} —
     *       dedicated server (env=SERVER); {@code server} entrypoint fires.</li>
     *   <li>{@code QishengChessFabric.onInitialize} —
     *       <b>singleplayer</b> (integrated server in the same JVM, env=CLIENT);
     *       {@code server} entrypoint does NOT fire, so we MUST init here or the
     *       user sees the "已加入红方" popup but no GUI (sendOpenScreen throws
     *       "serverSender not yet initialized" silently).</li>
     * </ul>
     * On a pure multiplayer client (no integrated server, connecting to remote
     * server) this still fires harmlessly: the local registration simply has no
     * server in this JVM to register against, so no packets are intercepted here.
     * The remote server has its own receiver registrations.
     *
     * <p>No env gate needed (v0.4.12). Earlier v0.4.9-v0.4.11 attempts used
     * {@code Class.forName("net.minecraft.server.level.ServerPlayer")} which
     * <b>always failed</b> at runtime (Yarn name not present — JVM uses
     * intermediary {@code class_3222}), causing initServer to skip on BOTH envs.
     * The MinecraftServer-based gate in v0.4.11 also fails because the static
     * {@code getServer()} method does not exist in 1.20.1 Yarn. v0.4.12 simply
     * attempts registration; Loom's bytecode remapping ensures all
     * SERVER-only type references resolve to intermediary names that exist in
     * both envs (verified in jar bytecode inspection).
     */
    public static void initServer() {
        if (initialized) return;
        try {
            ModNetwork.setServerSender(INSTANCE);
            registerAll();
            QishengChessFabric.LOGGER.info("[" + com.qisheng.chess.QishengChess.MOD_ID
                    + "] FabricServerNetworkBridge.initServer completed on env="
                    + net.fabricmc.loader.api.FabricLoader.getInstance().getEnvironmentType());
        } catch (Throwable t) {
            QishengChessFabric.LOGGER.warn("[" + com.qisheng.chess.QishengChess.MOD_ID
                    + "] FabricServerNetworkBridge.initServer failed: " + t, t);
            return;
        }
        initialized = true;
    }

    private static void registerAll() {
        ServerPlayNetworking.registerGlobalReceiver(ModNetwork.CHESS_INTERACT,
                c2s(ChessInteractC2SPacket::receive));
        ServerPlayNetworking.registerGlobalReceiver(ModNetwork.CHESS_DRAW_REQUEST,
                c2s(DrawPackets.Request::receive));
        ServerPlayNetworking.registerGlobalReceiver(ModNetwork.CHESS_DRAW_RESPONSE,
                c2s(DrawPackets.Response::receive));
        ServerPlayNetworking.registerGlobalReceiver(ModNetwork.CHESS_RESIGN,
                c2s(ChessResignC2SPacket::receive));
        ServerPlayNetworking.registerGlobalReceiver(ModNetwork.CHESS_SWITCH_REQUEST,
                c2s(SwitchPackets.Request::receive));
        ServerPlayNetworking.registerGlobalReceiver(ModNetwork.CHESS_SWITCH_RESPONSE,
                c2s(SwitchPackets.Response::receive));
        ServerPlayNetworking.registerGlobalReceiver(ModNetwork.CHESS_CHAT,
                c2s(ChatPackets.Send::receive));
    }
}