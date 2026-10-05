package com.qisheng.chess.network;

import com.qisheng.chess.client.CChessBoardScreen;
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
 *       自方棋子且对局未结束时，服务端才会跟一段位图</li>
 *   <li>buf[N+6..]（可选）合法落点位图，每字节低位先行 (xiangqi = 32 bytes,
 *       international = 8 bytes)。客户端可直接用，不必再调 {@code canMove}</li>
 *   <li>buf末尾-4..-1 = lastMoveSource + lastMoveDest (两个 short，-1 表示无)</li>
 *   <li>buf末尾变长 = variantId (UTF-8 string)。{@code "xiangqi"} 或
 *       {@code "international"}。旧 v0.6 客户端无此字段 → 读完后会抛
 *       EOFException，此时调用方应回落到 xiangqi（v0.3.1 的兼容契约）</li>
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

    public static void receive(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        String fen = buf.readUtf();
        String variantId = buf.readUtf();
        int sdPlayer = buf.readByte();
        int stateOrd = buf.readByte();
        int selectPoint = buf.readShort();
        boolean hasDests = buf.readBoolean();
        boolean[] legalDests = hasDests
                ? LegalDestsBitmap.read(buf, totalSquaresFor(variantId))
                : null;
        int lastSrc = buf.readShort();
        int lastDst = buf.readShort();

        var mc = Minecraft.getInstance();
        if (mc.level != null) {
            mc.execute(() -> {

                if (mc.screen instanceof CChessBoardScreen screen) {
                    screen.applySync(fen, sdPlayer, stateOrd, selectPoint, legalDests, lastSrc, lastDst, variantId);
                }
            });
        }
    }

    /** Resolve variantId → bitmap size without going through BoardRegistry (client has no engine module). */
    private static int totalSquaresFor(String variantId) {
        if (variantId == null) return 256;
        return switch (variantId) {
            case "xiangqi" -> 256;
            case "international" -> 64;
            default -> 256;
        };
    }
}