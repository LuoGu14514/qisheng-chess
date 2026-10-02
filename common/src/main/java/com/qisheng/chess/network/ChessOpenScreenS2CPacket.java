package com.qisheng.chess.network;

import com.qisheng.chess.client.CChessBoardScreen;
import dev.architectury.networking.NetworkManager.PacketContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/**
 * S2C: 服务端告诉客户端打开棋盘 GUI。
 *
 * 协议:
 *  - buf[0-7]    = pos (BlockPos)
 *  - buf[8-23]   = self UUID (16 bytes)
 *  - buf[24-N]   = FEN (UTF-8 string,长度字节)
 *  - buf[N+1]    = sdPlayer (0=红方回合,1=黑方)
 *  - buf[N+2]    = state ordinal
 *  - buf[N+3..4] = selectPoint (short)
 *  - buf[N+5]    = myRole byte (-1=旁观,0=红,1=黑)
 *  - buf[N+6]    = {@code hasDests} byte (0/1)，仅当服务端下发了合法落点位图时为 1
 *  - buf[N+7..N+38]（可选）256 格合法落点位图，与 {@link ChessSyncS2CPacket} 同格式
 *  - buf[N+39..N+40] = lastMoveSource (short, -1 表示无最近一步)
 *  - buf[N+41..N+42] = lastMoveDest (short, -1 表示无最近一步)
 *  - buf[N+43]   = flipped byte (0/1)：棋盘 BlockState.facing==SOUTH 时为 1，
 *                  客户端在 {@code viewerIsBlack} 之上再翻 180°，让红方显示在上方。
 *
 * 客户端收到后,必须通过 {@link Minecraft#execute(Runnable)} 把
 * {@code setScreen} 派发到渲染线程执行。Architectury 的 S2C 接收回调
 * 跑在网络线程(Netty Local Client IO),直接调 setScreen 会触发
 * "Rendersystem called from wrong thread" 异常 + fabric-screen-api-v1
 * "screen has not been correctly initialised" 崩溃。
 */
public class ChessOpenScreenS2CPacket {

    public static void receive(FriendlyByteBuf buf, PacketContext ctx) {
        BlockPos pos = buf.readBlockPos();
        UUID self = buf.readUUID();
        String fen = buf.readUtf();
        int sdPlayer = buf.readByte();
        int stateOrd = buf.readByte();
        int selectPoint = buf.readShort();
        int myRole = buf.readByte();   // -1=spec, 0=red, 1=black
        boolean hasDests = buf.readBoolean();
        boolean[] legalDests = hasDests ? LegalDestsBitmap.read(buf) : null;
        int lastSrc = buf.readShort();
        int lastDst = buf.readShort();
        boolean flipped = buf.readBoolean();

        var mc = Minecraft.getInstance();
        if (mc.player == null) return;

        // Marshal setScreen() onto the render thread. Both execute() and
        // send() enqueue to the render-thread task queue; execute() is the
        // post-1.19.4 preferred name (send() is an alias).
        mc.execute(() -> mc.setScreen(
                new CChessBoardScreen(pos, self, fen, sdPlayer, stateOrd, selectPoint, myRole,
                        legalDests, lastSrc, lastDst, flipped)));
    }
}