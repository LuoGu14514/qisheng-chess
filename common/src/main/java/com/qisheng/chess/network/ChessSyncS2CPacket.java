package com.qisheng.chess.network;

import com.qisheng.chess.client.CChessBoardScreen;
import dev.architectury.networking.NetworkManager.PacketContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

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
 *   <li>buf[N+5]    = {@code hasDests} byte (0/1)。仅当 selectedSq 为 sdPlayer
 *       自方棋子且对局未结束时，服务端才会跟一段 32 字节的位图</li>
 *   <li>buf[N+6..N+37]（可选）256 格合法落点位图，每字节低位先行
 *       (256 bits = 32 bytes)。客户端可直接用，不必再调 {@code canMove}</li>
 *   <li>buf[N+38..N+39] = lastMoveSource (short, -1 表示无最近一步)</li>
 *   <li>buf[N+40..N+41] = lastMoveDest (short, -1 表示无最近一步)</li>
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
        boolean hasDests = buf.readBoolean();
        boolean[] legalDests = hasDests ? LegalDestsBitmap.read(buf) : null;
        int lastSrc = buf.readShort();
        int lastDst = buf.readShort();

        var mc = Minecraft.getInstance();
        if (mc.level != null) {
            mc.execute(() -> {

                if (mc.screen instanceof CChessBoardScreen screen) {
                    screen.applySync(fen, sdPlayer, stateOrd, selectPoint, legalDests, lastSrc, lastDst);
                }
            });
        }
    }
}