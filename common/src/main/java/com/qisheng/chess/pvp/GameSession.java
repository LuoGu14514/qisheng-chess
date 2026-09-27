package com.qisheng.chess.pvp;

import com.qisheng.chess.engine.xqwlight.Position;
import com.qisheng.chess.network.SwitchPackets;
import com.qisheng.chess.util.CChessUtil;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 一局中国象棋的状态机
 *
 * 第二批新增字段:
 *  - pendingDrawFrom    UUID:发起待处理求和的玩家(null=无)
 *  - pendingSwitch      SwitchPackets.Pending:发起待处理切换请求的快照(null=无)
 */
public class GameSession {
    private GameState state = GameState.WAITING;
    private BoardMode mode = BoardMode.PVP;
    private GameResult result = GameResult.ONGOING;

    private final Position chessData;

    private UUID redPlayer = null;
    private UUID blackPlayer = null;

    private final Set<UUID> spectators = new HashSet<>();

    private int sdPlayer = 0;
    private int selectPoint = -1;

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

    public boolean isSpectator(UUID id) { return spectators.contains(id); }
    public boolean addSpectator(UUID id) { return spectators.add(id); }
    public boolean removeSpectator(UUID id) { return spectators.remove(id); }
    public Set<UUID> getSpectators() { return spectators; }

    public int getSelectPoint() { return selectPoint; }
    public void setSelectPoint(int p) { this.selectPoint = p; }

    public int getSdPlayer() { return sdPlayer; }
    public void setSdPlayer(int sd) { this.sdPlayer = sd; }

    public UUID getPendingDrawFrom() { return pendingDrawFrom; }
    public void setPendingDrawFrom(UUID id) { this.pendingDrawFrom = id; }

    public SwitchPackets.Pending getPendingSwitch() { return pendingSwitch; }
    public void setPendingSwitch(SwitchPackets.Pending p) { this.pendingSwitch = p; }

    public GameResult checkGameOver() {
        if (CChessUtil.isRepeat(chessData)) return GameResult.DRAW;
        if (CChessUtil.reachMoveLimit(chessData)) return GameResult.DRAW;
        if (chessData.isMate()) {
            return sdPlayer == 0 ? GameResult.BLACK_WIN : GameResult.RED_WIN;
        }
        return GameResult.ONGOING;
    }
}