package com.qisheng.chess.block;

import com.qisheng.chess.pvp.BoardKey;
import com.qisheng.chess.pvp.BoardMode;
import com.qisheng.chess.pvp.GameBroadcaster;
import com.qisheng.chess.pvp.GameMessages;
import com.qisheng.chess.pvp.GameSession;
import com.qisheng.chess.pvp.GameState;
import com.qisheng.chess.pvp.PvcController;
import com.qisheng.chess.pvp.SessionManager;
import com.qisheng.chess.tileentity.CChessTileEntity;
import com.qisheng.chess.util.CChessUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 单方块中国象棋棋盘。
 *
 * 交互流程:
 *  1. 玩家右键棋盘 → 服务端注册/找到 session → 玩家加入(或变成旁观)
 *  2. 完成后立即发送 CHESS_OPEN_SCREEN + CHESS_PLAYER_INFO,客户端打开 GUI
 *  3. GUI 内点击选子/落子,不再走 in-world click
 *
 * 提示渠道:右键棋盘方块的所有用户反馈走 {@code ServerPlayer#sendSystemMessage}
 * (聊天栏)。其余场景(对战内事件、求和、认输等)仍走 GameBroadcaster 的
 * popup / broadcast 路径。GUI 打开后盘面状态由 GUI 呈现,这里**不再**往聊天
 * 推整盘 ASCII —— 那是旧的刷屏来源,现在只有 {@code /qisheng board} 会输出。
 *
 * <p>类上的 {@code @SuppressWarnings("deprecation")}:Mojang 在 1.20.1 的
 * {@code BlockBehaviour} 里把 {@code use} / {@code onPlace} / {@code onRemove}
 * 标成了 {@code @Deprecated},但覆写它们仍是实现方块交互的**唯一**途径,没有
 * 替代 API。这里显式压掉,免得真正的废弃警告被这三个淹掉。
 *
 * 服务端维护:
 *  - onPlace → SessionManager.getOrCreate 注册会话
 *  - onRemove → SessionManager.remove 清理会话(仅当新方块不再是同类棋盘)
 *
 * Roster 通知:任何 roster 变更(join / takeOver / joinAsSpectator / 断开)
 * 后都调用 {@link GameBroadcaster#broadcastRoster} 让所有 GUI 客户端更新
 * 玩家名 + 旁观名单。
 */
@SuppressWarnings("deprecation")
public class CChessBoardBlock extends BaseEntityBlock {

    public CChessBoardBlock(Properties properties) {
        super(properties);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CChessTileEntity(pos, state);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos,
                                  Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.sidedSuccess(level.isClientSide());
        if (!(level instanceof ServerLevel serverLevel)) return InteractionResult.PASS;
        if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;

        SessionManager sm = SessionManager.get();
        if (!sm.isPlayerNear(serverLevel, serverPlayer.getUUID(), pos)) {
            serverPlayer.sendSystemMessage(Component.literal("请走近棋盘(5 格内)后再右键。"));
            return InteractionResult.FAIL;
        }

        BoardKey key = BoardKey.of(serverLevel, pos);
        // ensureSession() restores the game from the tile entity's saved NBT when
        // the server has been restarted since it was played. onPlace() only runs
        // when the block is *placed*, so without this a board in a reloaded world
        // had no session and every right-click answered "该棋盘无效".
        CChessTileEntity boardEntity = level.getBlockEntity(pos) instanceof CChessTileEntity be ? be : null;
        GameSession session = boardEntity != null ? boardEntity.ensureSession() : sm.get(key);
        if (session == null) {
            serverPlayer.sendSystemMessage(Component.literal("该棋盘无效,请重新放置。"));
            return InteractionResult.FAIL;
        }

        GameState gameState = session.getState();
        UUID me = serverPlayer.getUUID();

        // 终局后的棋盘曾永久停在 FINISHED:唯一的出路是 /qisheng reset。
        // 右键一块已经结束的棋盘 = "在这里开新局" —— 复位后走正常的加入流程。
        if (gameState == GameState.FINISHED) {
            resetFinishedBoard(sm, session, key);
            gameState = session.getState();
        }

        boolean alreadyIn = session.containsPlayer(me);
        boolean isSpectator = session.isSpectator(me);

        if (!alreadyIn && !isSpectator) {
            if (gameState == GameState.WAITING) {
                if (sm.joinGame(me, key)) {
                    if (session.getState() == GameState.PLAYING) {
                        boolean iAmRed = me.equals(session.getRedPlayer());
                        String myRole = iAmRed ? "红方" : "黑方";
                        if (session.getMode() == BoardMode.PVC) {
                            // No second human joined — the empty seat is the computer's.
                            serverPlayer.sendSystemMessage(
                                    Component.translatable("qisheng.chess.pvc.started"));
                        } else {
                            serverPlayer.sendSystemMessage(Component.literal("对局开始!你是" + myRole + "。"));
                        }
                        UUID otherId = iAmRed ? session.getBlackPlayer() : session.getRedPlayer();
                        ServerPlayer other = GameBroadcaster.findPlayer(serverLevel, otherId);
                        if (other != null) {
                            other.sendSystemMessage(Component.literal(serverPlayer.getName().getString()
                                    + " 已加入" + myRole + "。"));
                        }
                        GameBroadcaster.broadcastSync(serverLevel, session, pos);
                    } else {
                        serverPlayer.sendSystemMessage(GameMessages.joinedAsRed());
                    }
                    GameBroadcaster.broadcastRoster(serverLevel, session, pos);
                } else {
                    serverPlayer.sendSystemMessage(Component.literal("无法加入该对局。"));
                }
            } else if (gameState == GameState.PLAYING) {
                // Empty slot? Prefer takeover so the game can keep going.
                boolean tookOver = false;
                if (session.getRedPlayer() == null && sm.takeOver(me, key, 0)) {
                    serverPlayer.sendSystemMessage(Component.literal("你已接手红方空位。"));
                    tookOver = true;
                } else if (session.getBlackPlayer() == null && sm.takeOver(me, key, 1)) {
                    serverPlayer.sendSystemMessage(Component.literal("你已接手黑方空位。"));
                    tookOver = true;
                } else if (sm.joinAsSpectator(me, key)) {
                    serverPlayer.sendSystemMessage(Component.literal("你已加入旁观 — 等有空位时可用 /qisheng takeover 接手。"));
                    GameBroadcaster.broadcastRoster(serverLevel, session, pos);
                } else {
                    serverPlayer.sendSystemMessage(Component.literal("无法进入旁观。"));
                }
                if (tookOver) {
                    GameBroadcaster.broadcastSync(serverLevel, session, pos);
                    GameBroadcaster.broadcastRoster(serverLevel, session, pos);
                }
            }
        } else if (isSpectator) {
            // Already spectating — refresh the roster. If a slot has opened up,
            // prompt the player to consider takeover.
            GameBroadcaster.broadcastRoster(serverLevel, session, pos);
            if (session.getRedPlayer() == null) {
                serverPlayer.sendSystemMessage(Component.literal("红方空位 — 输入 /qisheng takeover red 可接手。"));
            } else if (session.getBlackPlayer() == null) {
                serverPlayer.sendSystemMessage(Component.literal("黑方空位 — 输入 /qisheng takeover black 可接手。"));
            }
        }
        // If alreadyIn, no chat update — the screen itself shows the latest state.

        // Always open the GUI on right-click so the player can interact.
        // Opening the board is also the one path that changes no state and
        // therefore broadcasts nothing — which is exactly what is needed after a
        // restart: it re-arms a PVC game whose turn had become the computer's.
        PvcController.maybeSchedule(serverLevel, pos, session);
        GameBroadcaster.sendOpenScreen(serverPlayer, session, pos);
        GameBroadcaster.broadcastRoster(serverLevel, session, pos);
        return InteractionResult.SUCCESS;
    }

    /** 把一块已结束的棋盘复位成可重新加入的 WAITING 局。 */
    private static void resetFinishedBoard(SessionManager sm, GameSession session, BoardKey key) {
        UUID red = session.getRedPlayer();
        UUID black = session.getBlackPlayer();
        session.setState(GameState.WAITING);
        session.setResult(com.qisheng.chess.pvp.GameResult.ONGOING);
        session.setSdPlayer(0);
        session.setSelectPoint(-1);
        session.setRedPlayer(null);
        session.setBlackPlayer(null);
        session.getChessData().fromFen(CChessUtil.INIT);
        if (red != null) sm.evictPlayer(red);
        if (black != null) sm.evictPlayer(black);
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
        super.onPlace(state, level, pos, oldState, isMoving);
        if (level instanceof ServerLevel serverLevel && !level.isClientSide()) {
            SessionManager.get().getOrCreate(serverLevel, pos);
        }
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        // BlockState changes (e.g. a neighbouring block update rewriting this
        // position) also fire onRemove. Only drop the session when the board is
        // actually gone, otherwise a plain state refresh would silently end a
        // running game.
        if (!level.isClientSide() && !state.is(newState.getBlock())) {
            SessionManager.get().remove(BoardKey.of(level, pos));
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }
}
