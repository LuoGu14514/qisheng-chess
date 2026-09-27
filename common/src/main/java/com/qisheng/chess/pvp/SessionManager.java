package com.qisheng.chess.pvp;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全局会话管理器(简化版,MVP 不做缓存清理)
 * 负责:
 *  - 注册每个棋盘方块的对局
 *  - 全局模式设置(PVP/PVC)
 *  - 检查玩家是否在某个对局中
 *
 * 红/黑判定:**仅按加入顺序**。
 *   - 第一次 joinGame → 红方(走先)
 *   - 第二次 joinGame → 黑方(走后),state 切 PLAYING
 *   - 之后玩家再尝试加入 → 失败,可走 joinAsSpectator 旁观
 *
 * ConcurrentHashMap 约束:在 {@code computeIfAbsent} 的 mapping function
 * 里禁止对同一 map 做 {@code put/remove/replace} 等会再次获取 bin 锁的
 * 调用,会抛 {@code IllegalStateException: Recursive update}。
 * 因此 {@link #getOrCreate} 的 lambda 只构造对象,不做 put —
 * 由 computeIfAbsent 自己把返回值放进 map。
 *
 * 第二批新增:
 *  - requestDraw / respondDraw 求和流程
 *  - swapRoles 切换身份(双方互换红/黑)
 *  - requestSwitch / respondSwitch 切换身份流程
 *  - cancelPendingOffers 状态变化时清空待处理申请
 */
public class SessionManager {
    private static final SessionManager INSTANCE = new SessionManager();
    public static SessionManager get() { return INSTANCE; }

    private SessionManager() {}

    private BoardMode globalMode = BoardMode.PVP;

    private final Map<BlockPos, GameSession> sessions = new ConcurrentHashMap<>();

    private final Map<UUID, BlockPos> playerInGame = new ConcurrentHashMap<>();

    private final Map<UUID, BlockPos> playerSpectating = new ConcurrentHashMap<>();

    public BoardMode getGlobalMode() { return globalMode; }
    public void setGlobalMode(BoardMode mode) { this.globalMode = mode; }

    private GameSession newSession() {
        GameSession session = new GameSession();
        session.setMode(globalMode);
        return session;
    }

    public GameSession getOrCreate(ServerLevel level, BlockPos pos) {
        return sessions.computeIfAbsent(pos, p -> newSession());
    }

    public GameSession get(BlockPos pos) {
        return sessions.get(pos);
    }

    /** 移除整个棋盘会话(棋盘被破坏时调用) */
    public void remove(BlockPos pos) {
        GameSession s = sessions.remove(pos);
        if (s != null) {
            if (s.getRedPlayer() != null) playerInGame.remove(s.getRedPlayer(), pos);
            if (s.getBlackPlayer() != null) playerInGame.remove(s.getBlackPlayer(), pos);
            for (UUID spec : s.getSpectators()) {
                playerSpectating.remove(spec, pos);
            }
        }
    }

    /**
     * 把指定玩家从所有对局/旁观表里移除。
     *  - 如果是红/黑玩家 → 清掉他的角色
     *  - 如果是旁观者 → 移除
     * 不删整个对局。
     */
    public void evictPlayer(UUID playerId) {
        playerInGame.remove(playerId);
        playerSpectating.remove(playerId);
        for (GameSession s : sessions.values()) {
            if (playerId.equals(s.getRedPlayer())) s.setRedPlayer(null);
            if (playerId.equals(s.getBlackPlayer())) s.setBlackPlayer(null);
            s.removeSpectator(playerId);
        }
    }

    public boolean joinGame(UUID playerId, BlockPos pos) {
        GameSession s = sessions.get(pos);
        if (s == null) return false;
        if (s.getState() != GameState.WAITING) return false;

        if (s.getRedPlayer() == null) {
            s.setRedPlayer(playerId);
            playerInGame.put(playerId, pos);
            return true;
        } else if (s.getBlackPlayer() == null && !s.getRedPlayer().equals(playerId)) {
            s.setBlackPlayer(playerId);
            playerInGame.put(playerId, pos);
            s.setState(GameState.PLAYING);
            return true;
        }
        return false;
    }

    public boolean takeOver(UUID playerId, BlockPos pos, int role) {
        GameSession s = sessions.get(pos);
        if (s == null) return false;
        if (s.getState() != GameState.PLAYING) return false;
        if (s.containsPlayer(playerId)) return false;
        if (playerInGame.containsKey(playerId)) return false;

        if (s.isSpectator(playerId)) {
            s.removeSpectator(playerId);
            playerSpectating.remove(playerId);
        }

        if (role == 0) {
            if (s.getRedPlayer() != null) return false;
            s.setRedPlayer(playerId);
            playerInGame.put(playerId, pos);
            return true;
        } else if (role == 1) {
            if (s.getBlackPlayer() != null) return false;
            s.setBlackPlayer(playerId);
            playerInGame.put(playerId, pos);
            return true;
        }
        return false;
    }

    public boolean joinAsSpectator(UUID playerId, BlockPos pos) {
        GameSession s = sessions.get(pos);
        if (s == null) return false;
        if (s.getState() != GameState.PLAYING) return false;
        if (s.containsPlayer(playerId)) return false;
        if (s.isSpectator(playerId)) return false;
        s.addSpectator(playerId);
        playerSpectating.put(playerId, pos);
        return true;
    }

    public BlockPos getPlayerGame(UUID playerId) {
        return playerInGame.get(playerId);
    }

    public BlockPos getPlayerSpectating(UUID playerId) {
        return playerSpectating.get(playerId);
    }

    public void removePlayerSpectating(UUID playerId) {
        playerSpectating.remove(playerId);
    }

    public boolean isPlayerNear(ServerLevel level, UUID playerId, BlockPos pos) {
        var player = level.getPlayerByUUID(playerId);
        if (player == null) return false;
        return player.blockPosition().closerThan(pos, 5.0);
    }

    // ---- Draw (求和) ----

    /** Outcome of a draw offer. */
    public enum DrawOutcome { ACCEPTED, REJECTED, CANCELLED }

    /**
     * Player requests a draw. Validates:
     *  - session exists and is in PLAYING state
     *  - requester is one of the two players
     *  - no draw is already pending
     *
     * Returns true on success (caller should then send the invite to the
     * opponent and update the popup text on the requester side).
     */
    public boolean requestDraw(UUID playerId, BlockPos pos) {
        GameSession s = sessions.get(pos);
        if (s == null) return false;
        if (s.getState() != GameState.PLAYING) return false;
        if (!s.containsPlayer(playerId)) return false;
        if (s.getPendingDrawFrom() != null) return false;
        s.setPendingDrawFrom(playerId);
        return true;
    }

    /**
     * Opponent responds to a pending draw offer.
     *
     * @return ACCEPTED if the game ends in DRAW, REJECTED if the requester
     *         is told "rejected", CANCELLED if the pending offer no longer
     *         exists (state changed).
     *
     * On ACCEPTED, the caller is responsible for broadcasting
     * {@code broadcastGameOver} with {@code GameResult.DRAW}.
     */
    public DrawOutcome respondDraw(UUID responderId, BlockPos pos, boolean accept) {
        GameSession s = sessions.get(pos);
        if (s == null) return DrawOutcome.CANCELLED;
        UUID requester = s.getPendingDrawFrom();
        if (requester == null) return DrawOutcome.CANCELLED;
        // Responder must be the OTHER player (not the requester themselves).
        UUID other = requester.equals(s.getRedPlayer())
                ? s.getBlackPlayer()
                : s.getRedPlayer();
        if (other == null || !other.equals(responderId)) return DrawOutcome.CANCELLED;

        s.setPendingDrawFrom(null);
        return accept ? DrawOutcome.ACCEPTED : DrawOutcome.REJECTED;
    }

    /** Cancel any pending draw offer (e.g. game over). */
    public void cancelDraw(BlockPos pos) {
        GameSession s = sessions.get(pos);
        if (s != null) s.setPendingDrawFrom(null);
    }

    // ---- Role swap (used by switch invite flow) ----

    /**
     * Atomically swap the red / black identity of two players. Both must be
     * players on the same session, in PLAYING state. Used when both parties
     * accept a "switch sides" invite.
     *
     * Returns false if preconditions aren't met; the session is left
     * untouched in that case.
     */
    public boolean swapRoles(UUID a, UUID b) {
        if (a.equals(b)) return false;
        for (GameSession s : sessions.values()) {
            if (s.getState() != GameState.PLAYING) continue;
            if (!a.equals(s.getRedPlayer()) || !b.equals(s.getBlackPlayer())) continue;
            s.setRedPlayer(b);
            s.setBlackPlayer(a);
            // playerInGame map is keyed by UUID → BlockPos so the entries
            // don't change. Nothing to update.
            return true;
        }
        return false;
    }
}