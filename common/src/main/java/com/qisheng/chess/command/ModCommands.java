package com.qisheng.chess.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.qisheng.chess.engine.xqwlight.Position;
import com.qisheng.chess.network.PopupS2CPacket;
import com.qisheng.chess.pvp.BoardKey;
import com.qisheng.chess.pvp.BoardMode;
import com.qisheng.chess.pvp.GameBroadcaster;
import com.qisheng.chess.pvp.GameLogic;
import com.qisheng.chess.pvp.GameMessages;
import com.qisheng.chess.pvp.GameResult;
import com.qisheng.chess.pvp.GameSession;
import com.qisheng.chess.pvp.GameState;
import com.qisheng.chess.pvp.SessionManager;
import com.qisheng.chess.util.CChessUtil;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * {@code /qisheng} 命令注册(common 入口)。
 * <ul>
 *   <li>{@code /qisheng move <src> <dst>} — 走子(右键的 CLI 后备)</li>
 *   <li>{@code /qisheng select <sq>} — 选子(CLI 后备)</li>
 *   <li>{@code /qisheng reset} — 重置当前棋盘</li>
 *   <li>{@code /qisheng board} — 以 ASCII 打印当前棋盘(唯一还会走聊天的盘面输出)</li>
 *   <li>{@code /qisheng status} — 打印紧凑对局状态</li>
 *   <li>{@code /qisheng leave} — 离场 / 停止旁观</li>
 *   <li>{@code /qisheng takeover red|black} — 接手空位</li>
 *   <li>{@code /qisheng mode pvp|pvc} — <b>需要权限等级 2</b>:改的是全局单例</li>
 *   <li>{@code /qisheng purge} — <b>需要权限等级 2</b>:清空所有会话(管理员用)</li>
 * </ul>
 *
 * <p><b>坐标约定</b>:{@code <sq>} 与 {@code /qisheng board} 打印出来的行号一致 ——
 * 0..8 = 最下面一行(标 0),72..80 = 最上面一行(标 9),{@code sq % 9} 是 a..i 列。
 * 这与 GUI 内部的方格编码不同,{@link #visualToInternal(int)} 负责换算。
 *
 * <p>右键方块是主要输入方式;CLI 是测试/高级用法。所有走子/选子逻辑都委托给
 * {@link GameLogic},因此 CLI、C2S 包、右键三条路径不会走偏。改变名单的操作
 * 还会调用 {@link GameBroadcaster#broadcastRoster},让旁观列表/玩家徽章处处同步。
 */
public class ModCommands {

    /** 权限等级 2 = 管理员(op)。破坏性/全局性的子命令必须要求它。 */
    private static final int OP_LEVEL = 2;

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("qisheng")
                        .then(Commands.literal("mode")
                                .requires(src -> src.hasPermission(OP_LEVEL))
                                .then(Commands.literal("pvp")
                                        .executes(ctx -> setMode(ctx, BoardMode.PVP)))
                                .then(Commands.literal("pvc")
                                        .executes(ctx -> setMode(ctx, BoardMode.PVC))))
                        .then(Commands.literal("purge")
                                .requires(src -> src.hasPermission(OP_LEVEL))
                                .executes(ModCommands::doPurge))
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

    // ---- helpers ----

    /** 命令解析出来的"这一局":会话 + 它所在的维度 + 方块坐标。 */
    private record Target(GameSession session, ServerLevel level, BlockPos pos) {}

    /**
     * Resolve the board the player is playing at, in the player's own dimension,
     * falling back to the player's current level if the board's dimension is gone.
     */
    private static Target resolvePlaying(ServerPlayer player, boolean allowSpectating) {
        SessionManager sm = SessionManager.get();
        UUID id = player.getUUID();
        BoardKey key = sm.getPlayerGame(id);
        if (key == null && allowSpectating) key = sm.getPlayerSpectating(id);
        if (key == null) return null;
        GameSession session = sm.get(key);
        if (session == null) return null;
        ServerLevel level = SessionManager.resolve(player.getServer(), key, player.serverLevel());
        if (level == null) return null;
        return new Target(session, level, key.pos());
    }

    // ---- handlers ----

    private static int setMode(CommandContext<CommandSourceStack> ctx, BoardMode mode) {
        SessionManager.get().setGlobalMode(mode);
        ctx.getSource().sendSystemMessage(Component.translatable(
                "qisheng.chess.cmd.mode.changed",
                Component.translatable(mode == BoardMode.PVC
                        ? "qisheng.chess.cmd.mode.label_pvc"
                        : "qisheng.chess.cmd.mode.label_pvp")));
        // SessionManager stamps globalMode in newSession(), so this only affects
        // boards created (or reset) after this point. Say so — changing the mode
        // under a game in progress would change the rules mid-match.
        ctx.getSource().sendSystemMessage(Component.translatable(mode == BoardMode.PVC
                ? "qisheng.chess.mode.pvc"
                : "qisheng.chess.mode.pvp"));
        return 1;
    }

    /** 管理员:丢弃全部会话(服务器停止钩子也调用同一段逻辑)。 */
    private static int doPurge(CommandContext<CommandSourceStack> ctx) {
        int before = SessionManager.get().sessionCount();
        SessionManager.get().resetAll();
        ctx.getSource().sendSystemMessage(Component.literal("[qisheng] 已清空 " + before + " 个棋盘会话。"));
        return 1;
    }

    private static int doMove(CommandContext<CommandSourceStack> ctx, int src, int dst) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Target t = resolvePlaying(player, false);
        if (t == null) {
            ctx.getSource().sendFailure(Component.translatable("qisheng.chess.cmd.not_in_game"));
            return 0;
        }
        GameLogic.MoveOutcome out = GameLogic.tryMove(t.session(), player.getUUID(), src, dst);
        switch (out) {
            case OK -> {
                ctx.getSource().sendSystemMessage(Component.literal(
                        "[qisheng] 已走子 " + src + " -> " + dst));
                GameResult result = t.session().getResult();
                if (result != GameResult.ONGOING) {
                    GameBroadcaster.broadcastGameOver(t.level(), t.session(), t.pos(), result);
                } else {
                    GameBroadcaster.broadcastSync(t.level(), t.session(), t.pos());
                }
                return 1;
            }
            default -> {
                GameBroadcaster.broadcastSync(t.level(), t.session(), t.pos());
                ctx.getSource().sendFailure(GameMessages.describeMove(out));
                return 0;
            }
        }
    }

    private static int doSelect(CommandContext<CommandSourceStack> ctx, int sq) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Target t = resolvePlaying(player, false);
        if (t == null) {
            ctx.getSource().sendFailure(Component.translatable("qisheng.chess.cmd.not_in_game"));
            return 0;
        }
        GameLogic.SelectOutcome out = GameLogic.trySelect(t.session(), player.getUUID(), sq);
        GameBroadcaster.broadcastSync(t.level(), t.session(), t.pos());
        if (out == GameLogic.SelectOutcome.OK) {
            byte pc = t.session().getChessData().squares[sq];
            ctx.getSource().sendSystemMessage(Component.literal(
                    "[qisheng] 已选中方格 " + sq + "(棋子字节 " + pc
                            + "),用 /qisheng move <src> <dst> 走子。"));
            return 1;
        }
        ctx.getSource().sendFailure(GameMessages.describeSelect(out));
        return 0;
    }

    private static int doReset(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        SessionManager sm = SessionManager.get();

        // 优先重置"自己坐着的那一局";否则(旁观者/管理员)需要权限。
        BoardKey key = sm.getPlayerGame(player.getUUID());
        if (key == null) {
            key = sm.getPlayerSpectating(player.getUUID());
            if (key == null) {
                ctx.getSource().sendFailure(Component.translatable("qisheng.chess.cmd.not_in_game"));
                return 0;
            }
        }
        GameSession session = sm.get(key);
        if (session == null) {
            ctx.getSource().sendFailure(Component.translatable("qisheng.chess.cmd.session_gone"));
            return 0;
        }
        ServerLevel level = SessionManager.resolve(player.getServer(), key, player.serverLevel());
        if (level == null) return 0;
        BlockPos pos = key.pos();

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
                Component.translatable("qisheng.chess.cmd.reset.done"),
                PopupS2CPacket.Severity.INFO, 3);
        return 1;
    }

    private static int doBoard(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Target t = resolvePlaying(player, true);
        if (t == null) {
            ctx.getSource().sendSystemMessage(Component.translatable("qisheng.chess.cmd.not_in_game_idle"));
            return 1;
        }
        ctx.getSource().sendSystemMessage(Component.literal(CChessUtil.boardToAscii(t.session().getChessData())));
        int role = t.session().getPlayerRole(player.getUUID());
        Component turn = Component.translatable(t.session().getSdPlayer() == 0
                ? "qisheng.chess.role.red" : "qisheng.chess.role.black");
        Component yourTurn = (role == t.session().getSdPlayer())
                ? Component.translatable("qisheng.chess.cmd.board.your_move")
                : Component.translatable("qisheng.chess.cmd.board.waiting");
        ctx.getSource().sendSystemMessage(Component.translatable(
                "qisheng.chess.cmd.board.turn_line", turn, yourTurn));
        return 1;
    }

    private static int doStatus(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Target t = resolvePlaying(player, true);
        if (t == null) {
            ctx.getSource().sendSystemMessage(Component.translatable("qisheng.chess.cmd.not_in_game_idle"));
            return 1;
        }
        GameSession session = t.session();
        int role = session.getPlayerRole(player.getUUID());
        Component roleStr = role == 0
                ? Component.translatable("qisheng.chess.role.red_first")
                : role == 1
                ? Component.translatable("qisheng.chess.role.black_second")
                : (session.isSpectator(player.getUUID())
                        ? Component.translatable("qisheng.chess.role.spectator")
                        : Component.translatable("qisheng.chess.cmd.status.not_in_game"));
        Component turnStr = Component.translatable(session.getSdPlayer() == 0
                ? "qisheng.chess.role.red" : "qisheng.chess.role.black");
        Component selStr = session.getSelectPoint() < 0
                ? Component.translatable("qisheng.chess.cmd.status.none")
                : Component.literal(String.valueOf(session.getSelectPoint()));
        Component resultStr = session.getResult() == GameResult.ONGOING
                ? Component.translatable("qisheng.chess.cmd.status.ongoing")
                : Component.literal(session.getResult().name());
        Component stateLabel = Component.literal(session.getState().name());
        Component modeLabel = Component.literal(session.getMode().name());
        Component boardKeyStr = Component.literal(BoardKey.of(t.level(), t.pos()).describe());
        Component spectatorsStr = Component.literal(String.valueOf(session.getSpectators().size()));
        ctx.getSource().sendSystemMessage(Component.translatable(
                "qisheng.chess.cmd.status.line",
                boardKeyStr, stateLabel, modeLabel, roleStr, turnStr, selStr, resultStr, spectatorsStr));
        return 1;
    }

    /**
     * Convert the user-facing square index (the one {@code /qisheng board} labels,
     * 0 = bottom row, 9 = top row) into the engine's internal square code.
     *
     * <p>{@code boardToAscii} prints internal rank 3 first with the label
     * {@code 9 - displayIdx}, i.e. the top row is labelled 9 and the bottom row 0.
     * The previous version mapped the first digit straight onto the internal rank,
     * so {@code /qisheng select 0} selected the row the board prints as 9 — the
     * numbers in the command did not match the numbers on screen.
     */
    private static int visualToInternal(int visualSq) {
        int labelRow = visualSq / 9;            // 0 = bottom, 9 = top (as printed)
        int file = visualSq % 9;                // 0 = a .. 8 = i
        int displayIdx = 9 - labelRow;          // 0 = top .. 9 = bottom
        return Position.COORD_XY(file + Position.FILE_LEFT, displayIdx + Position.RANK_TOP);
    }

    private static int doTakeover(CommandContext<CommandSourceStack> ctx, int role)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        UUID me = player.getUUID();
        SessionManager sm = SessionManager.get();

        BoardKey key = sm.getPlayerSpectating(me);
        if (key == null) {
            ctx.getSource().sendFailure(Component.translatable(
                    "qisheng.chess.cmd.takeover.not_spectating"));
            return 0;
        }
        GameSession session = sm.get(key);
        if (session == null) {
            ctx.getSource().sendFailure(Component.translatable("qisheng.chess.cmd.session_gone"));
            return 0;
        }
        ServerLevel level = SessionManager.resolve(player.getServer(), key, player.serverLevel());
        if (level == null) return 0;
        BlockPos pos = key.pos();

        Component roleName = Component.translatable(role == 0
                ? "qisheng.chess.role.red"
                : "qisheng.chess.role.black");
        if (!sm.takeOver(me, key, role)) {
            ctx.getSource().sendFailure(Component.translatable(
                    "qisheng.chess.cmd.takeover.failed", roleName));
            return 0;
        }
        GameBroadcaster.sendPopupTo(player,
                Component.translatable("qisheng.chess.cmd.takeover.you_took_over", roleName),
                PopupS2CPacket.Severity.INFO, 4);
        UUID other = role == 0 ? session.getBlackPlayer() : session.getRedPlayer();
        if (other != null) {
            ServerPlayer otherPlayer = GameBroadcaster.findPlayer(level, other);
            if (otherPlayer != null) {
                GameBroadcaster.sendPopupTo(otherPlayer,
                        Component.translatable("qisheng.chess.cmd.takeover.other_took_over",
                                player.getName(), roleName),
                        PopupS2CPacket.Severity.INFO, 4);
            }
        }
        GameBroadcaster.broadcastSync(level, session, pos);
        GameBroadcaster.broadcastRoster(level, session, pos);
        return 1;
    }

    private static int doLeave(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        SessionManager sm = SessionManager.get();
        BoardKey key = sm.getPlayerGame(player.getUUID());

        // Spectator path first.
        if (key == null) {
            BoardKey specKey = sm.getPlayerSpectating(player.getUUID());
            if (specKey != null) {
                GameSession s = sm.get(specKey);
                sm.removePlayerSpectating(player.getUUID());
                if (s != null) s.removeSpectator(player.getUUID());
                GameBroadcaster.sendPopupTo(player,
                        Component.translatable("qisheng.chess.cmd.leave.spectator_stopped"),
                        PopupS2CPacket.Severity.INFO, 3);
                if (s != null) {
                    ServerLevel lvl = SessionManager.resolve(player.getServer(), specKey, player.serverLevel());
                    if (lvl != null) GameBroadcaster.broadcastRoster(lvl, s, specKey.pos());
                }
                return 1;
            }
            ctx.getSource().sendSystemMessage(Component.translatable("qisheng.chess.cmd.not_in_game_idle"));
            return 1;
        }

        GameSession session = sm.get(key);
        if (session == null) return 1;
        ServerLevel level = SessionManager.resolve(player.getServer(), key, player.serverLevel());
        if (level == null) return 1;
        BlockPos pos = key.pos();

        // Leaving a live game forfeits it — otherwise the opponent is stuck
        // staring at a board whose other seat is a phantom.
        boolean wasPlaying = session.getState() == GameState.PLAYING;
        UUID survivorId = null;
        if (wasPlaying) {
            boolean leaverIsRed = player.getUUID().equals(session.getRedPlayer());
            survivorId = leaverIsRed ? session.getBlackPlayer() : session.getRedPlayer();
            GameResult result = leaverIsRed ? GameResult.BLACK_WIN : GameResult.RED_WIN;
            session.setResult(result);
            session.setState(GameState.FINISHED);
            if (survivorId != null) {
                ServerPlayer survivor = GameBroadcaster.findPlayer(level, survivorId);
                if (survivor != null) {
                    GameBroadcaster.sendPopupTo(survivor,
                            Component.translatable("qisheng.chess.cmd.leave.opponent_won",
                                    player.getName()),
                            PopupS2CPacket.Severity.INFO, 5);
                }
            }
            GameBroadcaster.broadcastGameOver(level, session, pos, result);
        }

        sm.evictPlayer(player.getUUID());
        session.setRedPlayer(null);
        session.setBlackPlayer(null);
        session.setSdPlayer(0);
        session.setSelectPoint(-1);
        session.getChessData().fromFen(CChessUtil.INIT);
        session.setState(GameState.WAITING);
        session.setResult(GameResult.ONGOING);
        if (survivorId != null) sm.evictPlayer(survivorId);

        GameBroadcaster.broadcastSync(level, session, pos);
        GameBroadcaster.broadcastRoster(level, session, pos);
        GameBroadcaster.sendPopupTo(player,
                Component.translatable(wasPlaying
                        ? "qisheng.chess.cmd.leave.was_playing"
                        : "qisheng.chess.cmd.leave.idle"),
                PopupS2CPacket.Severity.INFO, 3);
        return 1;
    }
}
