package com.qisheng.chess.network;

import com.qisheng.chess.block.CChessBoardBlock;
import com.qisheng.chess.pvp.BoardKey;
import com.qisheng.chess.pvp.BoardMode;
import com.qisheng.chess.pvp.BoardMessages;
import com.qisheng.chess.pvp.GameBroadcaster;
import com.qisheng.chess.pvp.GameLogic;
import com.qisheng.chess.pvp.GameMessages;
import com.qisheng.chess.pvp.GameResult;
import com.qisheng.chess.pvp.GameSession;
import com.qisheng.chess.pvp.GameState;
import com.qisheng.chess.pvp.SessionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * C2S packet for join / select / move / place actions.
 *
 * <p>All user prompts flow through {@link GameBroadcaster#sendPopupTo}
 * so the player sees them as centred in-GUI overlays instead of chat
 * lines. Roster-changing actions also call
 * {@link GameBroadcaster#broadcastRoster}.
 *
 * <p>Every user-visible string lives in {@code assets/.../lang/} so the
 * client locale drives the language — see {@link GameMessages} for the
 * select/move/join phrasing.
 *
 * <p><b>这个包里的坐标完全来自客户端</b>,所以每一个动作都要先过三关:
 * <ol>
 *   <li>动作编号必须是 {@link #ACTION_JOIN} / {@link #ACTION_SELECT} /
 *       {@link #ACTION_MOVE} / {@link #ACTION_PASS} / {@link #ACTION_PLACE}</li>
 *   <li>{@code pos} 处必须真的是 {@link CChessBoardBlock}(否则静默丢弃,
 *       不能让恶意客户端拿任意 BlockPos 去操作别人的棋盘)</li>
 *   <li>玩家必须站在棋盘旁({@link SessionManager#isPlayerNear})</li>
 * </ol>
 * 载荷长度也要先查再读:截断/畸形的包不允许把异常抛出处理器。
 *
 * <p>动作路由:
 * <ul>
 *   <li>{@code ACTION_MOVE}:象棋/国际象棋的 (src, dst) 走子</li>
 *   <li>{@code ACTION_PLACE}:五子棋/围棋的单格下子(载荷只有 1 个 short)</li>
 *   <li>{@code ACTION_PASS}:围棋的虚手(无载荷)</li>
 * </ul>
 * {@code ACTION_MOVE} 不再被五子棋/围棋借用(src == dst);这两个变种只走
 * {@code ACTION_PLACE}。这样棋盘的"下子"语义和数据载荷都跟象棋走子分开,
 * 未来加 Othello 这类纯下子变种时也只需这一个动作号。
 */
public class ChessInteractC2SPacket {

    private static final Logger LOG = LoggerFactory.getLogger("qisheng_chess");

    public static final int ACTION_JOIN = 0;
    public static final int ACTION_SELECT = 1;
    public static final int ACTION_MOVE = 2;
    public static final int ACTION_PASS = 3;
    public static final int ACTION_PLACE = 4;

    public static void receive(FriendlyByteBuf buf, ServerPlayer player) {
        if (player == null) return;

        ServerLevel level = player.serverLevel();
        BlockPos pos = buf.readBlockPos();
        int action = buf.readByte();

        // 未知动作:记一条日志就走,不回包(免得把服务端当探针用)。
        if (action != ACTION_JOIN && action != ACTION_SELECT && action != ACTION_MOVE
                && action != ACTION_PASS && action != ACTION_PLACE) {
            LOG.warn("[qisheng] Player {} sent unknown board interaction action {} @ {}",
                    player.getName().getString(), action, pos);
            return;
        }

        // 坐标来自客户端:先确认这里确实是棋盘方块,否则静默丢弃。
        if (!(level.getBlockState(pos).getBlock() instanceof CChessBoardBlock)) return;

        SessionManager sm = SessionManager.get();
        BoardKey key = BoardKey.of(level, pos);
        // 棋盘可能在别的维度,广播一律用棋盘所在的世界。
        ServerLevel boardLevel = SessionManager.resolve(player.getServer(), key, level);
        GameSession session = sm.get(key);
        if (session == null) {
            GameBroadcaster.sendPopupTo(player,
                    Component.translatable("qisheng.chess.server.no_session_at_pos"),
                    PopupS2CPacket.Severity.ERROR, 4);
            return;
        }

        // 三种动作都要求玩家确实站在棋盘旁(以前只有 JOIN 查)。
        if (!sm.isPlayerNear(level, player.getUUID(), pos)) {
            GameBroadcaster.sendPopupTo(player,
                    Component.translatable("qisheng.chess.server.approach_first"),
                    PopupS2CPacket.Severity.WARN, 3);
            return;
        }

        switch (action) {
            case ACTION_JOIN -> handleJoin(player, boardLevel, session, key, pos);
            case ACTION_SELECT -> {
                // 长度不足的包直接丢弃,绝不能让 readShort 抛到处理器之外。
                if (buf.readableBytes() < Short.BYTES) return;
                handleSelect(player, boardLevel, session, pos, buf.readShort());
            }
            case ACTION_MOVE -> {
                if (buf.readableBytes() < 2 * Short.BYTES) return;
                handleMove(player, boardLevel, session, pos, buf.readShort(), buf.readShort());
            }
            case ACTION_PASS -> handlePass(player, boardLevel, session, pos);
            case ACTION_PLACE -> {
                // Placement payload is a single square index (no src); gomoku and
                // go use this instead of ACTION_MOVE with src == dst.
                if (buf.readableBytes() < Short.BYTES) return;
                handlePlace(player, boardLevel, session, pos, buf.readShort());
            }
        }
    }

    private static void handleJoin(ServerPlayer player, ServerLevel boardLevel,
                                   GameSession session, BoardKey key, BlockPos pos) {
        SessionManager sm = SessionManager.get();
        UUID me = player.getUUID();
        if (session.containsPlayer(me)) {
            if (session.getState() == GameState.WAITING) {
                GameBroadcaster.sendPopupTo(player, GameMessages.alreadyJoinedWaiting(),
                        PopupS2CPacket.Severity.INFO, 3);
            } else if (session.getState() == GameState.PLAYING) {
                // me.equals(...) 的顺序不能反:座位可能为空,getRedPlayer() 会是 null。
                Component role = roleLabel(session, me);
                Component turn = turnLabel(session);
                GameBroadcaster.sendPopupTo(player,
                        GameMessages.alreadyJoinedPlaying(role, turn),
                        PopupS2CPacket.Severity.INFO, 4);
            } else {
                GameBroadcaster.sendPopupTo(player,
                        Component.translatable("qisheng.chess.server.game_finished_break"),
                        PopupS2CPacket.Severity.INFO, 4);
            }
            return;
        }
        boolean ok = sm.joinGame(me, key);
        if (ok) {
            if (session.getState() == GameState.PLAYING) {
                if (session.getMode() == BoardMode.PVC) {
                    // The empty seat belongs to the computer, so there is no
                    // opponent to wait for and none to notify (findOpponent
                    // returns null below and the notify block is skipped).
                    GameBroadcaster.sendPopupTo(player,
                            Component.translatable("qisheng.chess.pvc.started"),
                            PopupS2CPacket.Severity.INFO, 4);
                } else {
                    Component role = roleLabel(session, me);
                    GameBroadcaster.sendPopupTo(player,
                            Component.translatable("qisheng.chess.server.pvp_started_you", role),
                            PopupS2CPacket.Severity.INFO, 4);
                }
                ServerPlayer opponent = findOpponent(boardLevel, session, me);
                if (opponent != null) {
                    Component opRole = roleLabel(session, opponent.getUUID());
                    GameBroadcaster.sendPopupTo(opponent,
                            Component.translatable("qisheng.chess.server.opponent_joined_pvp",
                                    opponent.getName(), opRole),
                            PopupS2CPacket.Severity.INFO, 4);
                    BoardMessages.sendTo(opponent, session);
                }
                GameBroadcaster.broadcastSync(boardLevel, session, pos);
                BoardMessages.sendTo(player, session);
            } else {
                GameBroadcaster.sendPopupTo(player, GameMessages.joinedAsRed(),
                        PopupS2CPacket.Severity.INFO, 4);
            }
            GameBroadcaster.broadcastRoster(boardLevel, session, pos);
        } else if (sm.joinAsSpectator(me, key)) {
            GameBroadcaster.sendPopupTo(player,
                    Component.translatable("qisheng.chess.server.joined_spectator"),
                    PopupS2CPacket.Severity.INFO, 4);
            BoardMessages.sendTo(player, session);
            GameBroadcaster.broadcastRoster(boardLevel, session, pos);
        } else {
            GameBroadcaster.sendPopupTo(player,
                    Component.translatable("qisheng.chess.server.cannot_join"),
                    PopupS2CPacket.Severity.ERROR, 4);
        }
    }

    private static void handleSelect(ServerPlayer player, ServerLevel boardLevel,
                                     GameSession session, BlockPos pos, int sq) {
        GameLogic.SelectOutcome out = GameLogic.trySelect(session, player.getUUID(), sq);
        GameBroadcaster.broadcastSync(boardLevel, session, pos);
        if (out == GameLogic.SelectOutcome.OK) {
            GameBroadcaster.sendPopupTo(player,
                    Component.translatable("qisheng.chess.server.selected_click_target"),
                    PopupS2CPacket.Severity.INFO, 2);
        } else {
            GameBroadcaster.sendPopupTo(player, GameMessages.describeSelect(out),
                    PopupS2CPacket.Severity.WARN, 3);
        }
    }

    private static void handleMove(ServerPlayer player, ServerLevel boardLevel, GameSession session,
                                   BlockPos pos, int src, int dst) {
        GameLogic.MoveOutcome out = GameLogic.tryMove(session, player.getUUID(), src, dst);
        switch (out) {
            case OK -> {
                GameResult result = session.getResult();
                if (result != GameResult.ONGOING) {
                    GameBroadcaster.broadcastGameOver(boardLevel, session, pos, result);
                } else {
                    GameBroadcaster.broadcastSync(boardLevel, session, pos);
                }
            }
            default -> {
                GameBroadcaster.broadcastSync(boardLevel, session, pos);
                GameBroadcaster.sendPopupTo(player, GameMessages.describeMove(out),
                        PopupS2CPacket.Severity.WARN, 3);
            }
        }
    }

    private static void handlePass(ServerPlayer player, ServerLevel boardLevel, GameSession session, BlockPos pos) {
        GameLogic.MoveOutcome out = GameLogic.tryPass(session, player.getUUID());
        if (out == GameLogic.MoveOutcome.OK) {
            GameResult result = session.getResult();
            if (result != GameResult.ONGOING) {
                GameBroadcaster.broadcastGameOver(boardLevel, session, pos, result);
            } else {
                GameBroadcaster.broadcastSync(boardLevel, session, pos);
            }
        } else {
            GameBroadcaster.sendPopupTo(player, GameMessages.describeMove(out),
                    PopupS2CPacket.Severity.WARN, 3);
        }
    }

    private static void handlePlace(ServerPlayer player, ServerLevel boardLevel, GameSession session,
                                    BlockPos pos, int sq) {
        GameLogic.MoveOutcome out = GameLogic.tryPlace(session, player.getUUID(), sq);
        switch (out) {
            case OK -> {
                GameResult result = session.getResult();
                if (result != GameResult.ONGOING) {
                    GameBroadcaster.broadcastGameOver(boardLevel, session, pos, result);
                } else {
                    GameBroadcaster.broadcastSync(boardLevel, session, pos);
                }
            }
            default -> {
                GameBroadcaster.broadcastSync(boardLevel, session, pos);
                GameBroadcaster.sendPopupTo(player, GameMessages.describeMove(out),
                        PopupS2CPacket.Severity.WARN, 3);
            }
        }
    }

    private static ServerPlayer findOpponent(ServerLevel boardLevel, GameSession session, UUID self) {
        // self.equals(...) 的顺序不能反:座位可能为空,getRedPlayer() 会是 null。
        UUID other = self.equals(session.getRedPlayer()) ? session.getBlackPlayer() : session.getRedPlayer();
        // 对手可能站在别的维度,用跨维度查找。
        return other == null ? null : GameBroadcaster.findPlayer(boardLevel, other);
    }

    /** "红方(先手)" / "黑方(后手)" — which seat the given player currently occupies. */
    private static Component roleLabel(GameSession session, UUID player) {
        return Component.translatable(player.equals(session.getRedPlayer())
                ? "qisheng.chess.role.red_first"
                : "qisheng.chess.role.black_second");
    }

    /** "红方回合" / "黑方回合" — whose turn it is right now. */
    private static Component turnLabel(GameSession session) {
        return Component.translatable(session.getSdPlayer() == 0
                ? "qisheng.chess.turn.red"
                : "qisheng.chess.turn.black");
    }
}
