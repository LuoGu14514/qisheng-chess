package com.qisheng.chess.pvp;

import com.qisheng.chess.block.CChessBoardBlock;
import com.qisheng.chess.engine.ChineseChessEngine;
import com.qisheng.chess.engine.xqwlight.Position;
import com.qisheng.chess.network.DrawPackets;
import com.qisheng.chess.network.LegalDestsBitmap;
import com.qisheng.chess.network.ModNetwork;
import com.qisheng.chess.network.PopupS2CPacket;
import com.qisheng.chess.network.SpectatorListS2CPacket;
import com.qisheng.chess.network.SpectatorListS2CPacket.PlayerEntry;
import com.qisheng.chess.network.SwitchPackets;
import com.qisheng.chess.tileentity.CChessTileEntity;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Server → client packet helpers.
 *
 * <p><b>Every send must build its own {@link FriendlyByteBuf}.</b> An earlier
 * version hoisted the buffer out of the recipient loop in
 * {@code broadcastRoster} / {@code broadcastDrawResult} / {@code sendSwitchResult}
 * and handed the <em>same</em> instance to every packet. The first packet to be
 * encoded drains the buffer's reader index, so the second recipient onwards
 * encoded an empty payload — the client then read garbage or threw. The
 * {@code send} / {@code sendToAll} helpers below construct a fresh buffer per
 * recipient, which makes that class of bug impossible to reintroduce.
 */
public final class GameBroadcaster {
    private GameBroadcaster() {}

    // ---- delivery ----

    private static void send(ServerPlayer player, ResourceLocation channel,
                             Consumer<FriendlyByteBuf> writer) {
        if (player == null) return;
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        writer.accept(buf);
        NetworkManager.sendToPlayer(player, channel, buf);
    }

    /** Fans a packet out to every player seated at or spectating {@code session}. */
    private static void sendToAll(ServerLevel level, GameSession session, ResourceLocation channel,
                                  Consumer<FriendlyByteBuf> writer) {
        for (ServerPlayer p : level.players()) {
            if (isRecipient(session, p.getUUID())) {
                send(p, channel, writer);
            }
        }
    }

    /**
     * Resolve a player across every dimension.
     *
     * <p>{@code ServerLevel#getPlayerByUUID} only searches that one level, so a
     * player standing in the Nether was invisible to code running with the
     * Overworld level that owns the board.
     */
    public static ServerPlayer findPlayer(ServerLevel level, UUID id) {
        if (id == null) return null;
        MinecraftServer server = level.getServer();
        if (server != null) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) return p;
        }
        return level.getPlayerByUUID(id) instanceof ServerPlayer sp ? sp : null;
    }

    // ---- board state ----

    public static void broadcastSync(ServerLevel level, GameSession session, BlockPos pos) {
        if (session == null) return;
        // Every state change in the mod funnels through here, so this is the one
        // place that has to mark the board chunk dirty for the next world save.
        CChessTileEntity.markChanged(level, pos);
        // Same reason, for the computer opponent: a PVC board whose turn just
        // became the computer's is picked up here and nowhere else.
        PvcController.maybeSchedule(level, pos, session);
        String fen = session.getChessData().toFen();
        int sdPlayer = session.getSdPlayer();
        int stateOrd = session.getState().ordinal();
        int selectPoint = session.getSelectPoint();
        boolean[] legalDests = computeLegalDestsForSelection(session);
        sendToAll(level, session, ModNetwork.CHESS_SYNC, buf -> {
            buf.writeBlockPos(pos);
            buf.writeUtf(fen);
            buf.writeByte(sdPlayer);
            buf.writeByte(stateOrd);
            buf.writeShort(selectPoint);
            buf.writeBoolean(legalDests != null);
            if (legalDests != null) {
                LegalDestsBitmap.write(buf, legalDests);
            }
        });
    }

    /**
     * Compute the 256-square bitmap of legal destinations for the currently
     * selected piece, when that piece belongs to the side to move. Returns
     * {@code null} in every other case (no selection, off-board, opponent's
     * piece, finished game) so the client falls back to its own cached compute.
     *
     * <p>The bitmap is per-FEN, not per-recipient: both players and every
     * spectator can see the same dots, so it ships to everyone in the room.
     */
    private static boolean[] computeLegalDestsForSelection(GameSession session) {
        int sdPlayer = session.getSdPlayer();
        if (session.getState() == GameState.FINISHED) return null;
        int selectedSq = session.getSelectPoint();
        if (selectedSq < 0) return null;
        Position pos = session.getChessData();
        if (!ChineseChessEngine.isSquare(selectedSq)) return null;
        byte piece = pos.squares[selectedSq];
        if (piece == 0) return null;
        int tag = Position.SIDE_TAG(sdPlayer);
        if ((piece & tag) == 0) return null;
        boolean[] dests = new boolean[256];
        for (int dst = 0; dst < 256; dst++) {
            if (dst != selectedSq && ChineseChessEngine.canMove(pos, selectedSq, dst)) {
                dests[dst] = true;
            }
        }
        return dests;
    }

    public static void sendOpenScreen(ServerPlayer player, GameSession session, BlockPos pos) {
        if (session == null || player == null) return;
        String fen = session.getChessData().toFen();
        int sdPlayer = session.getSdPlayer();
        int stateOrd = session.getState().ordinal();
        int selectPoint = session.getSelectPoint();
        int role = session.getPlayerRole(player.getUUID());
        UUID self = player.getUUID();
        boolean[] legalDests = computeLegalDestsForSelection(session);
        // The board block's FACING (a horizontal Direction in BlockState) tells
        // the GUI whether to render flipped: SOUTH = the block's "front" faces
        // the placer's normal standing side, so the client flips the GUI 180°
        // on top of any role-based viewerIsBlack to keep red on the bottom from
        // the spectator's side.
        boolean flipped = player.level().getBlockState(pos)
                .getValue(CChessBoardBlock.FACING) == Direction.SOUTH;
        send(player, ModNetwork.CHESS_OPEN_SCREEN, buf -> {
            buf.writeBlockPos(pos);
            buf.writeUUID(self);
            buf.writeUtf(fen);
            buf.writeByte(sdPlayer);
            buf.writeByte(stateOrd);
            buf.writeShort(selectPoint);
            buf.writeByte(role);
            buf.writeBoolean(legalDests != null);
            if (legalDests != null) {
                LegalDestsBitmap.write(buf, legalDests);
            }
            buf.writeBoolean(flipped);
        });
    }

    public static void broadcastGameOver(ServerLevel level, GameSession session, BlockPos pos,
                                         GameResult result) {
        broadcastSync(level, session, pos);
        Component msg = Component.translatable(switch (result) {
            case RED_WIN   -> "qisheng.chess.game.over.red_win";
            case BLACK_WIN -> "qisheng.chess.game.over.black_win";
            case DRAW      -> "qisheng.chess.game.over.draw";
            case ABANDONED -> "qisheng.chess.game.over.abandoned";
            default        -> "qisheng.chess.game.over.generic";
        });
        broadcastPopup(level, session, msg, PopupS2CPacket.Severity.INFO, 0);
        // Every game-over path resets the board, so the roster must follow.
        broadcastRoster(level, session, pos);
    }

    // ---- Popups ----

    public static void broadcastPopup(ServerLevel level, GameSession session,
                                      Component msg, PopupS2CPacket.Severity severity,
                                      int autoSec) {
        if (session == null || msg == null) return;
        sendToAll(level, session, ModNetwork.CHESS_POPUP,
                buf -> writePopup(buf, msg, severity, autoSec));
    }

    public static void sendPopupTo(ServerPlayer player, Component msg,
                                   PopupS2CPacket.Severity severity, int autoSec) {
        if (player == null || msg == null) return;
        send(player, ModNetwork.CHESS_POPUP, buf -> writePopup(buf, msg, severity, autoSec));
    }

    public static void broadcastPopupTo(ServerPlayer player, Component msg) {
        sendPopupTo(player, msg, PopupS2CPacket.Severity.INFO, 3);
    }

    private static void writePopup(FriendlyByteBuf buf, Component msg,
                                   PopupS2CPacket.Severity severity, int autoSec) {
        buf.writeByte(severity.ordinal());
        buf.writeComponent(msg);
        // Clamp: the wire format is a single unsigned byte, and a negative value
        // would come back as a huge/negative timeout on the client.
        buf.writeByte(Math.max(0, Math.min(255, autoSec)));
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
        // SpectatorListS2CPacket.write allocates a fresh buffer per call, so it is
        // invoked once per recipient rather than once per broadcast.
        sendToAll(level, session, ModNetwork.CHESS_PLAYER_INFO,
                buf -> SpectatorListS2CPacket.writeInto(buf, pos, red, black, specArr));
    }

    private static PlayerEntry entry(ServerLevel level, UUID id) {
        if (id == null) return null;
        ServerPlayer sp = findPlayer(level, id);
        if (sp != null) return new PlayerEntry(id, sp.getGameProfile().getName());
        return new PlayerEntry(id, "");
    }

    // ---- Draw (求和) ----

    public static void sendDrawInvite(ServerLevel level, GameSession session, BlockPos pos,
                                      UUID requester) {
        UUID other = requester.equals(session.getRedPlayer())
                ? session.getBlackPlayer() : session.getRedPlayer();
        if (other == null) return;
        ServerPlayer op = findPlayer(level, other);
        if (op == null) return;
        send(op, ModNetwork.CHESS_DRAW_INVITE,
                buf -> DrawPackets.Invite.writeInto(buf, pos, requester));
        ServerPlayer reqPlayer = findPlayer(level, requester);
        if (reqPlayer != null) {
            send(reqPlayer, ModNetwork.CHESS_DRAW_RESULT,
                    buf -> DrawPackets.ResultPacket.writeInto(buf, DrawPackets.Result.REQUESTED));
        }
    }

    public static void broadcastDrawResult(ServerLevel level, GameSession session, BlockPos pos,
                                           DrawPackets.Result result) {
        if (session == null) return;
        sendToAll(level, session, ModNetwork.CHESS_DRAW_RESULT,
                buf -> DrawPackets.ResultPacket.writeInto(buf, result));
    }

    // ---- Switch (切换身份) ----

    /** Broadcast a switch result to the two players (the rest of the roster is unaffected). */
    public static void sendSwitchResult(ServerLevel level, GameSession session, BlockPos pos,
                                        SwitchPackets.Result result) {
        if (session == null) return;
        for (ServerPlayer p : level.players()) {
            if (session.containsPlayer(p.getUUID())) {
                send(p, ModNetwork.CHESS_SWITCH_RESULT,
                        buf -> SwitchPackets.ResultPacket.writeInto(buf, result));
            }
        }
    }

    public static boolean isRecipient(GameSession session, UUID id) {
        return session.containsPlayer(id) || session.isSpectator(id);
    }
}
