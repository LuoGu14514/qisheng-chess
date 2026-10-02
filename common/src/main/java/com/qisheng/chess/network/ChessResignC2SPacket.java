package com.qisheng.chess.network;

import com.qisheng.chess.pvp.BoardKey;
import com.qisheng.chess.pvp.GameBroadcaster;
import com.qisheng.chess.pvp.GameResult;
import com.qisheng.chess.pvp.GameSession;
import com.qisheng.chess.pvp.GameState;
import com.qisheng.chess.pvp.SessionManager;
import dev.architectury.networking.NetworkManager.PacketContext;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * C2S: the player surrenders the current game.
 *
 * <p>Wire: empty (sender is the resigning player; the board position is
 * looked up via {@code SessionManager.getPlayerGame(senderId)}).
 *
 * <p>Effects:
 * <ul>
 *   <li>session.setResult(RED_WIN or BLACK_WIN depending on which side resigned)</li>
 *   <li>session.setState(FINISHED)</li>
 *   <li>broadcast gameover + a popup to every recipient with the result</li>
 * </ul>
 */
public final class ChessResignC2SPacket {

    private ChessResignC2SPacket() {}

    public static FriendlyByteBuf write() {
        return new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
    }

    public static void receive(FriendlyByteBuf buf, PacketContext ctx) {
        ServerPlayer sender = (ServerPlayer) ctx.getPlayer();
        if (sender == null) return;
        SessionManager sm = SessionManager.get();
        BoardKey key = sm.getPlayerGame(sender.getUUID());
        if (key == null) {
            GameBroadcaster.sendPopupTo(sender,
                    Component.literal("你不在对局中,无法认输。"),
                    PopupS2CPacket.Severity.WARN, 3);
            return;
        }
        GameSession session = sm.get(key);
        if (session == null) return;
        if (session.getState() != GameState.PLAYING) {
            GameBroadcaster.sendPopupTo(sender,
                    Component.literal("对局未在进行中,无法认输。"),
                    PopupS2CPacket.Severity.WARN, 3);
            return;
        }
        GameResult result;
        int role = session.getPlayerRole(sender.getUUID());
        if (role == 0) {
            result = GameResult.BLACK_WIN;
        } else if (role == 1) {
            result = GameResult.RED_WIN;
        } else {
            // Spectators cannot resign on behalf of a player.
            GameBroadcaster.sendPopupTo(sender,
                    Component.literal("旁观者不能认输。"),
                    PopupS2CPacket.Severity.WARN, 3);
            return;
        }
        // 棋盘可能在别的维度:广播一律用棋盘所在的世界。
        ServerLevel boardLevel = SessionManager.resolve(sender.getServer(), key, sender.serverLevel());
        BlockPos pos = key.pos();
        session.setResult(result);
        session.setState(GameState.FINISHED);
        sm.cancelDraw(key);
        GameBroadcaster.broadcastGameOver(boardLevel, session, pos, result);
        // Per-recipient popup naming the resigning player so the winner sees
        // "X 已认输" and the loser sees the same.
        Component msg = Component.literal(sender.getName().getString() + " 认输 — "
                + (result == GameResult.RED_WIN ? "红方胜" : "黑方胜"));
        GameBroadcaster.broadcastPopup(boardLevel, session, msg,
                PopupS2CPacket.Severity.INFO, 0);
    }
}