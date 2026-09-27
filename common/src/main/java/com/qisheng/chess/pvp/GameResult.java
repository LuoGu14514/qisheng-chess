package com.qisheng.chess.pvp;

/**
 * 对局结果
 * ONGOING    - 进行中
 * RED_WIN    - 红方胜(中象红棋 = 玩家)
 * BLACK_WIN  - 黑方胜
 * DRAW       - 和棋(60回合限着/三回合长打)
 * ABANDONED  - 玩家离开
 */
public enum GameResult {
    ONGOING,
    RED_WIN,
    BLACK_WIN,
    DRAW,
    ABANDONED
}
