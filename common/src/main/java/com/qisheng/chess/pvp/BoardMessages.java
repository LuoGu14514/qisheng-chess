package com.qisheng.chess.pvp;

import com.qisheng.chess.util.CChessUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Push the current ASCII board to a player at moments where they need to see
 * what just changed: game started, after a successful move, after a reset.
 *
 * <p>Kept separate from {@link GameBroadcaster} (which handles the CHESS_SYNC
 * packet / game-over messages) so the high-frequency board dump has its own
 * dedicated place. Once the in-GUI popup overlay is wired up
 * ({@link GameBroadcaster#broadcastPopup}), the suffix line goes through the
 * popup path; the multi-line ASCII dump stays on chat because it doesn't fit
 * in a centred single-line popup and is most useful when the player is
 * <em>not</em> looking at the GUI.
 */
public final class BoardMessages {
    private BoardMessages() {}

    /** Send the current board (ASCII) + a one-line "whose turn" hint. */
    public static void sendTo(ServerPlayer player, GameSession session) {
        if (player == null || session == null) return;
        // ASCII dump stays on chat — multi-line, debug-friendly, useful
        // without the GUI.
        player.sendSystemMessage(Component.literal(CChessUtil.boardToAscii(session.getChessData())));
        int role = session.getPlayerRole(player.getUUID());
        Component turn = Component.translatable(session.getSdPlayer() == 0
                ? "qisheng.chess.role.red" : "qisheng.chess.role.black");
        Component suffix;
        if (session.getState() != GameState.PLAYING) {
            suffix = Component.literal("(" + session.getState() + ")");
        } else if (role < 0) {
            suffix = Component.translatable("qisheng.chess.turn.spectating", turn);
        } else if (role == session.getSdPlayer()) {
            suffix = Component.translatable("qisheng.chess.turn.your_move");
        } else {
            suffix = Component.translatable("qisheng.chess.turn.waiting", turn);
        }
        Component msg = Component.translatable("qisheng.chess.turn.label", turn).append(suffix);
        // Route through popup so it shows as an in-GUI overlay rather than
        // a chat line — players opening the GUI won't miss it.
        GameBroadcaster.broadcastPopupTo(player, msg);
    }
}