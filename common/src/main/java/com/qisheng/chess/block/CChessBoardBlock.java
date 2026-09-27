package com.qisheng.chess.block;

import com.qisheng.chess.pvp.BoardMessages;
import com.qisheng.chess.tileentity.CChessTileEntity;
import com.qisheng.chess.pvp.GameBroadcaster;
import com.qisheng.chess.pvp.GameMessages;
import com.qisheng.chess.pvp.GameSession;
import com.qisheng.chess.pvp.GameState;
import com.qisheng.chess.pvp.SessionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 单方块中国象棋棋盘。
 *
 * 交互流程(MVP):
 *  1. 玩家右键棋盘 → 服务端注册/找到 session → 玩家加入(或变成旁观)
 *  2. 完成后立即发送 CHESS_OPEN_SCREEN + CHESS_PLAYER_INFO,客户端打开 GUI
 *  3. GUI 内点击选子/落子,不再走 in-world click
 *
 * 提示渠道:右键棋盘方块的所有用户反馈走 {@code ServerPlayer#sendSystemMessage}
 * (聊天栏)。其余场景(对战内事件、求和、认输等)仍走 GameBroadcaster 的
 * popup / broadcast 路径。
 *
 * 服务端维护:
 *  - onPlace → SessionManager.getOrCreate 注册会话
 *  - onRemove → SessionManager.remove 清理会话
 *
 * Roster 通知:任何 roster 变更(join / takeOver / joinAsSpectator / 断开)
 * 后都调用 {@link GameBroadcaster#broadcastRoster} 让所有 GUI 客户端更新
 * 玩家名 + 旁观名单。
 */
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
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
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

        GameSession session = sm.get(pos);
        if (session == null) {
            serverPlayer.sendSystemMessage(Component.literal("该棋盘无效,请重新放置。"));
            return InteractionResult.FAIL;
        }

        GameState gameState = session.getState();
        UUID me = serverPlayer.getUUID();
        boolean alreadyIn = session.containsPlayer(me);
        boolean isSpectator = session.isSpectator(me);

        // FINISHED: allow reopening the GUI in read-only mode for review.
        // Server's GameLogic.tryMove / trySelect will reject any moves anyway
        // because state == FINISHED.
        if (gameState == GameState.FINISHED) {
            GameBroadcaster.sendOpenScreen(serverPlayer, session, pos);
            GameBroadcaster.broadcastRoster(serverLevel, session, pos);
            return InteractionResult.SUCCESS;
        }

        if (!alreadyIn && !isSpectator) {
            if (gameState == GameState.WAITING) {
                boolean ok = sm.joinGame(me, pos);
                if (ok) {
                    if (session.getState() == GameState.PLAYING) {
                        String myRole = session.getRedPlayer().equals(me) ? "红方" : "黑方";
                        serverPlayer.sendSystemMessage(Component.literal("对局开始!你是" + myRole + "。"));
                        UUID otherId = session.getRedPlayer().equals(me)
                                ? session.getBlackPlayer() : session.getRedPlayer();
                        ServerPlayer other = (ServerPlayer) serverLevel.getPlayerByUUID(otherId);
                        if (other != null) {
                            other.sendSystemMessage(Component.literal(serverPlayer.getName().getString()
                                            + " 已加入" + myRole + "。"));
                            BoardMessages.sendTo(other, session);
                        }
                        GameBroadcaster.broadcastSync(serverLevel, session, pos);
                        BoardMessages.sendTo(serverPlayer, session);
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
                if (session.getRedPlayer() == null && sm.takeOver(me, pos, 0)) {
                    serverPlayer.sendSystemMessage(Component.literal("你已接手红方空位。"));
                    tookOver = true;
                } else if (session.getBlackPlayer() == null && sm.takeOver(me, pos, 1)) {
                    serverPlayer.sendSystemMessage(Component.literal("你已接手黑方空位。"));
                    tookOver = true;
                } else if (sm.joinAsSpectator(me, pos)) {
                    serverPlayer.sendSystemMessage(Component.literal("你已加入旁观 — 等有空位时可用 /qisheng takeover 接手。"));
                    BoardMessages.sendTo(serverPlayer, session);
                    GameBroadcaster.broadcastRoster(serverLevel, session, pos);
                } else {
                    serverPlayer.sendSystemMessage(Component.literal("无法进入旁观。"));
                }
                if (tookOver) {
                    GameBroadcaster.broadcastSync(serverLevel, session, pos);
                    BoardMessages.sendTo(serverPlayer, session);
                    GameBroadcaster.broadcastRoster(serverLevel, session, pos);
                }
            }
        } else if (isSpectator) {
            // Already spectating — re-send board so the screen has fresh data.
            // If a slot has opened up, prompt the player to consider takeover.
            BoardMessages.sendTo(serverPlayer, session);
            GameBroadcaster.broadcastRoster(serverLevel, session, pos);
            if (session.getRedPlayer() == null) {
                serverPlayer.sendSystemMessage(Component.literal("红方空位 — 输入 /qisheng takeover red 可接手。"));
            } else if (session.getBlackPlayer() == null) {
                serverPlayer.sendSystemMessage(Component.literal("黑方空位 — 输入 /qisheng takeover black 可接手。"));
            }
        }
        // If alreadyIn, no popup update — the screen itself shows the latest state.

        // Always open the GUI on right-click so the player can interact.
        GameBroadcaster.sendOpenScreen(serverPlayer, session, pos);
        GameBroadcaster.broadcastRoster(serverLevel, session, pos);
        return InteractionResult.SUCCESS;
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
        super.onPlace(state, level, pos, oldState, isMoving);
        if (!level.isClientSide() && level instanceof ServerLevel serverLevel) {
            SessionManager.get().getOrCreate(serverLevel, pos);
        }
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!level.isClientSide()) {
            SessionManager.get().remove(pos);
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    /** Spawn a brief particle puff above a square (kept as a stub for future in-world interaction mode). */
    @SuppressWarnings("unused")
    private static void spawnHighlight(ServerLevel level, BlockPos boardPos, int sq, ParticleOptions type) {
        // Visual highlight is no longer needed: the GUI shows the selection
        // directly. Kept as a no-op stub in case we want world-side particles
        // for an in-world interaction mode later.
    }
}