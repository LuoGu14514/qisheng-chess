package com.qisheng.chess.fabric;

import com.qisheng.chess.network.ChatPackets;
import com.qisheng.chess.network.ChessInteractC2SPacket;
import com.qisheng.chess.network.ChessOpenScreenS2CPacket;
import com.qisheng.chess.network.ChessResignC2SPacket;
import com.qisheng.chess.network.ChessSyncS2CPacket;
import com.qisheng.chess.network.DrawPackets;
import com.qisheng.chess.network.ModNetwork;
import com.qisheng.chess.network.PopupS2CPacket;
import com.qisheng.chess.network.SpectatorListS2CPacket;
import com.qisheng.chess.network.SwitchPackets;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Fabric 端网络包注册与发包实现。
 *
 * <p>本类是 {@link ModNetwork.FabricSender} 的实现,并负责在启动时把 C2S / S2C
 * 16 条 channel 全部挂到 Fabric 自带的 {@link ServerPlayNetworking} /
 * {@link ClientPlayNetworking} 上,完全绕开 Architectury 的
 * {@code NetworkManager}——这是修复 v0.4.5 时期服务器启动时
 * {@code NoSuchMethodError: NetworkManagerImpl.registerS2CReceiver(...)} 的关键。
 *
 * <p>由 {@code QishengChessFabric.onInitialize} 和
 * {@code QishengChessFabricClient.onInitializeClient} 在 entrypoint 处调用
 * {@link #initServer()} / {@link #initClient()} 完成注册 + 注入 {@link #INSTANCE}
 * 到 {@link ModNetwork#setSender}。
 */
@net.fabricmc.api.Environment(EnvType.SERVER)
public final class FabricNetworkBridge implements ModNetwork.FabricSender {
    public static final FabricNetworkBridge INSTANCE = new FabricNetworkBridge();

    private FabricNetworkBridge() {}

    // ---------- ModNetwork.FabricSender ----------

    @Override
    @Environment(EnvType.SERVER)
    public void sendToPlayer(ServerPlayer player, ResourceLocation channel, FriendlyByteBuf buf) {
        if (player == null) return;
        ServerPlayNetworking.send(player, channel, buf);
    }

    @Override
    @Environment(EnvType.CLIENT)
    public void sendToServer(ResourceLocation channel, FriendlyByteBuf buf) {
        ClientPlayNetworking.send(channel, buf);
    }

    // ---------- handler adapters ----------

    /** Server-side channel handler signature: (MinecraftServer, ServerPlayer, ServerGamePacketListenerImpl, FriendlyByteBuf, PacketSender) */
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

    /** Client-side channel handler signature: (Minecraft, ClientPacketListener, FriendlyByteBuf, PacketSender) */
    @Environment(EnvType.CLIENT)
    private static final class S2CHandler implements ClientPlayNetworking.PlayChannelHandler {
        final Consumer<FriendlyByteBuf> body;
        S2CHandler(Consumer<FriendlyByteBuf> body) { this.body = body; }
        @Override
        public void receive(Minecraft client, ClientPacketListener handler,
                            FriendlyByteBuf buf, PacketSender responseSender) {
            body.accept(buf);
        }
    }

    private static C2SHandler c2s(BiConsumer<FriendlyByteBuf, ServerPlayer> body) {
        return new C2SHandler(body);
    }

    @Environment(EnvType.CLIENT)
    private static S2CHandler s2c(Consumer<FriendlyByteBuf> body) {
        return new S2CHandler(body);
    }

    // ---------- registration entrypoints ----------

    /**
     * Server init. Called from {@code QishengChessFabric.onInitialize}.
     * Registers all C2S channels AND injects the FabricSender impl so that
     * common-side code (e.g. GameBroadcaster) can fan out S2C packets.
     */
    @Environment(EnvType.SERVER)
    public static void initServer() {
        ModNetwork.setSender(INSTANCE);
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

    /** Client init. Called from {@code QishengChessFabricClient.onInitializeClient}. */
    @Environment(EnvType.CLIENT)
    public static void initClient() {
        ClientPlayNetworking.registerGlobalReceiver(ModNetwork.CHESS_SYNC,
                s2c(ChessSyncS2CPacket::receive));
        ClientPlayNetworking.registerGlobalReceiver(ModNetwork.CHESS_OPEN_SCREEN,
                s2c(ChessOpenScreenS2CPacket::receive));
        ClientPlayNetworking.registerGlobalReceiver(ModNetwork.CHESS_POPUP,
                s2c(PopupS2CPacket::receive));
        ClientPlayNetworking.registerGlobalReceiver(ModNetwork.CHESS_PLAYER_INFO,
                s2c(SpectatorListS2CPacket::receive));
        ClientPlayNetworking.registerGlobalReceiver(ModNetwork.CHESS_DRAW_INVITE,
                s2c(DrawPackets.Invite::receive));
        ClientPlayNetworking.registerGlobalReceiver(ModNetwork.CHESS_DRAW_RESULT,
                s2c(DrawPackets.ResultPacket::receive));
        ClientPlayNetworking.registerGlobalReceiver(ModNetwork.CHESS_SWITCH_INVITE,
                s2c(SwitchPackets.Invite::receive));
        ClientPlayNetworking.registerGlobalReceiver(ModNetwork.CHESS_SWITCH_RESULT,
                s2c(SwitchPackets.ResultPacket::receive));
        ClientPlayNetworking.registerGlobalReceiver(ModNetwork.CHESS_CHAT,
                s2c(ChatPackets.Broadcast::receive));
    }
}