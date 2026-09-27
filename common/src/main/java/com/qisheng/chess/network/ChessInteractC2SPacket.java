package com.qisheng.chess.network;

import com.qisheng.chess.pvp.BoardMessages;
import com.qisheng.chess.pvp.GameBroadcaster;
import com.qisheng.chess.pvp.GameLogic;
import com.qisheng.chess.pvp.GameMessages;
import com.qisheng.chess.pvp.GameResult;
import com.qisheng.chess.pvp.GameSession;
import com.qisheng.chess.pvp.GameState;
import com.qisheng.chess.pvp.SessionManager;
import dev.architectury.networking.NetworkManager.PacketContext;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * C2S packet for join / select / move actions.
 *
 * <p>All user prompts now flow through {@link GameBroadcaster#sendPopupTo}
 * so the player sees them as centred in-GUI overlays instead of chat
 * lines. Roster-changing actions also call
 * {@link GameBroadcaster#broadcastRoster}.
 */
public class ChessInteractC2SPacket {

    public static final int ACTION_JOIN = 0;
    public static final int ACTION_SELECT = 1;
    public static final int ACTION_MOVE = 2;

    public static void receive(FriendlyByteBuf buf, PacketContext ctx) {
        ServerPlayer player = (ServerPlayer) ctx.getPlayer();
        if (player == null) return;

        ServerLevel level = player.serverLevel();
        BlockPos pos = buf.readBlockPos();
        int action = buf.readByte();

        GameSession session = SessionManager.get().get(pos);
        if (session == null) {
            GameBroadcaster.sendPopupTo(player,
                    Component.literal("该坐标没有棋局。"),
                    PopupS2CPacket.Severity.ERROR, 4);
            return;
        }

        switch (action) {
            case ACTION_JOIN -> handleJoin(player, level, session, pos);
            case ACTION_SELECT -> handleSelect(player, level, session, pos, buf.readShort());
            case ACTION_MOVE -> handleMove(player, level, session, pos,
                    buf.readShort(), buf.readShort());
        }
    }

    private static void handleJoin(ServerPlayer player, ServerLevel level,
                                   GameSession session, BlockPos pos) {
        SessionManager sm = SessionManager.get();
        if (!sm.isPlayerNear(level, player.getUUID(), pos)) {
            GameBroadcaster.sendPopupTo(player,
                    Component.literal("请先走近棋盘再加入。"),
                    PopupS2CPacket.Severity.WARN, 3);
            return;
        }
        UUID me = player.getUUID();
        if (session.containsPlayer(me)) {
            if (session.getState() == GameState.WAITING) {
                GameBroadcaster.sendPopupTo(player, GameMessages.alreadyJoinedWaiting(),
                        PopupS2CPacket.Severity.INFO, 3);
            } else if (session.getState() == GameState.PLAYING) {
                String role = session.getRedPlayer().equals(me) ? "红方(先手)" : "黑方(后手)";
                String turn = session.getSdPlayer() == 0 ? "红方走子" : "黑方走子";
                GameBroadcaster.sendPopupTo(player,
                        GameMessages.alreadyJoinedPlaying(role, turn),
                        PopupS2CPacket.Severity.INFO, 4);
            } else {
                GameBroadcaster.sendPopupTo(player,
                        Component.literal("对局已结束;拆掉棋盘方块重新开始。"),
                        PopupS2CPacket.Severity.INFO, 4);
            }
            return;
        }
        boolean ok = sm.joinGame(me, pos);
        if (ok) {
            if (session.getState() == GameState.PLAYING) {
                String role = session.getRedPlayer().equals(me) ? "红方(先手)" : "黑方(后手)";
                GameBroadcaster.sendPopupTo(player,
                        Component.literal("对局开始!你是" + role + "。"),
                        PopupS2CPacket.Severity.INFO, 4);
                ServerPlayer opponent = findOpponent(level, session, me);
                if (opponent != null) {
                    String opRole = session.getRedPlayer().equals(opponent.getUUID())
                            ? "红方(先手)" : "黑方(后手)";
                    GameBroadcaster.sendPopupTo(opponent,
                            Component.literal(player.getName().getString() + "已加入" + opRole + "。"),
                            PopupS2CPacket.Severity.INFO, 4);
                    BoardMessages.sendTo(opponent, session);
                }
                GameBroadcaster.broadcastSync(level, session, pos);
                BoardMessages.sendTo(player, session);
            } else {
                GameBroadcaster.sendPopupTo(player, GameMessages.joinedAsRed(),
                        PopupS2CPacket.Severity.INFO, 4);
            }
            GameBroadcaster.broadcastRoster(level, session, pos);
        } else if (sm.joinAsSpectator(me, pos)) {
            GameBroadcaster.sendPopupTo(player,
                    Component.literal("已加入旁观 — /qisheng leave 可离开。"),
                    PopupS2CPacket.Severity.INFO, 4);
            BoardMessages.sendTo(player, session);
            GameBroadcaster.broadcastRoster(level, session, pos);
        } else {
            GameBroadcaster.sendPopupTo(player,
                    Component.literal("无法加入该对局。"),
                    PopupS2CPacket.Severity.ERROR, 4);
        }
    }

    private static void handleSelect(ServerPlayer player, ServerLevel level,
                                     GameSession session, BlockPos pos, int sq) {
        GameLogic.SelectOutcome out = GameLogic.trySelect(session, player.getUUID(), sq);
        GameBroadcaster.broadcastSync(level, session, pos);
        if (out == GameLogic.SelectOutcome.OK) {
            GameBroadcaster.sendPopupTo(player,
                    Component.literal("已选子,点击目标格走子。"),
                    PopupS2CPacket.Severity.INFO, 2);
        } else {
            GameBroadcaster.sendPopupTo(player, GameMessages.describeSelect(out),
                    PopupS2CPacket.Severity.WARN, 3);
        }
    }

    private static void handleMove(ServerPlayer player, ServerLevel level, GameSession session,
                                   BlockPos pos, int src, int dst) {
        GameLogic.MoveOutcome out = GameLogic.tryMove(session, player.getUUID(), src, dst);
        switch (out) {
            case OK -> {
                GameResult result = session.getResult();
                if (result != GameResult.ONGOING) {
                    GameBroadcaster.broadcastGameOver(level, session, pos, result);
                } else {
                    GameBroadcaster.broadcastSync(level, session, pos);
                }
            }
            default -> {
                GameBroadcaster.broadcastSync(level, session, pos);
                GameBroadcaster.sendPopupTo(player, GameMessages.describeMove(out),
                        PopupS2CPacket.Severity.WARN, 3);
            }
        }
    }

    private static ServerPlayer findOpponent(ServerLevel level, GameSession session, UUID self) {
        UUID other = session.getRedPlayer().equals(self) ? session.getBlackPlayer() : session.getRedPlayer();
        return other == null ? null : (ServerPlayer) level.getPlayerByUUID(other);
    }
}