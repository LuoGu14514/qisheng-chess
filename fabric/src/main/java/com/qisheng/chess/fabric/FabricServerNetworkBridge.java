package com.qisheng.chess.fabric;

import com.qisheng.chess.network.ChatPackets;
import com.qisheng.chess.network.ChessInteractC2SPacket;
import com.qisheng.chess.network.ChessResignC2SPacket;
import com.qisheng.chess.network.DrawPackets;
import com.qisheng.chess.network.ModNetwork;
import com.qisheng.chess.network.SwitchPackets;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
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
 * <p>本类标记 {@link Environment EnvType.SERVER},只在 SERVER 端(独立服或
 * 集成服的 server 端)被 Fabric Loader 加载;客户端加载时整类被剥离。
 * 因此本类只允许引用 SERVER-only 的类,不能 import 任何
 * {@code net.minecraft.client.*} 或 {@code ClientPlayNetworking}。
 *
 * <p>对应客户端实现见 {@link FabricClientNetworkBridge};调用方
 * (common 端 GameBroadcaster) 经 {@link ModNetwork#sendToPlayer} 间接调用本类的
 * {@link #sendToPlayer}。
 */
@Environment(EnvType.SERVER)
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
     */
    public static void initServer() {
        if (initialized) return;
        initialized = true;
        ModNetwork.setServerSender(INSTANCE);
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