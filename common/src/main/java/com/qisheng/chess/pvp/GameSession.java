package com.qisheng.chess.pvp;

import com.qisheng.chess.engine.BoardRegistry;
import com.qisheng.chess.engine.BoardState;
import com.qisheng.chess.engine.BoardVariant;
import com.qisheng.chess.engine.ChineseChessEngine;
import com.qisheng.chess.engine.international.InternationalChessVariant;
import com.qisheng.chess.engine.international.IntChessBoard;
import com.qisheng.chess.engine.xiangqi.XiangqiVariant;
import com.qisheng.chess.engine.xqwlight.Position;
import com.qisheng.chess.network.SwitchPackets;
import com.qisheng.chess.util.CChessUtil;
import net.minecraft.nbt.CompoundTag;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 一局棋的状态机
 *
 * 第二批新增字段:
 *  - pendingDrawFrom    UUID:发起待处理求和的玩家(null=无)
 *  - pendingSwitch      SwitchPackets.Pending:发起待处理切换请求的快照(null=无)
 *
 * 第三批新增字段 (v0.3.1):
 *  - variantId          String:对应 {@link BoardVariant#getId()}(xiangqi / international)。
 *                      旧存档没有这一项,默认 {@link BoardRegistry#DEFAULT_ID},
 *                      即 xiangqi,与 v0.2.x 行为一致。
 *
 * 持久化(0.1.2 起):{@link #save()} / {@link #fromTag(CompoundTag)} 把整局状态
 * 序列化进 {@code CChessTileEntity} 的 NBT。**有意不存**旁观名单与两个待处理
 * 邀请(求和 / 换边)——它们只在玩家在线时有意义,重启后应当自然消失。
 */
public class GameSession {
    private GameState state = GameState.WAITING;
    private BoardMode mode = BoardMode.PVP;
    private GameResult result = GameResult.ONGOING;

    private final Position chessData;
    private String variantId = BoardRegistry.DEFAULT_ID;

    private UUID redPlayer = null;
    private UUID blackPlayer = null;

    private final Set<UUID> spectators = new HashSet<>();

    private int sdPlayer = 0;
    private int selectPoint = -1;

    /**
     * Source and destination squares of the most recent successful move, or
     * {@code -1} when nothing has been played yet (game reset, fresh save).
     * Sent to the client with every {@code CHESS_SYNC} / {@code CHESS_OPEN_SCREEN}
     * so the renderer can highlight the last move.
     *
     * <p>Both values are persisted to NBT (see {@link #save()} / {@link #fromTag})
     * so a player who reconnects mid-chapter still sees the most recent move
     * highlighted on the board.
     */
    private int lastMoveSrc = -1;
    private int lastMoveDst = -1;

    private UUID pendingDrawFrom = null;
    private SwitchPackets.Pending pendingSwitch = null;

    public GameSession() {
        this.chessData = new Position();
        this.chessData.fromFen(CChessUtil.INIT);
    }

    public GameState getState() { return state; }

    /**
     * Change the session state. If the new state is not {@code PLAYING},
     * any pending offers (draw / switch) are cleared automatically — they
     * no longer make sense once the game is over or hasn't started.
     */
    public void setState(GameState s) {
        if (this.state == s) return;
        this.state = s;
        if (s != GameState.PLAYING) {
            this.pendingDrawFrom = null;
            this.pendingSwitch = null;
        }
    }

    public BoardMode getMode() { return mode; }
    public void setMode(BoardMode m) { this.mode = m; }

    public GameResult getResult() { return result; }
    public void setResult(GameResult r) { this.result = r; }

    public Position getChessData() { return chessData; }

    /**
     * Stable id of the {@link BoardVariant} this session is using — {@code "xiangqi"}
     * or {@code "international"}. Persisted in NBT; older saves default to
     * {@link BoardRegistry#DEFAULT_ID} so v0.2.x worlds load unchanged.
     */
    public String getVariantId() { return variantId; }

    /**
     * Swap the variant this session is running. Custom versions cannot be
     * changed mid-game: callers are responsible for making sure the FEN on the
     * board makes sense for the new variant before invoking this.
     */
    public void setVariantId(String id) {
        this.variantId = id == null ? BoardRegistry.DEFAULT_ID : id;
    }

    /** Variant-aware view of the underlying board state. */
    public BoardState getBoardState() { return chessData; }

    /**
     * The {@link BoardVariant} for this session — convenience wrapper around
     * {@link BoardRegistry#getByIdOrDefault(String)}.
     */
    public BoardVariant getVariant() {
        return BoardRegistry.getByIdOrDefault(variantId);
    }

    public UUID getRedPlayer() { return redPlayer; }
    public UUID getBlackPlayer() { return blackPlayer; }

    public void setRedPlayer(UUID id) { this.redPlayer = id; }
    public void setBlackPlayer(UUID id) { this.blackPlayer = id; }

    public boolean containsPlayer(UUID id) {
        return id.equals(redPlayer) || id.equals(blackPlayer);
    }

    public int getPlayerRole(UUID id) {
        if (id.equals(redPlayer)) return 0;
        if (id.equals(blackPlayer)) return 1;
        return -1;
    }

    /** The player seated on {@code side} (0 = red, 1 = black), or {@code null}. */
    public UUID getPlayerAtSide(int side) {
        return side == 0 ? redPlayer : blackPlayer;
    }

    /**
     * True when it is the computer's turn: a running
     * {@link BoardMode#PVC} game with nobody seated on the side to move.
     *
     * <p>The computer's colour is <em>derived</em> from the empty seat rather
     * than stored. That keeps the NBT schema unchanged, and means a restore, a
     * board whose owner chose black, or a side vacated by a leaving player all
     * resolve without extra bookkeeping.
     *
     * <p>The "both seats empty" guard also stops the obvious failure mode: a
     * hand-edited save could otherwise have the computer play <em>both</em>
     * sides against itself, forever.
     */
    public boolean isComputerToMove() {
        if (mode != BoardMode.PVC || state != GameState.PLAYING) return false;
        if (redPlayer == null && blackPlayer == null) return false;
        return getPlayerAtSide(sdPlayer) == null;
    }

    public boolean isSpectator(UUID id) { return spectators.contains(id); }
    public boolean addSpectator(UUID id) { return spectators.add(id); }
    public boolean removeSpectator(UUID id) { return spectators.remove(id); }
    public Set<UUID> getSpectators() { return spectators; }

    public int getSelectPoint() { return selectPoint; }
    public void setSelectPoint(int p) { this.selectPoint = p; }

    public int getSdPlayer() { return sdPlayer; }
    public void setSdPlayer(int sd) { this.sdPlayer = sd; }

    /** Last successful move source square, or {@code -1} if none. */
    public int getLastMoveSource() { return lastMoveSrc; }
    public void setLastMoveSource(int s) { this.lastMoveSrc = s; }
    /** Last successful move destination square, or {@code -1} if none. */
    public int getLastMoveDest() { return lastMoveDst; }
    public void setLastMoveDest(int d) { this.lastMoveDst = d; }

    public UUID getPendingDrawFrom() { return pendingDrawFrom; }
    public void setPendingDrawFrom(UUID id) { this.pendingDrawFrom = id; }

    public SwitchPackets.Pending getPendingSwitch() { return pendingSwitch; }
    public void setPendingSwitch(SwitchPackets.Pending p) { this.pendingSwitch = p; }

    public GameResult checkGameOver() {
        BoardVariant v = getVariant();
        BoardState state = chessData;

        if (v.isCheckmate(state)) {
            return sdPlayer == 0 ? GameResult.BLACK_WIN : GameResult.RED_WIN;
        }

        // Variant-specific draw rules. Two flavours right now:
        //   - Xiangqi has threefold / move-limit drawn through the xqwlight
        //     board's own counters (repStatus + distance).
        //   - International chess has the 50-move rule (halfmoveClock >= 100).
        if (v instanceof XiangqiVariant) {
            if (CChessUtil.isRepeat(chessData)) return GameResult.DRAW;
            if (CChessUtil.reachMoveLimit(chessData)) return GameResult.DRAW;
        }
        if (v instanceof InternationalChessVariant && state instanceof IntChessBoard b) {
            if (b.halfmoveClock >= 100) return GameResult.DRAW;
        }

        if (v.isStalemate(state)) return GameResult.DRAW;
        return GameResult.ONGOING;
    }

    // ---- Persistence (NBT) ----

    private static final String TAG_FEN = "Fen";
    private static final String TAG_STATE = "State";
    private static final String TAG_MODE = "Mode";
    private static final String TAG_RESULT = "Result";
    private static final String TAG_SD = "SdPlayer";
    private static final String TAG_SELECT = "SelectPoint";
    private static final String TAG_RED = "Red";
    private static final String TAG_BLACK = "Black";
    private static final String TAG_LAST_SRC = "LastSrc";
    private static final String TAG_LAST_DST = "LastDst";
    private static final String TAG_VARIANT = "Variant";

    /** Snapshot of everything that must survive a server restart. */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString(TAG_FEN, chessData.toFen());
        tag.putString(TAG_STATE, state.name());
        tag.putString(TAG_MODE, mode.name());
        tag.putString(TAG_RESULT, result.name());
        tag.putInt(TAG_SD, sdPlayer);
        tag.putInt(TAG_SELECT, selectPoint);
        if (redPlayer != null) tag.putUUID(TAG_RED, redPlayer);
        if (blackPlayer != null) tag.putUUID(TAG_BLACK, blackPlayer);
        tag.putInt(TAG_LAST_SRC, lastMoveSrc);
        tag.putInt(TAG_LAST_DST, lastMoveDst);
        tag.putString(TAG_VARIANT, variantId);
        return tag;
    }

    /**
     * Rebuild a session from {@link #save()}.
     *
     * <p>Never returns {@code null} and never throws: a tag written by an older
     * build, or one hand-edited into a broken state, degrades to a fresh WAITING
     * game rather than crashing chunk load. The FEN goes through
     * {@link ChineseChessEngine#isWellFormedFen} first, because the engine's own
     * parser accepts anything and would happily produce a nonsense board.
     */
    public static GameSession fromTag(CompoundTag tag) {
        GameSession s = new GameSession();
        String fen = tag.getString(TAG_FEN);
        if (ChineseChessEngine.isWellFormedFen(fen)) {
            s.chessData.fromFen(fen);
        }
        s.state = enumOr(GameState.class, tag.getString(TAG_STATE), GameState.WAITING);
        s.mode = enumOr(BoardMode.class, tag.getString(TAG_MODE), BoardMode.PVP);
        s.result = enumOr(GameResult.class, tag.getString(TAG_RESULT), GameResult.ONGOING);
        s.sdPlayer = tag.getInt(TAG_SD) == 1 ? 1 : 0;
        int sel = tag.getInt(TAG_SELECT);
        s.selectPoint = ChineseChessEngine.isSquare(sel) ? sel : -1;
        if (tag.hasUUID(TAG_RED)) s.redPlayer = tag.getUUID(TAG_RED);
        if (tag.hasUUID(TAG_BLACK)) s.blackPlayer = tag.getUUID(TAG_BLACK);
        int src = tag.getInt(TAG_LAST_SRC);
        s.lastMoveSrc = ChineseChessEngine.isSquare(src) ? src : -1;
        int dst = tag.getInt(TAG_LAST_DST);
        s.lastMoveDst = ChineseChessEngine.isSquare(dst) ? dst : -1;
        // Older saves have no Variant tag. They are xiangqi by construction;
        // loading them under any other id would crash the first move.
        String savedVariant = tag.getString(TAG_VARIANT);
        s.variantId = savedVariant.isEmpty() ? BoardRegistry.DEFAULT_ID : savedVariant;
        // A game cannot be "playing" with nobody seated; that only happens when
        // the file was edited. Demote instead of leaving a zombie game.
        if (s.state == GameState.PLAYING && s.redPlayer == null && s.blackPlayer == null) {
            s.state = GameState.WAITING;
        }
        return s;
    }

    private static <E extends Enum<E>> E enumOr(Class<E> type, String name, E fallback) {
        if (name == null || name.isEmpty()) return fallback;
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equals(name)) return constant;
        }
        return fallback;
    }
}