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

    /**
     * Upper bound for the spectator count on the wire.
     *
     * <p>The decoder allocates an array from the incoming {@code int}, so an
     * unbounded value let anyone with a connection OOM the client. No real
     * server exceeds this, and the encoder clamps to it as well.
     */
    public static final int MAX_SPECTATORS = 4096;

    /** Minecraft usernames are ≤16 chars; 64 bytes is a generous, bounded wire cap. */
    private static final int NAME_MAX_BYTES = 64;

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
            red = new PlayerEntry(buf.readUUID(), buf.readUtf(NAME_MAX_BYTES));
        }
        if (buf.readBoolean()) {
            black = new PlayerEntry(buf.readUUID(), buf.readUtf(NAME_MAX_BYTES));
        }
        int n = buf.readInt();
        // Bound before allocating: a malformed or hostile header used to let the
        // sender pick an arbitrary length and OOM the client. A Minecraft server
        // cannot have more players than this, so anything larger is bogus.
        if (n < 0 || n > MAX_SPECTATORS) {
            throw new io.netty.handler.codec.DecoderException(
                    "spectator count out of range: " + n);
        }
        PlayerEntry[] specs = new PlayerEntry[n];
        for (int i = 0; i < n; i++) {
            specs[i] = new PlayerEntry(buf.readUUID(), buf.readUtf(NAME_MAX_BYTES));
        }
        return new Roster(p, red, black, specs);
    }

    /**
     * Encode a roster into a fresh buf.
     *
     * <p>Kept for callers that own the buffer. Broadcasting must use
     * {@link #writeInto} instead so every recipient gets its own buffer —
     * see the class javadoc of {@link com.qisheng.chess.pvp.GameBroadcaster}.
     */
    public static FriendlyByteBuf write(BlockPos pos, PlayerEntry red, PlayerEntry black, PlayerEntry[] specs) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        writeInto(buf, pos, red, black, specs);
        return buf;
    }

    /** Encode a roster into a caller-provided buffer. */
    public static void writeInto(FriendlyByteBuf buf, BlockPos pos, PlayerEntry red, PlayerEntry black,
                                 PlayerEntry[] specs) {
        buf.writeBlockPos(pos);
        buf.writeBoolean(red != null);
        if (red != null) { buf.writeUUID(red.id); buf.writeUtf(clamp(red.name)); }
        buf.writeBoolean(black != null);
        if (black != null) { buf.writeUUID(black.id); buf.writeUtf(clamp(black.name)); }
        int n = specs == null ? 0 : Math.min(specs.length, MAX_SPECTATORS);
        buf.writeInt(n);
        for (int i = 0; i < n; i++) {
            buf.writeUUID(specs[i].id);
            buf.writeUtf(clamp(specs[i].name));
        }
    }

    /** Minecraft usernames cap at 16 chars; the wire limit is 32767 but be tidy. */
    private static String clamp(String s) {
        if (s == null) return "";
        return s.length() <= 64 ? s : s.substring(0, 64);
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