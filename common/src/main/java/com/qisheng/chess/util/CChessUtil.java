package com.qisheng.chess.util;

import com.qisheng.chess.engine.xqwlight.Position;

/**
 * 中象工具方法
 *
 * 一部分是 TLM 原仓库照搬(TartaricAcid/TouhouLittleMaid 1.18.2, MIT):
 *   - INIT FEN
 *   - isRed
 *   - reachMoveLimit / isRepeat
 *
 * 另一部分是 qisheng-chess 自己的:
 *   - boardToAscii: 整盘 ASCII 表示,自动推到聊天,玩家不用记初始 FEN
 *
 * 原先还有一批服务于「单方块棋盘顶面」的方法(getClickSquare / squareCenter /
 * piecesIndex / isBlack / isPlayer / isMaid),随提交 77174bd
 * "Remove live-on-block board surface renderer" 一起失效,已删除。
 */
public final class CChessUtil {
    // 女仆必输残局，测试用
    // rnbakab1r/9/8R/p1p1C4/1C4p1p/9/P1P1P1P1P/2N5N/9/R1BAKAB2
    // 长打残局
    // 1C1a2br1/3rak3/7c1/p3P2Rp/5n3/9/P1R3p1P/4C4/4A3N/2BAK3c
    // 六十回合自然限着
    // 3aka3/9/9/9/9/9/9/9/9/3AKA3
    public static final String INIT = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR";

    /**
     * 整盘 ASCII 表示 — 玩家看不到真实棋盘,所以服务器把当前局面推到聊天。
     *
     * 编码(标准中象字符,大小写代表红/黑):
     *   K 将  A 仕  B 相  N 马  R 车  C 炮  P 兵/卒
     *
     * 视觉布局(固定方向,红方视角):
     *   - 顶行 visualRank=9 = 黑方底线
     *   - 底行 visualRank=0 = 红方底线
     *   - visualRank=5 与 visualRank=4 之间是 楚河 / 汉界
     *
     * Position.fromFen 把 FEN 的第一个 rank 写到 internalRank=RANK_TOP=3,
     * 最后一个 rank 写到 RANK_BOTTOM=12 — 因此 internalRank=3 是黑方底线,
     * internalRank=12 是红方底线。displayIdx 从顶到底打印,顶行映射到
     * RANK_TOP(3),底行映射到 RANK_BOTTOM(12)。
     */
    public static String boardToAscii(Position p) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("      a   b   c   d   e   f   g   h   i\n");
        for (int displayIdx = 0; displayIdx < 10; displayIdx++) {
            int internalRank = displayIdx + Position.RANK_TOP;  // 3..12
            int visualRank = 9 - displayIdx;                   // 9..0

            // 用"楚河汉界"行代替 visualRank=5(初始局面是空的,看起来像河)
            if (visualRank == 5) {
                sb.append("    +---+---+---+---+---+---+---+---+---+\n");
                sb.append("  5 |       楚 河                汉 界       |\n");
                sb.append("    +---+---+---+---+---+---+---+---+---+\n");
                continue;
            }

            sb.append("    +---+---+---+---+---+---+---+---+---+\n");
            sb.append("  ").append(visualRank).append(" ");
            for (int displayFile = 0; displayFile < 9; displayFile++) {
                int internalFile = displayFile + Position.FILE_LEFT;
                int sq = Position.COORD_XY(internalFile, internalRank);
                byte pc = p.squares[sq];
                sb.append("| ").append(pieceLetter(pc)).append(" ");
            }
            sb.append("|\n");
        }
        sb.append("    +---+---+---+---+---+---+---+---+---+\n");
        sb.append("      a   b   c   d   e   f   g   h   i\n");
        return sb.toString();
    }

    /** 0=空,1=将,2=士,3=象,4=马,5=车,6=炮,7=兵 → 单字符;大小写区分红黑。 */
    private static String pieceLetter(byte pc) {
        if (pc == 0) return "·";
        int base;
        boolean isRed = (pc & 8) == 8;
        boolean isBlack = (pc & 16) == 16;
        if (isRed) base = pc - 8;
        else if (isBlack) base = pc - 16;
        else return "?";
        String letter = switch (base) {
            case 0 -> "K"; // 將
            case 1 -> "A"; // 仕
            case 2 -> "B"; // 相
            case 3 -> "N"; // 馬
            case 4 -> "R"; // 車
            case 5 -> "C"; // 炮
            case 6 -> "P"; // 兵
            default -> "?";
        };
        return isRed ? letter : letter.toLowerCase();
    }

    /**
     * 红方棋子判定:位 3(值 8)为红。参数是 {@code Position.squares[]} 里的
     * 棋子字节(红 = 种类 + 8,黑 = 种类 + 16),不是格子下标、也不是种类序号。
     */
    public static boolean isRed(byte piece) {
        return (piece & 8) == 8;
    }

    // 六十回自然限着
    public static boolean reachMoveLimit(Position position) {
        return position.moveNum > 60;
    }

    // 三回合长打
    public static boolean isRepeat(Position position) {
        return position.repStatus(3) > 0;
    }
}