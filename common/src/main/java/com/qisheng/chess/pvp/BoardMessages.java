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
        String turn = session.getSdPlayer() == 0 ? "红方" : "黑方";
        String suffix;
        if (session.getState() != GameState.PLAYING) {
            suffix = "(" + session.getState() + ")";
        } else if (role < 0) {
            suffix = "(旁观;" + turn + "走子)";
        } else if (role == session.getSdPlayer()) {
            suffix = "← 你走子";
        } else {
            suffix = "(等待;" + turn + "走子)";
        }
        Component msg = Component.literal("轮到 " + turn + suffix);
        // Route through popup so it shows as an in-GUI overlay rather than
        // a chat line — players opening the GUI won't miss it.
        GameBroadcaster.broadcastPopupTo(player, msg);
    }
}