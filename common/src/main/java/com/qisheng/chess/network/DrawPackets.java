package com.qisheng.chess.network;

import com.qisheng.chess.client.CChessBoardScreen;
import com.qisheng.chess.network.PopupS2CPacket;
import com.qisheng.chess.pvp.GameBroadcaster;
import com.qisheng.chess.pvp.GameResult;
import com.qisheng.chess.pvp.GameSession;
import com.qisheng.chess.pvp.GameState;
import com.qisheng.chess.pvp.SessionManager;
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
 * Packets for the in-game "draw" (求和) workflow.
 *
 * <h2>Flow</h2>
 * <pre>
 *   Red player clicks "求和"
 *       │
 *       │ (1) C2S  DrawPackets.Request          (body empty)
 *       ▼
 *   Server: SessionManager.requestDraw → sets session.pendingDrawFrom
 *           GameBroadcaster.sendDrawInvite → S2C Invite to opponent
 *                                          → S2C Result(REQUESTED) to requester
 *
 *   Black player clicks [接受] or [拒绝]
 *       │
 *       │ (2) C2S DrawPackets.Response  (byte accept)
 *       ▼
 *   Server: SessionManager.respondDraw
 *     accept=true  → session.Result=DRAW, state=FINISHED,
 *                    broadcastGameOver + broadcastDrawResult(ACCEPTED)
 *     accept=false → cancel pending, broadcastDrawResult(REJECTED)
 * </pre>
 */
public final class DrawPackets {

    public enum Result { REQUESTED, ACCEPTED, REJECTED, CANCELLED }

    private DrawPackets() {}

    public static class Request {
        private Request() {}

        public static FriendlyByteBuf write() {
            return new FriendlyByteBuf(Unpooled.buffer());
        }

        public static void receive(FriendlyByteBuf buf, PacketContext ctx) {
            ServerPlayer sender = (ServerPlayer) ctx.getPlayer();
            if (sender == null) return;
            SessionManager sm = SessionManager.get();
            BlockPos pos = sm.getPlayerGame(sender.getUUID());
            if (pos == null) return;
            GameSession session = sm.get(pos);
            if (session == null) return;
            ServerLevel level = sender.serverLevel();
            boolean ok = sm.requestDraw(sender.getUUID(), pos);
            if (!ok) {
                GameBroadcaster.sendPopupTo(sender,
                        Component.literal("无法发起求和(对局未进行、你不是对局玩家、或已有未处理的申请)。"),
                        PopupS2CPacket.Severity.WARN, 4);
                return;
            }
            GameBroadcaster.sendDrawInvite(level, session, pos, sender.getUUID());
        }
    }

    public static class Invite {
        private Invite() {}

        public static FriendlyByteBuf write(BlockPos pos, UUID from) {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            buf.writeBlockPos(pos);
            buf.writeUUID(from);
            return buf;
        }

        public static void receive(FriendlyByteBuf buf, PacketContext ctx) {
            BlockPos pos = buf.readBlockPos();
            UUID from = buf.readUUID();
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                if (mc.screen instanceof CChessBoardScreen scr
                        && scr.getBoardPos().equals(pos)) {
                    scr.onDrawInvite(from);
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
            ServerLevel level = responder.serverLevel();
            SessionManager.DrawOutcome outcome = sm.respondDraw(responder.getUUID(), pos, accept);
            switch (outcome) {
                case ACCEPTED -> {
                    session.setResult(GameResult.DRAW);
                    session.setState(GameState.FINISHED);
                    sm.cancelDraw(pos);
                    GameBroadcaster.broadcastGameOver(level, session, pos, GameResult.DRAW);
                    GameBroadcaster.broadcastDrawResult(level, session, pos, Result.ACCEPTED);
                }
                case REJECTED -> GameBroadcaster.broadcastDrawResult(level, session, pos, Result.REJECTED);
                case CANCELLED -> GameBroadcaster.sendPopupTo(responder,
                        Component.literal("求和申请已撤销(对局状态变化)。"),
                        PopupS2CPacket.Severity.INFO, 3);
            }
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
                    scr.onDrawResult(res);
                }
            });
        }
    }
}