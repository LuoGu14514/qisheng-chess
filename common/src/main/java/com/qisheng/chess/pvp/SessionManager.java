package com.qisheng.chess.pvp;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全局会话管理器
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
 * 会话以 {@link BoardKey}(维度 + 坐标)为键。0.1.3 之前只用 {@code BlockPos},
 * 导致主世界与下界同坐标的两块棋盘共用同一局棋。
 *
 * 占用约束:一个玩家同时只能在一局里当棋手。
 * {@link #joinGame} / {@link #takeOver} 都会检查 {@link #playerInGame},
 * 否则玩家可以在 A 盘坐着的同时去 B 盘抢红方,两局状态互相打架。
 *
 * ConcurrentHashMap 约束:在 {@code computeIfAbsent} 的 mapping function
 * 里禁止对同一 map 做 {@code put/remove/replace} 等会再次获取 bin 锁的
 * 调用,会抛 {@code IllegalStateException: Recursive update}。
 * 因此 {@link #getOrCreate} 的 lambda 只构造对象,不做 put —
 * 由 computeIfAbsent 自己把返回值放进 map。
 */
public class SessionManager {
    private static final SessionManager INSTANCE = new SessionManager();
    public static SessionManager get() { return INSTANCE; }

    private SessionManager() {}

    private BoardMode globalMode = BoardMode.PVP;

    private final Map<BoardKey, GameSession> sessions = new ConcurrentHashMap<>();

    private final Map<UUID, BoardKey> playerInGame = new ConcurrentHashMap<>();

    private final Map<UUID, BoardKey> playerSpectating = new ConcurrentHashMap<>();

    public BoardMode getGlobalMode() { return globalMode; }
    public void setGlobalMode(BoardMode mode) {
        if (mode != null) this.globalMode = mode;
    }

    private GameSession newSession() {
        GameSession session = new GameSession();
        session.setMode(globalMode);
        return session;
    }

    // ---- Lookup / lifecycle ----

    public GameSession getOrCreate(ServerLevel level, BlockPos pos) {
        return getOrCreate(BoardKey.of(level, pos));
    }

    public GameSession getOrCreate(BoardKey key) {
        return sessions.computeIfAbsent(key, k -> newSession());
    }

    public GameSession get(ServerLevel level, BlockPos pos) {
        return sessions.get(BoardKey.of(level, pos));
    }

    public GameSession get(BoardKey key) {
        return sessions.get(key);
    }

    /** 移除整个棋盘会话(棋盘被破坏时调用) */
    public void remove(BoardKey key) {
        GameSession s = sessions.remove(key);
        if (s != null) {
            if (s.getRedPlayer() != null) playerInGame.remove(s.getRedPlayer(), key);
            if (s.getBlackPlayer() != null) playerInGame.remove(s.getBlackPlayer(), key);
            for (UUID spec : s.getSpectators()) {
                playerSpectating.remove(spec, key);
            }
        }
    }

    /**
     * Install a session that was restored from a tile entity's NBT.
     *
     * <p>Called lazily — the first time somebody actually uses a board after a
     * restart — rather than from {@code BlockEntity#load}, because a chunk load
     * must not have the side effect of publishing global game state (and
     * {@code load} also runs on the client).
     *
     * <p>If a session already exists for {@code key} it wins; the restored
     * snapshot is discarded. That happens when a chunk is unloaded and reloaded
     * during a single run: the in-memory game is always newer than the file.
     */
    public GameSession adopt(BoardKey key, GameSession restored) {
        if (restored == null) return getOrCreate(key);
        GameSession existing = sessions.putIfAbsent(key, restored);
        GameSession winner = existing != null ? existing : restored;
        sealedIndex(key, winner);
        return winner;
    }

    /**
     * Point {@link #playerInGame} at the seats a restored session already holds.
     *
     * <p>Without this a player who rejoins after a restart would be unable to be
     * recognised as seated — {@code joinGame} would hand them the other colour
     * (or refuse), and a disconnect would not end their game.
     */
    private void sealedIndex(BoardKey key, GameSession s) {
        UUID red = s.getRedPlayer();
        UUID black = s.getBlackPlayer();
        if (red != null) playerInGame.put(red, key);
        if (black != null) playerInGame.put(black, key);
    }

    /**
     * Drop every session. Called from the server-stopping hook: the manager is a
     * static singleton, so without this a single-player world reload (or any
     * second world in the same JVM) would inherit the previous world's boards.
     */
    public void resetAll() {
        sessions.clear();
        playerInGame.clear();
        playerSpectating.clear();
        globalMode = BoardMode.PVP;
    }

    public int sessionCount() { return sessions.size(); }

    public Collection<GameSession> allSessions() { return sessions.values(); }

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

    // ---- Joining ----

    /** True when the player already holds a seat in some game (any board). */
    public boolean isPlayerBusy(UUID playerId) {
        return playerInGame.containsKey(playerId);
    }

    public boolean joinGame(UUID playerId, BoardKey key) {
        GameSession s = sessions.get(key);
        if (s == null) return false;
        if (s.getState() != GameState.WAITING) return false;
        // One seat per player, across all boards.
        BoardKey current = playerInGame.get(playerId);
        if (current != null && !current.equals(key)) return false;

        // PVC: a solo board. Taking the red seat starts the game at once — there
        // is no second human to wait for, because the computer owns whichever
        // seat stays empty (GameSession.isComputerToMove()).
        if (s.getMode() == BoardMode.PVC) {
            if (s.getRedPlayer() != null) return false;
            s.setRedPlayer(playerId);
            playerInGame.put(playerId, key);
            s.setState(GameState.PLAYING);
            return true;
        }

        if (s.getRedPlayer() == null) {
            s.setRedPlayer(playerId);
            playerInGame.put(playerId, key);
            return true;
        } else if (s.getBlackPlayer() == null && !s.getRedPlayer().equals(playerId)) {
            s.setBlackPlayer(playerId);
            playerInGame.put(playerId, key);
            s.setState(GameState.PLAYING);
            return true;
        }
        return false;
    }

    public boolean takeOver(UUID playerId, BoardKey key, int role) {
        GameSession s = sessions.get(key);
        if (s == null) return false;
        if (s.getState() != GameState.PLAYING) return false;
        // A PVC board is one human versus the computer by definition; letting a
        // second player sit down would silently turn it into a PVP game with an
        // opponent that keeps playing. Refuse, so the mode command stays
        // meaningful.
        if (s.getMode() == BoardMode.PVC) return false;
        if (s.containsPlayer(playerId)) return false;
        BoardKey current = playerInGame.get(playerId);
        if (current != null && !current.equals(key)) return false;

        if (s.isSpectator(playerId)) {
            s.removeSpectator(playerId);
            playerSpectating.remove(playerId);
        }

        if (role == 0) {
            if (s.getRedPlayer() != null) return false;
            s.setRedPlayer(playerId);
            playerInGame.put(playerId, key);
            return true;
        } else if (role == 1) {
            if (s.getBlackPlayer() != null) return false;
            s.setBlackPlayer(playerId);
            playerInGame.put(playerId, key);
            return true;
        }
        return false;
    }

    public boolean joinAsSpectator(UUID playerId, BoardKey key) {
        GameSession s = sessions.get(key);
        if (s == null) return false;
        if (s.getState() != GameState.PLAYING) return false;
        if (s.containsPlayer(playerId)) return false;
        if (s.isSpectator(playerId)) return false;
        // A player seated at another board must not also spectate this one.
        if (playerInGame.containsKey(playerId)) return false;
        s.addSpectator(playerId);
        playerSpectating.put(playerId, key);
        return true;
    }

    public BoardKey getPlayerGame(UUID playerId) {
        return playerInGame.get(playerId);
    }

    public BoardKey getPlayerSpectating(UUID playerId) {
        return playerSpectating.get(playerId);
    }

    public void removePlayerSpectating(UUID playerId) {
        playerSpectating.remove(playerId);
    }

    /**
     * The level that owns {@code key}, falling back to {@code fallback} when the
     * dimension is unloaded or unknown.
     *
     * <p>Packet handlers receive the sender's own level, which is not necessarily
     * the level the board lives in — a player may teleport away mid-game, and the
     * session is keyed by dimension.
     */
    public static ServerLevel resolve(MinecraftServer server, BoardKey key, ServerLevel fallback) {
        if (server != null && key != null) {
            ServerLevel lvl = server.getLevel(key.dimension());
            if (lvl != null) return lvl;
        }
        return fallback;
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
    public boolean requestDraw(UUID playerId, BoardKey key) {
        GameSession s = sessions.get(key);
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
    public DrawOutcome respondDraw(UUID responderId, BoardKey key, boolean accept) {
        GameSession s = sessions.get(key);
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
    public void cancelDraw(BoardKey key) {
        GameSession s = sessions.get(key);
        if (s != null) s.setPendingDrawFrom(null);
    }

    // ---- Role swap (used by switch invite flow) ----

    /**
     * Atomically swap the red / black identity of two players on one board.
     * Both must be the two seated players of {@code key} in PLAYING state.
     *
     * <p>The pre-0.1.3 version ignored the board and only matched
     * {@code a == red && b == black}, so a "switch back" request (a is black,
     * b is red) silently failed. Matching is now order-independent.
     *
     * Returns false if preconditions aren't met; the session is left
     * untouched in that case.
     */
    public boolean swapRoles(BoardKey key, UUID a, UUID b) {
        if (a == null || b == null || a.equals(b)) return false;
        GameSession s = sessions.get(key);
        if (s == null) return false;
        if (s.getState() != GameState.PLAYING) return false;

        UUID red = s.getRedPlayer();
        UUID black = s.getBlackPlayer();
        if (red == null || black == null) return false;

        boolean forward = a.equals(red) && b.equals(black);
        boolean backward = a.equals(black) && b.equals(red);
        if (!forward && !backward) return false;

        s.setRedPlayer(a);
        s.setBlackPlayer(b);
        // playerInGame is keyed by UUID → BoardKey, so the entries don't change.
        return true;
    }
}
