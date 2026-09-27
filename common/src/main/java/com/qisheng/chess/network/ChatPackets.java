package com.qisheng.chess.network;

import com.qisheng.chess.client.CChessBoardScreen;
import com.qisheng.chess.pvp.GameBroadcaster;
import com.qisheng.chess.pvp.GameSession;
import com.qisheng.chess.pvp.SessionManager;
import dev.architectury.networking.NetworkManager;
import dev.architectury.networking.NetworkManager.PacketContext;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Packets for the in-game chat (评论 / 聊天).
 */
public final class ChatPackets {

    public static final int MAX_LEN = 64;

    private ChatPackets() {}

    public static class Send {
        private Send() {}

        public static FriendlyByteBuf write(String text) {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            buf.writeUtf(text == null ? "" : text, MAX_LEN);
            return buf;
        }

        public static void receive(FriendlyByteBuf buf, PacketContext ctx) {
            ServerPlayer sender = (ServerPlayer) ctx.getPlayer();
            if (sender == null) return;
            String text = buf.readUtf(MAX_LEN).trim();
            // strip control chars except tab/newline
            text = text.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "");
            text = text.trim();
            if (text.isEmpty()) return;
            SessionManager sm = SessionManager.get();
            BlockPos pos = sm.getPlayerGame(sender.getUUID());
            if (pos == null) pos = sm.getPlayerSpectating(sender.getUUID());
            if (pos == null) {
                GameBroadcaster.sendPopupTo(sender,
                        Component.literal("你不在对局中,无法发评论。"),
                        PopupS2CPacket.Severity.WARN, 3);
                return;
            }
            GameSession session = sm.get(pos);
            if (session == null) return;
            ServerLevel level = sender.serverLevel();
            broadcast(level, session, pos, sender.getUUID(), text);
        }

        public static void broadcast(ServerLevel level, GameSession session, BlockPos pos,
                                      UUID senderId, String text) {
            FriendlyByteBuf buf = Broadcast.write(senderId, text);
            for (ServerPlayer p : level.players()) {
                if (session.containsPlayer(p.getUUID()) || session.isSpectator(p.getUUID())) {
                    NetworkManager.sendToPlayer(p, ModNetwork.CHESS_CHAT, buf);
                }
            }
        }
    }

    public static class Broadcast {
        private Broadcast() {}

        public static FriendlyByteBuf write(UUID senderId, String text) {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            buf.writeUUID(senderId);
            buf.writeUtf(text, MAX_LEN);
            return buf;
        }

        public static void receive(FriendlyByteBuf buf, PacketContext ctx) {
            UUID senderId = buf.readUUID();
            String text = buf.readUtf(MAX_LEN);
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                if (mc.screen instanceof CChessBoardScreen scr) {
                    scr.onChatMessage(senderId, text);
                }
            });
        }
    }
}