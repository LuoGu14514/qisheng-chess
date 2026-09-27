package com.qisheng.chess.network;

import com.qisheng.chess.client.PopupOverlay;
import dev.architectury.networking.NetworkManager.PacketContext;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;

/**
 * S2C: server pushes a short Chinese prompt that the client renders as an
 * in-GUI centred overlay (see {@link PopupOverlay}) instead of dumping it
 * into the chat box.
 *
 * <p>Wire format:
 * <pre>
 *   buf[0]   = severity byte (0 = INFO, 1 = WARN, 2 = ERROR)
 *   buf[1-]  = UTF-8 text (length-prefixed, writeUtf)
 *   buf[N+1] = auto-dismiss in seconds (0 = stick until clicked)
 * </pre>
 *
 * <p>Marshalling: must run the {@link Minecraft#execute} dance to push the
 * overlay mutation onto the render thread (same reason as
 * {@link ChessOpenScreenS2CPacket}).
 */
public class PopupS2CPacket {

    public enum Severity { INFO, WARN, ERROR;
        // Colours used by PopupOverlay — kept here so server & client can't drift.
        public int bgArgb() { return switch (this) {
            case INFO  -> 0xCC1F3A2A;
            case WARN  -> 0xCC5A4A1F;
            case ERROR -> 0xCC6A1F1F;
        };}
        public int borderArgb() { return switch (this) {
            case INFO  -> 0xFF8FE3A1;
            case WARN  -> 0xFFE3C88F;
            case ERROR -> 0xFFE38F8F;
        };}
        public int textArgb() { return switch (this) {
            case INFO  -> 0xFFEFEFEF;
            case WARN  -> 0xFFFFFFFF;
            case ERROR -> 0xFFFFFFFF;
        };}
    }

    public static void receive(FriendlyByteBuf buf, PacketContext ctx) {
        Severity sev = Severity.values()[buf.readByte()];
        String text = buf.readUtf();
        int autoSec = buf.readByte();

        var mc = Minecraft.getInstance();
        mc.execute(() -> PopupOverlay.show(Component.literal(text), sev, autoSec));
    }
}