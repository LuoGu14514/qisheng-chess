package com.qisheng.chess.pvp;

import net.minecraft.network.chat.Component;

/**
 * Chinese-language prompts for every {@link GameLogic} outcome, plus the
 * join / handover / spectator-flow messages. The right-click block flow,
 * the CLI flow, and the C2S packet flow all route through here so the
 * wording stays identical.
 *
 * <p>Messages are now short and human — no more "[qisheng]" prefix.
 * The C2S packet handler routes them via {@link GameBroadcaster#broadcastPopup}
 * so the player sees them as a centred in-GUI overlay rather than a chat
 * line.
 */
public final class GameMessages {
    private GameMessages() {}

    public static Component describeSelect(GameLogic.SelectOutcome out) {
        return switch (out) {
            case OK              -> Component.literal("已选子。点击目标格走子。");
            case NOT_IN_GAME     -> Component.literal("你不在这一局中。");
            case GAME_FINISHED   -> Component.literal("对局已结束。");
            case GAME_NOT_PLAYING-> Component.literal("对局未在进行中。");
            case NOT_YOUR_TURN   -> Component.literal("现在不是你的回合。");
            case OUT_OF_BOUNDS   -> Component.literal("点击越界。");
            case EMPTY_SQUARE    -> Component.literal("该格无子,请点己方棋子。");
            case WRONG_PIECE_SIDE-> Component.literal("只能选己方的棋子。");
        };
    }

    public static Component describeMove(GameLogic.MoveOutcome out) {
        return switch (out) {
            case OK              -> Component.literal("走子成功。");
            case NOT_IN_GAME     -> Component.literal("你不在这一局中。");
            case GAME_FINISHED   -> Component.literal("对局已结束。");
            case GAME_NOT_PLAYING-> Component.literal("对局未在进行中。");
            case NOT_YOUR_TURN   -> Component.literal("现在不是你的回合。");
            case SOURCE_MISMATCH -> Component.literal("所选棋子与你刚才点的不同,请重选。");
            case OUT_OF_BOUNDS   -> Component.literal("点击越界。");
            case ILLEGAL_MOVE    -> Component.literal("违反走子规则。");
            case KING_EXPOSED    -> Component.literal("违反规则 — 你的将/帅会被吃。");
        };
    }

    /** Hint when the player hits the side of the block instead of the top. */
    public static Component lookAtTopFace() {
        return Component.literal("请低头看向棋盘顶面 — 侧面点击无效。");
    }

    public static Component joinedAsRed() {
        return Component.literal("你已加入红方(先手)。等待对手加入…");
    }

    public static Component joinedAsBlack() {
        return Component.literal("对局开始!你是黑方(后手)。");
    }

    public static Component opponentJoined(String opponentName) {
        return Component.literal(opponentName + " 已加入红方(先手)。");
    }

    public static Component alreadyJoinedWaiting() {
        return Component.literal("你已加入,等待对手。");
    }

    public static Component alreadyJoinedPlaying(String role, String turn) {
        return Component.literal("你是" + role + ";" + turn + "。先点己方棋子,再点目标格走子。");
    }
}