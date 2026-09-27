package com.qisheng.chess.network;

import com.qisheng.chess.client.CChessBoardScreen;
import dev.architectury.networking.NetworkManager.PacketContext;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/**
 * S2C: server pushes the player roster for a chess session.
 *
 * <p>Wire format (CHESS_PLAYER_INFO):
 * <pre>
 *   buf[0-7]       = BlockPos (board identity)
 *   buf[8]         = bool hasRed
 *   buf[9..24]     = if hasRed: red UUID
 *   buf[9..N+24]   = if hasRed: red name (length-prefixed UTF-8)
 *   buf[after red] = bool hasBlack
 *   buf[after]     = if hasBlack: black UUID + name
 *   buf[after]     = int specCount
 *   buf[after]     = specCount × (UUID + UTF-8 name)
 * </pre>
 *
 * <p>Sent whenever the roster changes (someone joins, leaves, swaps,
 * disconnects, or after {@code CHESS_OPEN_SCREEN} so the GUI can paint
 * the spectator list immediately).
 */
public class SpectatorListS2CPacket {

    public static class PlayerEntry {
        public final UUID id;
        public final String name;
        public PlayerEntry(UUID id, String name) { this.id = id; this.name = name; }
    }

    public static class Roster {
        public final BlockPos pos;
        public final PlayerEntry red;
        public final PlayerEntry black;
        public final PlayerEntry[] spectators;
        public Roster(BlockPos pos, PlayerEntry red, PlayerEntry black, PlayerEntry[] specs) {
            this.pos = pos;
            this.red = red;
            this.black = black;
            this.spectators = specs == null ? new PlayerEntry[0] : specs;
        }
    }

    public static Roster read(FriendlyByteBuf buf) {
        BlockPos p = buf.readBlockPos();
        PlayerEntry red = null, black = null;
        if (buf.readBoolean()) {
            red = new PlayerEntry(buf.readUUID(), buf.readUtf());
        }
        if (buf.readBoolean()) {
            black = new PlayerEntry(buf.readUUID(), buf.readUtf());
        }
        int n = buf.readInt();
        PlayerEntry[] specs = new PlayerEntry[n];
        for (int i = 0; i < n; i++) {
            specs[i] = new PlayerEntry(buf.readUUID(), buf.readUtf());
        }
        return new Roster(p, red, black, specs);
    }

    /** Encode a roster to a fresh buf — used by {@link com.qisheng.chess.pvp.GameBroadcaster}. */
    public static FriendlyByteBuf write(BlockPos pos, PlayerEntry red, PlayerEntry black, PlayerEntry[] specs) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeBlockPos(pos);
        buf.writeBoolean(red != null);
        if (red != null) { buf.writeUUID(red.id); buf.writeUtf(red.name == null ? "" : red.name); }
        buf.writeBoolean(black != null);
        if (black != null) { buf.writeUUID(black.id); buf.writeUtf(black.name == null ? "" : black.name); }
        int n = specs == null ? 0 : specs.length;
        buf.writeInt(n);
        for (int i = 0; i < n; i++) {
            buf.writeUUID(specs[i].id);
            buf.writeUtf(specs[i].name == null ? "" : specs[i].name);
        }
        return buf;
    }

    public static void receive(FriendlyByteBuf buf, PacketContext ctx) {
        Roster roster = read(buf);
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.screen instanceof CChessBoardScreen scr
                    && scr.getBoardPos().equals(roster.pos)) {
                scr.applyRoster(roster);
            }
        });
    }
}