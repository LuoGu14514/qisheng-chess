package com.qisheng.chess.fabric;

import com.qisheng.chess.network.ChatPackets;
import com.qisheng.chess.network.ChessOpenScreenS2CPacket;
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
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Consumer;

/**
 * Fabric CLIENT 端网络包注册与发包实现。
 *
 * <p>本类标记 {@link Environment EnvType.CLIENT},只在 CLIENT 端(独立游戏及
 * 集成服的 client 端)被 Fabric Loader 加载;独立 SERVER 加载时整类被剥离。
 * 因此本类只允许引用 CLIENT-only 的类,不能 import 任何
 * {@code net.minecraft.server.*} 或 {@code ServerPlayNetworking}。
 *
 * <p>对应服务器端实现见 {@link FabricServerNetworkBridge};调用方
 * (common 端 CChessBoardScreen) 经 {@link ModNetwork#sendToServer} 间接调用本类的
 * {@link #sendToServer}。
 */
@Environment(EnvType.CLIENT)
public final class FabricClientNetworkBridge implements ModNetwork.FabricSender {
    public static final FabricClientNetworkBridge INSTANCE = new FabricClientNetworkBridge();

    private FabricClientNetworkBridge() {}

    @Override
    public void sendToPlayer(ServerPlayer player, ResourceLocation channel, FriendlyByteBuf buf) {
        // 本类只在 CLIENT 端加载,理论上不应被调用;保留抛错以便误用尽早暴露。
        throw new UnsupportedOperationException(
                "FabricClientNetworkBridge.sendToPlayer called; this side runs on CLIENT. channel=" + channel);
    }

    @Override
    public void sendToServer(ResourceLocation channel, FriendlyByteBuf buf) {
        ClientPlayNetworking.send(channel, buf);
    }

    private static final class S2CHandler implements ClientPlayNetworking.PlayChannelHandler {
        final Consumer<FriendlyByteBuf> body;
        S2CHandler(Consumer<FriendlyByteBuf> body) { this.body = body; }
        @Override
        public void receive(Minecraft client, ClientPacketListener handler,
                            FriendlyByteBuf buf, PacketSender responseSender) {
            body.accept(buf);
        }
    }

    private static S2CHandler s2c(Consumer<FriendlyByteBuf> body) {
        return new S2CHandler(body);
    }

    private static volatile boolean initialized = false;

    /**
     * Client init. <b>Idempotent</b>: called from {@code QishengChessFabricClient.onInitializeClient}
     * (the {@code client} entrypoint) AND, defensively, from {@code QishengChessFabric.onInitialize}
     * (the {@code main} entrypoint) so a missing client entrypoint never leaves the
     * client sender as the throwing placeholder.
     */
    public static void initClient() {
        if (initialized) return;
        ModNetwork.setClientSender(INSTANCE);
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
        initialized = true;
    }
}