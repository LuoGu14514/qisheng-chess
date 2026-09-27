package com.qisheng.chess.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.qisheng.chess.network.PopupS2CPacket;
import com.qisheng.chess.pvp.BoardMessages;
import com.qisheng.chess.pvp.BoardMode;
import com.qisheng.chess.pvp.GameBroadcaster;
import com.qisheng.chess.pvp.GameLogic;
import com.qisheng.chess.pvp.GameMessages;
import com.qisheng.chess.pvp.GameResult;
import com.qisheng.chess.pvp.GameSession;
import com.qisheng.chess.pvp.GameState;
import com.qisheng.chess.pvp.SessionManager;
import com.qisheng.chess.util.CChessUtil;

import java.util.UUID;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * /qisheng command registration (common entry).
 * <ul>
 *   <li>/qisheng move &lt;src&gt; &lt;dst&gt; — play a move (CLI fallback to right-click)</li>
 *   <li>/qisheng select &lt;sq&gt; — select a square (CLI fallback)</li>
 *   <li>/qisheng reset — reset current board</li>
 *   <li>/qisheng board — print current board as ASCII</li>
 *   <li>/qisheng status — print compact game state</li>
 *   <li>/qisheng leave — leave the current game / stop spectating</li>
 * </ul>
 *
 * <p>The right-click block flow is the primary input; CLI commands exist as
 * a fallback for testing / power use. All move / select logic is delegated
 * to {@link GameLogic} so the CLI, C2S packet, and right-click paths
 * cannot drift. Roster-changing operations also call
 * {@link GameBroadcaster#broadcastRoster} so the spectator list / player
 * badges update everywhere.
 */
public class ModCommands {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("qisheng")
                        .then(Commands.literal("mode")
                                .then(Commands.literal("pvp")
                                        .executes(ctx -> setMode(ctx, BoardMode.PVP)))
                                .then(Commands.literal("pvc")
                                        .executes(ctx -> setMode(ctx, BoardMode.PVC))))
                        .then(Commands.literal("move")
                                .then(Commands.argument("src", IntegerArgumentType.integer(0, 89))
                                        .then(Commands.argument("dst", IntegerArgumentType.integer(0, 89))
                                                .executes(ctx -> doMove(ctx,
                                                        visualToInternal(IntegerArgumentType.getInteger(ctx, "src")),
                                                        visualToInternal(IntegerArgumentType.getInteger(ctx, "dst")))))))
                        .then(Commands.literal("select")
                                .then(Commands.argument("sq", IntegerArgumentType.integer(0, 89))
                                        .executes(ctx -> doSelect(ctx,
                                                visualToInternal(IntegerArgumentType.getInteger(ctx, "sq"))))))
                        .then(Commands.literal("reset")
                                .executes(ctx -> doReset(ctx)))
                        .then(Commands.literal("board")
                                .executes(ctx -> doBoard(ctx)))
                        .then(Commands.literal("status")
                                .executes(ctx -> doStatus(ctx)))
                        .then(Commands.literal("takeover")
                                .then(Commands.literal("red")
                                        .executes(ctx -> doTakeover(ctx, 0)))
                                .then(Commands.literal("black")
                                        .executes(ctx -> doTakeover(ctx, 1))))
                        .then(Commands.literal("leave")
                                .executes(ctx -> doLeave(ctx)))
        );
    }

    // ---- handlers ----

    private static int setMode(CommandContext<CommandSourceStack> ctx, BoardMode mode) {
        SessionManager.get().setGlobalMode(mode);
        ctx.getSource().sendSystemMessage(Component.literal("[qisheng] Global mode set to " + mode));
        return 1;
    }

    private static int doMove(CommandContext<CommandSourceStack> ctx, int src, int dst) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        BlockPos pos = SessionManager.get().getPlayerGame(player.getUUID());
        if (pos == null) {
            ctx.getSource().sendFailure(Component.literal("[qisheng] You are not in a chess game."));
            return 0;
        }
        ServerLevel level = player.serverLevel();
        GameSession session = SessionManager.get().get(pos);
        if (session == null) return 0;

        GameLogic.MoveOutcome out = GameLogic.tryMove(session, player.getUUID(), src, dst);
        switch (out) {
            case OK -> {
                ctx.getSource().sendSystemMessage(Component.literal(
                        "[qisheng] Moved " + src + " -> " + dst));
                GameResult result = session.getResult();
                if (result != GameResult.ONGOING) {
                    GameBroadcaster.broadcastGameOver(level, session, pos, result);
                } else {
                    GameBroadcaster.broadcastSync(level, session, pos);
                }
                return 1;
            }
            default -> {
                GameBroadcaster.broadcastSync(level, session, pos);
                ctx.getSource().sendFailure(GameMessages.describeMove(out));
                return 0;
            }
        }
    }

    private static int doSelect(CommandContext<CommandSourceStack> ctx, int sq) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        BlockPos pos = SessionManager.get().getPlayerGame(player.getUUID());
        if (pos == null) {
            ctx.getSource().sendFailure(Component.literal("[qisheng] You are not in a chess game."));
            return 0;
        }
        GameSession session = SessionManager.get().get(pos);
        if (session == null) return 0;

        ServerLevel level = player.serverLevel();
        GameLogic.SelectOutcome out = GameLogic.trySelect(session, player.getUUID(), sq);
        GameBroadcaster.broadcastSync(level, session, pos);
        if (out == GameLogic.SelectOutcome.OK) {
            ctx.getSource().sendSystemMessage(Component.literal(
                    "[qisheng] Selected " + sq + " (piece " + session.getChessData().squares[sq]
                            + "), use /qisheng move " + sq + " <dst>"));
            return 1;
        }
        ctx.getSource().sendFailure(GameMessages.describeSelect(out));
        return 0;
    }

    private static int doReset(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        BlockPos pos = SessionManager.get().getPlayerGame(player.getUUID());
        if (pos == null) {
            ctx.getSource().sendFailure(Component.literal("[qisheng] You are not in a chess game."));
            return 0;
        }
        ServerLevel level = player.serverLevel();
        GameSession session = SessionManager.get().get(pos);
        if (session == null) return 0;

        SessionManager sm = SessionManager.get();
        if (session.getRedPlayer() != null) sm.evictPlayer(session.getRedPlayer());
        if (session.getBlackPlayer() != null) sm.evictPlayer(session.getBlackPlayer());

        session.getChessData().fromFen(CChessUtil.INIT);
        session.setSdPlayer(0);
        session.setState(GameState.WAITING);
        session.setResult(GameResult.ONGOING);
        session.setSelectPoint(-1);
        session.setRedPlayer(null);
        session.setBlackPlayer(null);

        GameBroadcaster.broadcastSync(level, session, pos);
        GameBroadcaster.broadcastRoster(level, session, pos);
        GameBroadcaster.sendPopupTo(player,
                Component.literal("棋盘已重置。"),
                PopupS2CPacket.Severity.INFO, 3);
        return 1;
    }

    private static int doBoard(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        BlockPos pos = SessionManager.get().getPlayerGame(player.getUUID());
        if (pos == null) pos = SessionManager.get().getPlayerSpectating(player.getUUID());
        if (pos == null) {
            ctx.getSource().sendSystemMessage(Component.literal("[qisheng] You are not in a chess game or spectating."));
            return 1;
        }
        GameSession session = SessionManager.get().get(pos);
        if (session == null) {
            ctx.getSource().sendSystemMessage(Component.literal("[qisheng] Session not found."));
            return 1;
        }
        ctx.getSource().sendSystemMessage(Component.literal(CChessUtil.boardToAscii(session.getChessData())));
        int role = session.getPlayerRole(player.getUUID());
        String turn = session.getSdPlayer() == 0 ? "红方" : "黑方";
        String yourTurn = (role == session.getSdPlayer()) ? " ← 你走子" : " (等待)";
        ctx.getSource().sendSystemMessage(Component.literal("[qisheng] 轮到: " + turn + yourTurn));
        return 1;
    }

    private static int doStatus(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        BlockPos pos = SessionManager.get().getPlayerGame(player.getUUID());
        if (pos == null) pos = SessionManager.get().getPlayerSpectating(player.getUUID());
        if (pos == null) {
            ctx.getSource().sendSystemMessage(Component.literal("[qisheng] You are not in a chess game or spectating."));
            return 1;
        }
        GameSession session = SessionManager.get().get(pos);
        if (session == null) {
            ctx.getSource().sendSystemMessage(Component.literal("[qisheng] Session not found."));
            return 1;
        }
        int role = session.getPlayerRole(player.getUUID());
        String roleStr = role == 0 ? "红方(先手)" : role == 1 ? "黑方(后手)"
                : (session.isSpectator(player.getUUID()) ? "旁观" : "不在对局");
        String turnStr = session.getSdPlayer() == 0 ? "红方" : "黑方";
        String selStr = session.getSelectPoint() < 0 ? "无" : String.valueOf(session.getSelectPoint());
        String resultStr = session.getResult() == GameResult.ONGOING ? "进行中" : session.getResult().name();
        ctx.getSource().sendSystemMessage(Component.literal(
                "[qisheng] 棋盘 @ " + pos.toShortString()
                        + " | 状态=" + session.getState()
                        + " | 模式=" + session.getMode()
                        + " | 你=" + roleStr
                        + " | 轮到=" + turnStr
                        + " | 选中=" + selStr
                        + " | 结果=" + resultStr
                        + " | 旁观=" + session.getSpectators().size()));
        return 1;
    }

    private static int visualToInternal(int visualSq) {
        int rank = visualSq / 9;
        int file = visualSq % 9;
        return com.qisheng.chess.engine.xqwlight.Position.COORD_XY(
                file + com.qisheng.chess.engine.xqwlight.Position.FILE_LEFT,
                rank + com.qisheng.chess.engine.xqwlight.Position.RANK_TOP);
    }

    private static int doTakeover(CommandContext<CommandSourceStack> ctx, int role)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        UUID me = player.getUUID();
        SessionManager sm = SessionManager.get();

        BlockPos pos = sm.getPlayerSpectating(me);
        if (pos == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "[qisheng] Spectate the board first (right-click it), then use /qisheng takeover."));
            return 0;
        }
        GameSession session = sm.get(pos);
        if (session == null) {
            ctx.getSource().sendFailure(Component.literal("[qisheng] Session gone."));
            return 0;
        }
        String roleName = role == 0 ? "红方" : "黑方";
        boolean ok = sm.takeOver(me, pos, role);
        if (!ok) {
            ctx.getSource().sendFailure(Component.literal(
                    "[qisheng] Cannot take over as " + roleName
                            + ". Either the slot is full, you are already a player, or the game is not in PLAYING state."));
            return 0;
        }
        GameBroadcaster.sendPopupTo(player,
                Component.literal("你已接手" + roleName + "。"),
                PopupS2CPacket.Severity.INFO, 4);
        UUID other = role == 0 ? session.getBlackPlayer() : session.getRedPlayer();
        if (other != null) {
            ServerPlayer otherPlayer = (ServerPlayer) player.serverLevel().getPlayerByUUID(other);
            if (otherPlayer != null) {
                GameBroadcaster.sendPopupTo(otherPlayer,
                        Component.literal(player.getName().getString() + " 已接手" + roleName + "。"),
                        PopupS2CPacket.Severity.INFO, 4);
            }
        }
        GameBroadcaster.broadcastSync(player.serverLevel(), session, pos);
        GameBroadcaster.broadcastRoster(player.serverLevel(), session, pos);
        BoardMessages.sendTo(player, session);
        return 1;
    }

    private static int doLeave(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        SessionManager sm = SessionManager.get();
        BlockPos pos = sm.getPlayerGame(player.getUUID());

        // Spectator path first.
        if (pos == null) {
            BlockPos specPos = sm.getPlayerSpectating(player.getUUID());
            if (specPos != null) {
                GameSession s = sm.get(specPos);
                if (s != null) s.removeSpectator(player.getUUID());
                sm.removePlayerSpectating(player.getUUID());
                GameBroadcaster.sendPopupTo(player,
                        Component.literal("已停止旁观。"),
                        PopupS2CPacket.Severity.INFO, 3);
                if (s != null) GameBroadcaster.broadcastRoster(player.serverLevel(), s, specPos);
                return 1;
            }
            ctx.getSource().sendSystemMessage(Component.literal("[qisheng] You are not in a chess game or spectating."));
            return 1;
        }

        GameSession session = sm.get(pos);
        if (session == null) return 1;

        sm.evictPlayer(player.getUUID());

        if (session.getRedPlayer() == null && session.getBlackPlayer() == null) {
            session.getChessData().fromFen(CChessUtil.INIT);
            session.setSdPlayer(0);
            session.setState(GameState.WAITING);
            session.setResult(GameResult.ONGOING);
            session.setSelectPoint(-1);
            GameBroadcaster.broadcastSync(player.serverLevel(), session, pos);
            GameBroadcaster.broadcastRoster(player.serverLevel(), session, pos);
            GameBroadcaster.sendPopupTo(player,
                    Component.literal("你已离场 — 棋盘已清空。"),
                    PopupS2CPacket.Severity.INFO, 3);
        } else {
            GameBroadcaster.broadcastRoster(player.serverLevel(), session, pos);
            GameBroadcaster.sendPopupTo(player,
                    Component.literal("你已离场 — 对方可继续或也离场。"),
                    PopupS2CPacket.Severity.INFO, 3);
        }
        return 1;
    }
}