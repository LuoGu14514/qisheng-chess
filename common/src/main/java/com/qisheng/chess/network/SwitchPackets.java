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

        public static void receive(FriendlyByteBuf buf, PacketContext ctx) {
            ServerPlayer sender = (ServerPlayer) ctx.getPlayer();
            if (sender == null) return;
            UUID target = buf.readUUID();
            SessionManager sm = SessionManager.get();
            BlockPos pos = sm.getPlayerGame(sender.getUUID());
            if (pos == null) return;
            GameSession session = sm.get(pos);
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
            ServerLevel level = sender.serverLevel();
            ServerPlayer targetPlayer = (ServerPlayer) level.getPlayerByUUID(target);
            if (targetPlayer == null) {
                GameBroadcaster.sendPopupTo(sender,
                        Component.literal("对方离线,无法邀请。"),
                        PopupS2CPacket.Severity.WARN, 3);
                session.setPendingSwitch(null);
                return;
            }
            FriendlyByteBuf invBuf = Invite.write(sender.getUUID());
            NetworkManager.sendToPlayer(targetPlayer, ModNetwork.CHESS_SWITCH_INVITE, invBuf);
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

        public static void receive(FriendlyByteBuf buf, PacketContext ctx) {
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

        public static void receive(FriendlyByteBuf buf, PacketContext ctx) {
            ServerPlayer responder = (ServerPlayer) ctx.getPlayer();
            if (responder == null) return;
            boolean accept = buf.readByte() != 0;
            SessionManager sm = SessionManager.get();
            BlockPos pos = sm.getPlayerGame(responder.getUUID());
            if (pos == null) return;
            GameSession session = sm.get(pos);
            if (session == null) return;
            Pending pending = session.getPendingSwitch();
            if (pending == null || !pending.to.equals(responder.getUUID())) {
                GameBroadcaster.sendPopupTo(responder,
                        Component.literal("没有待处理的换身份申请。"),
                        PopupS2CPacket.Severity.INFO, 2);
                return;
            }
            ServerLevel level = responder.serverLevel();
            session.setPendingSwitch(null);
            if (!accept) {
                GameBroadcaster.sendSwitchResult(level, session, pos, Result.REJECTED);
                return;
            }
            boolean ok = sm.swapRoles(pending.from, pending.to);
            if (!ok) {
                GameBroadcaster.sendSwitchResult(level, session, pos, Result.NO_LONGER_VALID);
                return;
            }
            GameBroadcaster.broadcastRoster(level, session, pos);
            GameBroadcaster.broadcastSync(level, session, pos);
            GameBroadcaster.sendSwitchResult(level, session, pos, Result.ACCEPTED);
        }
    }

    public static class ResultPacket {
        private ResultPacket() {}

        public static FriendlyByteBuf write(Result result) {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            buf.writeByte(result.ordinal());
            return buf;
        }

        public static void receive(FriendlyByteBuf buf, PacketContext ctx) {
            int ord = buf.readByte();
            Result res = Result.values()[ord];
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                if (mc.screen instanceof CChessBoardScreen scr) {
                    scr.onSwitchResult(res);
                }
            });
        }
    }
}