package com.qisheng.chess.event;

import com.qisheng.chess.network.PopupS2CPacket;
import com.qisheng.chess.pvp.GameBroadcaster;
import com.qisheng.chess.pvp.GameResult;
import com.qisheng.chess.pvp.GameSession;
import com.qisheng.chess.pvp.GameState;
import com.qisheng.chess.pvp.SessionManager;
import com.qisheng.chess.util.CChessUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Centralized disconnect handling.
 *
 * <p>Without this, a player who disconnects mid-game leaves the session
 * permanently stuck in PLAYING state with a phantom player — the surviving
 * opponent cannot join a fresh game on the same board and breaking the board
 * is the only escape. We:
 * <ol>
 *   <li>Mark the game FINISHED with the survivor as winner</li>
 *   <li>Send the survivor a "you win by forfeit" popup (BEFORE resetting
 *       state, otherwise the session reference is gone)</li>
 *   <li>Reset the board to WAITING with INIT FEN so two new players can start</li>
 *   <li>Broadcast a fresh roster so the survivor's GUI shows "no opponent yet"</li>
 * </ol>
 *
 * <p>Spectators also get cleaned up (no forfeit, no reset — just dropped).
 *
 * <p>Architectury does not expose a cross-platform player-quit event in
 * common code; platform-side entry points register this hook. See
 * {@code QishengChessFabric} (Fabric) for the registration.
 */
public final class PlayerDisconnectHandler {
    private static final Logger LOG = LoggerFactory.getLogger("qisheng_chess");

    private PlayerDisconnectHandler() {}

    public static void onPlayerDisconnect(ServerPlayer player) {
        if (player == null) return;
        UUID id = player.getUUID();
        SessionManager sm = SessionManager.get();

        // Spectator cleanup — separate from the forfeit path.
        BlockPos spectating = sm.getPlayerSpectating(id);
        if (spectating != null) {
            GameSession s = sm.get(spectating);
            if (s != null) s.removeSpectator(id);
            sm.removePlayerSpectating(id);
            // Push fresh roster to the remaining recipients.
            ServerLevel slevel = player.serverLevel();
            if (slevel != null && s != null) {
                GameBroadcaster.broadcastRoster(slevel, s, spectating);
            }
        }

        BlockPos pos = sm.getPlayerGame(id);
        if (pos == null) {
            // Was only a spectator (or stale entry) — already cleaned up.
            return;
        }

        ServerLevel level = player.serverLevel();
        GameSession session = sm.get(pos);
        if (session == null) {
            sm.evictPlayer(id);
            return;
        }

        if (!session.containsPlayer(id)) {
            sm.evictPlayer(id);
            return;
        }

        if (session.getState() == GameState.PLAYING) {
            UUID survivorId = id.equals(session.getRedPlayer())
                    ? session.getBlackPlayer()
                    : session.getRedPlayer();
            GameResult result = id.equals(session.getRedPlayer())
                    ? GameResult.BLACK_WIN
                    : GameResult.RED_WIN;

            if (survivorId != null) {
                ServerPlayer survivor = (ServerPlayer) level.getPlayerByUUID(survivorId);
                if (survivor != null) {
                    GameBroadcaster.sendPopupTo(survivor,
                            Component.literal(player.getName().getString()
                                    + " 已断线 — 你赢了(对方判负)。"),
                            PopupS2CPacket.Severity.INFO, 5);
                }
            }

            session.setResult(result);
            session.setState(GameState.WAITING);
            session.setSdPlayer(0);
            session.setSelectPoint(-1);
            session.setRedPlayer(null);
            session.setBlackPlayer(null);
            session.getChessData().fromFen(CChessUtil.INIT);

            LOG.info("[qisheng] Player {} disconnected mid-game at {}; survivor wins, board reset.",
                    player.getName().getString(), pos);
        }

        sm.evictPlayer(id);

        // Final roster push (the survivor's GUI sees the now-empty slots).
        GameBroadcaster.broadcastRoster(level, session, pos);
    }
}