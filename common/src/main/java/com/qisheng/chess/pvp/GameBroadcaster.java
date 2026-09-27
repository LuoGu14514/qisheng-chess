package com.qisheng.chess.pvp;

import com.qisheng.chess.network.DrawPackets;
import com.qisheng.chess.network.ModNetwork;
import com.qisheng.chess.network.PopupS2CPacket;
import com.qisheng.chess.network.SpectatorListS2CPacket;
import com.qisheng.chess.network.SpectatorListS2CPacket.PlayerEntry;
import com.qisheng.chess.network.SwitchPackets;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class GameBroadcaster {
    private GameBroadcaster() {}

    public static void broadcastSync(ServerLevel level, GameSession session, BlockPos pos) {
        if (session == null) return;
        for (ServerPlayer p : level.players()) {
            if (isRecipient(session, p.getUUID())) {
                FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
                buf.writeBlockPos(pos);
                buf.writeUtf(session.getChessData().toFen());
                buf.writeByte(session.getSdPlayer());
                buf.writeByte(session.getState().ordinal());
                buf.writeShort(session.getSelectPoint());
                NetworkManager.sendToPlayer(p, ModNetwork.CHESS_SYNC, buf);
            }
        }
    }

    public static void sendOpenScreen(ServerPlayer player, GameSession session, BlockPos pos) {
        if (session == null) return;
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeBlockPos(pos);
        buf.writeUUID(player.getUUID());
        buf.writeUtf(session.getChessData().toFen());
        buf.writeByte(session.getSdPlayer());
        buf.writeByte(session.getState().ordinal());
        buf.writeShort(session.getSelectPoint());
        buf.writeByte(session.getPlayerRole(player.getUUID()));
        NetworkManager.sendToPlayer(player, ModNetwork.CHESS_OPEN_SCREEN, buf);
    }

    public static void broadcastGameOver(ServerLevel level, GameSession session, BlockPos pos,
                                         GameResult result) {
        broadcastSync(level, session, pos);
        Component msg = Component.literal(switch (result) {
            case RED_WIN   -> "对局结束 — 红方胜!";
            case BLACK_WIN -> "对局结束 — 黑方胜!";
            case DRAW      -> "对局结束 — 和棋!";
            case ABANDONED -> "对局结束 — 已流局。";
            default        -> "对局结束。";
        });
        broadcastPopup(level, session, msg, PopupS2CPacket.Severity.INFO, 0);
    }

    // ---- Popups ----

    public static void broadcastPopup(ServerLevel level, GameSession session,
                                       Component msg, PopupS2CPacket.Severity severity,
                                       int autoSec) {
        if (session == null || msg == null) return;
        for (ServerPlayer p : level.players()) {
            if (isRecipient(session, p.getUUID())) {
                sendPopupTo(p, msg, severity, autoSec);
            }
        }
    }

    public static void sendPopupTo(ServerPlayer player, Component msg,
                                   PopupS2CPacket.Severity severity, int autoSec) {
        if (player == null || msg == null) return;
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeByte(severity.ordinal());
        buf.writeComponent(msg);
        buf.writeByte(autoSec);
        NetworkManager.sendToPlayer(player, ModNetwork.CHESS_POPUP, buf);
    }

    public static void broadcastPopupTo(ServerPlayer player, Component msg) {
        sendPopupTo(player, msg, PopupS2CPacket.Severity.INFO, 3);
    }

    // ---- Roster ----

    public static void broadcastRoster(ServerLevel level, GameSession session, BlockPos pos) {
        if (session == null) return;
        PlayerEntry red = entry(level, session.getRedPlayer());
        PlayerEntry black = entry(level, session.getBlackPlayer());
        List<PlayerEntry> specs = new ArrayList<>();
        for (UUID id : session.getSpectators()) {
            PlayerEntry e = entry(level, id);
            if (e != null) specs.add(e);
        }
        PlayerEntry[] specArr = specs.toArray(new PlayerEntry[0]);
        FriendlyByteBuf buf = SpectatorListS2CPacket.write(pos, red, black, specArr);
        for (ServerPlayer p : level.players()) {
            if (isRecipient(session, p.getUUID())) {
                NetworkManager.sendToPlayer(p, ModNetwork.CHESS_PLAYER_INFO, buf);
            }
        }
    }

    private static PlayerEntry entry(ServerLevel level, UUID id) {
        if (id == null) return null;
        ServerPlayer sp = (ServerPlayer) level.getPlayerByUUID(id);
        if (sp != null) return new PlayerEntry(id, sp.getGameProfile().getName());
        return new PlayerEntry(id, "");
    }

    // ---- Draw (求和) ----

    public static void sendDrawInvite(ServerLevel level, GameSession session, BlockPos pos,
                                       UUID requester) {
        UUID other = requester.equals(session.getRedPlayer())
                ? session.getBlackPlayer() : session.getRedPlayer();
        if (other == null) return;
        ServerPlayer op = (ServerPlayer) level.getPlayerByUUID(other);
        if (op == null) return;
        FriendlyByteBuf buf = DrawPackets.Invite.write(pos, requester);
        NetworkManager.sendToPlayer(op, ModNetwork.CHESS_DRAW_INVITE, buf);
        ServerPlayer reqPlayer = (ServerPlayer) level.getPlayerByUUID(requester);
        if (reqPlayer != null) {
            FriendlyByteBuf reqBuf = DrawPackets.ResultPacket.write(DrawPackets.Result.REQUESTED);
            NetworkManager.sendToPlayer(reqPlayer, ModNetwork.CHESS_DRAW_RESULT, reqBuf);
        }
    }

    public static void broadcastDrawResult(ServerLevel level, GameSession session, BlockPos pos,
                                            DrawPackets.Result result) {
        if (session == null) return;
        FriendlyByteBuf buf = DrawPackets.ResultPacket.write(result);
        for (ServerPlayer p : level.players()) {
            if (isRecipient(session, p.getUUID())) {
                NetworkManager.sendToPlayer(p, ModNetwork.CHESS_DRAW_RESULT, buf);
            }
        }
    }

    // ---- Switch (切换身份) ----

    /** Broadcast a switch result to the two players (the rest of the roster is unaffected). */
    public static void sendSwitchResult(ServerLevel level, GameSession session, BlockPos pos,
                                         SwitchPackets.Result result) {
        if (session == null) return;
        FriendlyByteBuf buf = SwitchPackets.ResultPacket.write(result);
        for (ServerPlayer p : level.players()) {
            if (session.containsPlayer(p.getUUID())) {
                NetworkManager.sendToPlayer(p, ModNetwork.CHESS_SWITCH_RESULT, buf);
            }
        }
    }

    private static boolean isRecipient(GameSession session, UUID id) {
        return session.containsPlayer(id) || session.isSpectator(id);
    }
}