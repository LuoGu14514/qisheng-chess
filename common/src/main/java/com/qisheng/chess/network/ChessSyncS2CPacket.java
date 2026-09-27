package com.qisheng.chess.network;

import com.qisheng.chess.client.CChessBoardScreen;
import dev.architectury.networking.NetworkManager.PacketContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;

/**
 * S2C 棋盘状态同步(服务端广播给两个玩家 + 旁观者)
 *
 * <p>协议:
 * <ul>
 *   <li>buf[0-7]    = pos (BlockPos)</li>
 *   <li>buf[8-N]    = FEN (UTF-8 string,长度字节)</li>
 *   <li>buf[N+1]    = sdPlayer (0=红方回合,1=黑方)</li>
 *   <li>buf[N+2]    = state ordinal (0=WAITING 1=PLAYING 2=FINISHED)</li>
 *   <li>buf[N+3..4] = selectPoint (short, -1 表示未选子)</li>
 * </ul>
 *
 * <p>客户端收到后:
 * <ul>
 *   <li>if a {@link CChessBoardScreen} is currently up, call
 *       {@code applySync} to refresh it</li>
 * </ul>
 *
 * <p>All UI-side updates must run on the render thread, so we marshal
 * through {@link Minecraft#execute(Runnable)}.
 */
public class ChessSyncS2CPacket {

    public static void receive(FriendlyByteBuf buf, PacketContext ctx) {
        BlockPos pos = buf.readBlockPos();
        String fen = buf.readUtf();
        int sdPlayer = buf.readByte();
        int stateOrd = buf.readByte();
        int selectPoint = buf.readShort();

        var mc = Minecraft.getInstance();
        if (mc.level != null) {
            mc.execute(() -> {

                if (mc.screen instanceof CChessBoardScreen screen) {
                    screen.applySync(fen, sdPlayer, stateOrd, selectPoint);
                }
            });
        }

        // One-line summary for the player to confirm sync.
        String sdStr = sdPlayer == 0 ? "红方" : "黑方";
        String stateStr = switch (stateOrd) {
            case 0 -> "等待玩家";
            case 1 -> "对局中";
            case 2 -> "已结束";
            default -> "未知";
        };
        ctx.getPlayer().sendSystemMessage(Component.literal(
                "§7[启升棋同步] " + sdStr + " | " + stateStr + " | FEN=" + fen));
    }
}