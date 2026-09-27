package com.qisheng.chess.pvp;

/**
 * 棋盘状态机
 * WAITING  - 等待玩家加入
 * PLAYING  - 对局进行中
 * FINISHED - 对局已结束
 */
public enum GameState {
    WAITING,
    PLAYING,
    FINISHED
}
