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
 * Packets for the "switch roles" (切换身份) workflow.
 */
public final class SwitchPackets {

    public enum Result { ACCEPTED, REJECTED, CANCELLED, NO_LONGER_VALID }

    public static class Pending {
        public final UUID from;
        public final UUID to;
        public Pending(UUID from, UUID to) { this.from = from; this.to = to; }
    }

    private SwitchPackets() {}

    public static class Request {
        private Request() {}

        public static FriendlyByteBuf write(UUID targetId) {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            buf.writeUUID(targetId);
            return buf;
        }

        public static void receive(FriendlyByteBuf buf, ServerPlayer sender) {
            if (sender == null) return;
            UUID target = buf.readUUID();
            SessionManager sm = SessionManager.get();
            BoardKey key = sm.getPlayerGame(sender.getUUID());
            if (key == null) return;
            GameSession session = sm.get(key);
            if (session == null) return;
            if (session.getState() != com.qisheng.chess.pvp.GameState.PLAYING) {
                GameBroadcaster.sendPopupTo(sender,
                        Component.literal("对局未在进行中,无法发起切换。"),
                        PopupS2CPacket.Severity.WARN, 3);
                return;
            }
            if (!session.containsPlayer(sender.getUUID())) return;
            if (!session.containsPlayer(target)) {
                GameBroadcaster.sendPopupTo(sender,
                        Component.literal("对方已不在对局中。"),
                        PopupS2CPacket.Severity.WARN, 3);
                return;
            }
            if (sender.getUUID().equals(target)) {
                GameBroadcaster.sendPopupTo(sender,
                        Component.literal("不能跟自己换身份。"),
                        PopupS2CPacket.Severity.WARN, 3);
                return;
            }
            if (session.getPendingDrawFrom() != null) {
                GameBroadcaster.sendPopupTo(sender,
                        Component.literal("已有未处理的求和申请,稍后再试。"),
                        PopupS2CPacket.Severity.WARN, 3);
                return;
            }
            session.setPendingSwitch(new Pending(sender.getUUID(), target));
            // 对方可能站在别的维度,用跨维度查找(棋盘所在世界)。
            ServerLevel boardLevel = SessionManager.resolve(sender.getServer(), key, sender.serverLevel());
            ServerPlayer targetPlayer = GameBroadcaster.findPlayer(boardLevel, target);
            if (targetPlayer == null) {
                GameBroadcaster.sendPopupTo(sender,
                        Component.literal("对方离线,无法邀请。"),
                        PopupS2CPacket.Severity.WARN, 3);
                session.setPendingSwitch(null);
                return;
            }
            FriendlyByteBuf invBuf = Invite.write(sender.getUUID());
            ModNetwork.sendToPlayer(targetPlayer, ModNetwork.CHESS_SWITCH_INVITE, invBuf);
            GameBroadcaster.sendPopupTo(sender,
                    Component.literal("已向 " + targetPlayer.getName().getString()
                            + " 发起换身份邀请…"),
                    PopupS2CPacket.Severity.INFO, 3);
        }
    }

    public static class Invite {
        private Invite() {}

        public static FriendlyByteBuf write(UUID requester) {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            buf.writeUUID(requester);
            return buf;
        }

        public static void receive(FriendlyByteBuf buf) {
            UUID requester = buf.readUUID();
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                if (mc.screen instanceof CChessBoardScreen scr) {
                    scr.onSwitchInvite(requester);
                }
            });
        }
    }

    public static class Response {
        private Response() {}

        public static FriendlyByteBuf write(boolean accept) {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            buf.writeByte(accept ? 1 : 0);
            return buf;
        }

        public static void receive(FriendlyByteBuf buf, ServerPlayer responder) {
            if (responder == null) return;
            boolean accept = buf.readByte() != 0;
            SessionManager sm = SessionManager.get();
            BoardKey key = sm.getPlayerGame(responder.getUUID());
            if (key == null) return;
            GameSession session = sm.get(key);
            if (session == null) return;
            Pending pending = session.getPendingSwitch();
            if (pending == null || !pending.to.equals(responder.getUUID())) {
                GameBroadcaster.sendPopupTo(responder,
                        Component.literal("没有待处理的换身份申请。"),
                        PopupS2CPacket.Severity.INFO, 2);
                return;
            }
            ServerLevel boardLevel = SessionManager.resolve(responder.getServer(), key, responder.serverLevel());
            BlockPos pos = key.pos();
            session.setPendingSwitch(null);
            if (!accept) {
                GameBroadcaster.sendSwitchResult(boardLevel, session, pos, Result.REJECTED);
                return;
            }
            // Pending 不携带棋盘键(只是服务端内存里的玩家对)。上面已校验
            // responder == pending.to,所以应答者自己的对局键就是这次切换的棋盘键。
            boolean ok = sm.swapRoles(key, pending.from, pending.to);
            if (!ok) {
                GameBroadcaster.sendSwitchResult(boardLevel, session, pos, Result.NO_LONGER_VALID);
                return;
            }
            GameBroadcaster.broadcastRoster(boardLevel, session, pos);
            GameBroadcaster.broadcastSync(boardLevel, session, pos);
            GameBroadcaster.sendSwitchResult(boardLevel, session, pos, Result.ACCEPTED);
        }
    }

    public static class ResultPacket {
        private ResultPacket() {}

        public static FriendlyByteBuf write(Result result) {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            writeInto(buf, result);
            return buf;
        }

        /** Encode into a caller-owned buffer (broadcasts need one buffer per recipient). */
        public static void writeInto(FriendlyByteBuf buf, Result result) {
            buf.writeByte(result.ordinal());
        }

        public static void receive(FriendlyByteBuf buf) {
            int ord = buf.readByte();
            // Bounds-checked: a malformed/hostile ordinal used to throw
            // ArrayIndexOutOfBoundsException on the client render thread.
            // CANCELLED is the safe fallback — it dismisses any pending prompt.
            Result res = (ord >= 0 && ord < Result.values().length)
                    ? Result.values()[ord] : Result.CANCELLED;
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                if (mc.screen instanceof CChessBoardScreen scr) {
                    scr.onSwitchResult(res);
                }
            });
        }
    }
}