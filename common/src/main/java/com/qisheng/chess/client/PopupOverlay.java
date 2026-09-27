package com.qisheng.chess.client;

import com.qisheng.chess.network.PopupS2CPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Client-side singleton that renders stacked in-GUI pop-up chips at the
 * top-centre of the active {@link net.minecraft.client.gui.screens.Screen}.
 *
 * <p>Replaces the old "spam chat with [qisheng] ..." messages that the
 * server used to send via {@code sendSystemMessage}. All server-side
 * prompts now flow through {@link com.qisheng.chess.network.PopupS2CPacket}
 * and land here.
 *
 * <h2>Behaviour</h2>
 * <ul>
 *   <li>Chips stack vertically, newest on top.</li>
 *   <li>INFO chips auto-dismiss after 3 s (configurable).</li>
 *   <li>WARN / ERROR chips stick until the player clicks them, then dismiss.</li>
 *   <li>One dismiss = one chip; click position is matched to the chip rect.</li>
 *   <li>Limit: 6 visible chips. Older ones drop off the bottom.</li>
 * </ul>
 *
 * <p>Render integration: lives in {@code CChessBoardScreen.render()} (only
 * screen with this GUI), so we don't need a mixin to inject into the
 * global HUD pipeline. Anywhere else (main HUD, pause menu, etc.) the
 * overlay is hidden — which is what we want, since the chess prompts
 * only make sense while looking at a board.
 */
public final class PopupOverlay {

    /** Maximum simultaneous chips visible at once. */
    private static final int MAX_VISIBLE = 6;
    /** Default auto-dismiss for INFO chips when caller passed 0. */
    private static final int DEFAULT_AUTO_SEC = 3;
    /** Chip layout. */
    private static final int CHIP_PAD_X = 10;
    private static final int CHIP_PAD_Y = 6;
    private static final int CHIP_GAP   = 4;

    private static final Deque<Chip> ACTIVE = new ArrayDeque<>();

    private PopupOverlay() {}

    /**
     * Push a chip. {@code autoSec == 0} uses the default for INFO chips and
     * means "stick until clicked" for WARN / ERROR.
     */
    public static void show(Component text, PopupS2CPacket.Severity sev, int autoSec) {
        if (text == null) return;
        int seconds = autoSec > 0 ? autoSec
                       : (sev == PopupS2CPacket.Severity.INFO ? DEFAULT_AUTO_SEC : 0);
        ACTIVE.addFirst(new Chip(text, sev, seconds));
        while (ACTIVE.size() > MAX_VISIBLE) ACTIVE.removeLast();
    }

    /** Advance dismiss timers. */
    public static void tick() {
        if (ACTIVE.isEmpty()) return;
        long now = System.currentTimeMillis();
        ACTIVE.removeIf(c -> c.shouldDismiss(now));
    }

    /** Wipe all active chips (used when leaving the GUI). */
    public static void clear() { ACTIVE.clear(); }

    /**
     * Render all chips inside the given Screen's viewport. Anchor: top-centre
     * of the visible area. Chips stack downward; the newest is at the top.
     *
     * <p>Intended to be called at the very end of
     * {@link net.minecraft.client.gui.screens.Screen#render} so the chips
     * paint over everything else (including the chess board).
     */
    public static void render(GuiGraphics gfx, int screenWidth) {
        if (ACTIVE.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        int y = 8;
        for (Chip c : ACTIVE) {
            int w = mc.font.width(c.text);
            int chipW = w + 2 * CHIP_PAD_X;
            int chipH = mc.font.lineHeight + 2 * CHIP_PAD_Y;
            int x0 = (screenWidth - chipW) / 2;
            int bg = c.severity.bgArgb();
            int border = c.severity.borderArgb();
            gfx.fill(x0 - 1, y - 1, x0 + chipW + 1, y + chipH + 1, border);
            gfx.fill(x0, y, x0 + chipW, y + chipH, bg);
            gfx.drawString(mc.font, c.text, x0 + CHIP_PAD_X, y + CHIP_PAD_Y, c.severity.textArgb());
            // Store rect for click hit-test.
            c.x0 = x0; c.y0 = y; c.x1 = x0 + chipW; c.y1 = y + chipH;
            y += chipH + CHIP_GAP;
        }
    }

    /**
     * Returns true if a chip was dismissed by this click (so the caller
     * can stop further propagation if desired).
     */
    public static boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return false;
        ACTIVE.removeIf(c -> {
            if (c.x0 <= mx && mx <= c.x1 && c.y0 <= my && my <= c.y1) {
                c.dismissed = true;
                return true;
            }
            return false;
        });
        return false;
    }

    // ---------- Chip ----------

    private static class Chip {
        final Component text;
        final PopupS2CPacket.Severity severity;
        final long expiresAt;       // 0 = stick
        boolean dismissed = false;
        // Rect captured during last render (so click hit-test uses the
        // same coordinates the user sees).
        int x0, y0, x1, y1;

        Chip(Component text, PopupS2CPacket.Severity sev, int autoSec) {
            this.text = text;
            this.severity = sev;
            this.expiresAt = autoSec > 0
                    ? System.currentTimeMillis() + autoSec * 1000L
                    : 0L;
        }

        boolean shouldDismiss(long now) {
            return dismissed || (expiresAt > 0 && now >= expiresAt);
        }
    }
}