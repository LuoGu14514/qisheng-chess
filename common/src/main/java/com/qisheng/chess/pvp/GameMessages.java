package com.qisheng.chess.pvp;

import net.minecraft.network.chat.Component;

/**
 * Localized prompts for every {@link GameLogic} outcome, plus the join /
 * handover / spectator-flow messages. The right-click block flow, the CLI
 * flow, and the C2S packet flow all route through here so the wording
 * stays identical.
 *
 * <p>All user-visible text flows through {@code Component.translatable} so
 * the language follows the player's client locale (see {@code assets/.../lang/}).
 * The C2S packet handler routes them via {@link GameBroadcaster#broadcastPopup}
 * so the player sees them as a centred in-GUI overlay rather than a chat
 * line.
 */
public final class GameMessages {
    private GameMessages() {}

    // --- select outcomes ------------------------------------------------

    public static Component describeSelect(GameLogic.SelectOutcome out) {
        return switch (out) {
            case OK              -> Component.translatable("qisheng.chess.server.select.ok");
            case NOT_IN_GAME     -> Component.translatable("qisheng.chess.server.player.not_in_game");
            case GAME_FINISHED   -> Component.translatable("qisheng.chess.server.game.finished");
            case GAME_NOT_PLAYING-> Component.translatable("qisheng.chess.server.game.not_playing");
            case NOT_YOUR_TURN   -> Component.translatable("qisheng.chess.server.player.not_your_turn");
            case OUT_OF_BOUNDS   -> Component.translatable("qisheng.chess.server.player.out_of_bounds");
            case EMPTY_SQUARE    -> Component.translatable("qisheng.chess.server.select.empty_square");
            case WRONG_PIECE_SIDE-> Component.translatable("qisheng.chess.server.select.wrong_piece_side");
        };
    }

    // --- move outcomes --------------------------------------------------

    public static Component describeMove(GameLogic.MoveOutcome out) {
        return switch (out) {
            case OK              -> Component.translatable("qisheng.chess.server.move.ok");
            case NOT_IN_GAME     -> Component.translatable("qisheng.chess.server.player.not_in_game");
            case GAME_FINISHED   -> Component.translatable("qisheng.chess.server.game.finished");
            case GAME_NOT_PLAYING-> Component.translatable("qisheng.chess.server.game.not_playing");
            case NOT_YOUR_TURN   -> Component.translatable("qisheng.chess.server.player.not_your_turn");
            case SOURCE_MISMATCH -> Component.translatable("qisheng.chess.server.move.source_mismatch");
            case OUT_OF_BOUNDS   -> Component.translatable("qisheng.chess.server.player.out_of_bounds");
            case ILLEGAL_MOVE    -> Component.translatable("qisheng.chess.server.move.illegal_move");
            case KING_EXPOSED    -> Component.translatable("qisheng.chess.server.move.king_exposed");
        };
    }

    // --- join flow ------------------------------------------------------

    public static Component joinedAsRed() {
        return Component.translatable("qisheng.chess.server.joined_red");
    }

    /**
     * "You are %s; %s. Pick your piece, then click a destination."
     * Used when an already-seated player re-clicks join while a game is in
     * progress — reminds them of their role and whose turn it is. The
     * role/turn arguments are themselves translatable so nested rendering
     * follows the client's locale.
     */
    public static Component alreadyJoinedWaiting() {
        return Component.translatable("qisheng.chess.server.already_joined_waiting");
    }

    public static Component alreadyJoinedPlaying(Component role, Component turn) {
        return Component.translatable("qisheng.chess.server.already_joined_playing", role, turn);
    }
}
