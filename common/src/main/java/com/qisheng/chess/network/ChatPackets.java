package com.qisheng.chess.network;

import com.qisheng.chess.client.CChessBoardScreen;
import com.qisheng.chess.pvp.BoardKey;
import com.qisheng.chess.pvp.GameBroadcaster;
import com.qisheng.chess.pvp.GameSession;
import com.qisheng.chess.pvp.SessionManager;
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

    /**
     * Wire limit, in <b>UTF-8 bytes</b> — not characters.
     *
     * <p>{@code FriendlyByteBuf.writeUtf(s, max)} compares
     * {@code ByteBufUtil.utf8MaxBytes(s)} against {@code max}, so the old value of
     * 64 silently meant "about 21 Chinese characters": typing a longer line and
     * pressing Enter threw {@code EncoderException} out of {@code keyPressed} and
     * crashed the client. 256 bytes ≈ 85 CJK characters, which is a comfortable
     * one-line limit. The client clamps to {@code min(MAX_LEN, 256)} bytes before
     * sending, so both sides stay in sync — keep them equal if you change this.
     */
    public static final int MAX_LEN = 256;

    private ChatPackets() {}

    public static class Send {
        private Send() {}

        public static FriendlyByteBuf write(String text) {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            buf.writeUtf(text == null ? "" : text, MAX_LEN);
            return buf;
        }

        public static void receive(FriendlyByteBuf buf, ServerPlayer sender) {
            if (sender == null) return;
            String text = buf.readUtf(MAX_LEN).trim();
            // strip control chars except tab/newline
            text = text.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "");
            text = text.trim();
            if (text.isEmpty()) return;
            SessionManager sm = SessionManager.get();
            BoardKey key = sm.getPlayerGame(sender.getUUID());
            if (key == null) key = sm.getPlayerSpectating(sender.getUUID());
            if (key == null) {
                GameBroadcaster.sendPopupTo(sender,
                        Component.literal("你不在对局中,无法发评论。"),
                        PopupS2CPacket.Severity.WARN, 3);
                return;
            }
            GameSession session = sm.get(key);
            if (session == null) return;
            // 棋盘可能在别的维度:评论只发给棋盘所在世界里的对局参与者。
            BlockPos pos = key.pos();
            ServerLevel boardLevel = SessionManager.resolve(sender.getServer(), key, sender.serverLevel());
            broadcast(boardLevel, session, pos, sender.getUUID(), text);
        }

        public static void broadcast(ServerLevel level, GameSession session, BlockPos pos,
                                      UUID senderId, String text) {
            // Each recipient gets its own buffer — sharing the same FriendlyByteBuf
            // would drain the reader index and the second recipient would read empty.
            for (ServerPlayer p : level.players()) {
                if (session.containsPlayer(p.getUUID()) || session.isSpectator(p.getUUID())) {
                    FriendlyByteBuf buf = Broadcast.write(senderId, text);
                    ModNetwork.sendToPlayer(p, ModNetwork.CHESS_CHAT, buf);
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

        public static void receive(FriendlyByteBuf buf) {
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