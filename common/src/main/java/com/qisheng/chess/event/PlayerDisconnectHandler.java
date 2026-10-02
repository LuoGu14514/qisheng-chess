package com.qisheng.chess.event;

import com.qisheng.chess.network.PopupS2CPacket;
import com.qisheng.chess.pvp.BoardKey;
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
 *   <li>Resolve the board the leaver sat at (dimension-aware {@link BoardKey})</li>
 *   <li>If the game was PLAYING, mark it finished with the survivor as winner and
 *       push the final position + a "you win by forfeit" popup to everyone watching</li>
 *   <li>Reset the board to WAITING with {@link CChessUtil#INIT} so two new players
 *       can start immediately</li>
 *   <li>Evict <em>both</em> seats from {@code playerInGame}. The survivor must be
 *       evicted too: the stale mapping otherwise makes {@code joinGame} reject them
 *       from their own board ("你已在其它棋盘对局中") with no way to recover.</li>
 *   <li>Broadcast a fresh sync + roster so the survivor's GUI shows "no opponent yet"</li>
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
        ServerLevel senderLevel = player.serverLevel();

        // ---- Spectator cleanup — separate from the forfeit path ----
        BoardKey spectating = sm.getPlayerSpectating(id);
        if (spectating != null) {
            GameSession s = sm.get(spectating);
            sm.removePlayerSpectating(id);
            if (s != null) {
                s.removeSpectator(id);
                ServerLevel boardLevel = SessionManager.resolve(player.getServer(), spectating, senderLevel);
                if (boardLevel != null) {
                    GameBroadcaster.broadcastRoster(boardLevel, s, spectating.pos());
                }
            }
        }

        // ---- Seat forfeit ----
        BoardKey key = sm.getPlayerGame(id);
        if (key == null) {
            // Was only a spectator (or stale entry) — already cleaned up.
            return;
        }

        BlockPos pos = key.pos();
        ServerLevel level = SessionManager.resolve(player.getServer(), key, senderLevel);
        GameSession session = sm.get(key);
        if (level == null || session == null) {
            sm.evictPlayer(id);
            return;
        }

        if (!session.containsPlayer(id)) {
            sm.evictPlayer(id);
            return;
        }

        boolean wasPlaying = session.getState() == GameState.PLAYING;
        UUID survivorId = null;
        GameResult result = GameResult.ABANDONED;

        if (wasPlaying) {
            boolean leaverIsRed = id.equals(session.getRedPlayer());
            survivorId = leaverIsRed ? session.getBlackPlayer() : session.getRedPlayer();
            result = leaverIsRed ? GameResult.BLACK_WIN : GameResult.RED_WIN;

            session.setResult(result);
            session.setState(GameState.FINISHED);
            if (survivorId != null) {
                ServerPlayer survivor = GameBroadcaster.findPlayer(level, survivorId);
                if (survivor != null) {
                    GameBroadcaster.sendPopupTo(survivor,
                            Component.literal(player.getName().getString()
                                    + " 已断线 — 你赢了(对方判负)。"),
                            PopupS2CPacket.Severity.INFO, 5);
                }
            }
            // Final position + result popup for everyone still watching.
            GameBroadcaster.broadcastGameOver(level, session, pos, result);
        }

        // ---- Reset the board so it is immediately reusable ----
        UUID redId = session.getRedPlayer();
        UUID blackId = session.getBlackPlayer();
        session.setState(GameState.WAITING);
        session.setResult(GameResult.ONGOING);
        session.setSdPlayer(0);
        session.setSelectPoint(-1);
        session.setRedPlayer(null);
        session.setBlackPlayer(null);
        session.getChessData().fromFen(CChessUtil.INIT);

        sm.evictPlayer(id);
        // The survivor keeps no claim on a board that was just reset.
        if (survivorId != null) sm.evictPlayer(survivorId);
        if (redId != null && !redId.equals(id) && !redId.equals(survivorId)) sm.evictPlayer(redId);
        if (blackId != null && !blackId.equals(id) && !blackId.equals(survivorId)) sm.evictPlayer(blackId);

        if (wasPlaying) {
            LOG.info("[qisheng] Player {} disconnected mid-game at {} ({}); survivor {} wins, board reset.",
                    player.getName().getString(), pos, key.describe(),
                    survivorId == null ? "<none>" : survivorId);
        } else {
            LOG.info("[qisheng] Player {} left the waiting board at {} ({}).",
                    player.getName().getString(), pos, key.describe());
        }

        // Fresh sync + roster: the survivor's GUI now shows an empty seat.
        GameBroadcaster.broadcastSync(level, session, pos);
        GameBroadcaster.broadcastRoster(level, session, pos);
    }
}
