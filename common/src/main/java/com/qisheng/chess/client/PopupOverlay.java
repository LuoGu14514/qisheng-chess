package com.qisheng.chess.client;

import com.qisheng.chess.network.PopupS2CPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

/**
 * Stack of in-GUI pop-up chips at the top-centre of the chess board GUI.
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
 *   <li>A click that dismisses a chip is <b>consumed</b> ({@code true}), so it
 *       can no longer fall through and play a board move.</li>
 * </ul>
 *
 * <h2>Lifetime</h2>
 * The stack is <b>instance</b> state owned by {@link CChessBoardScreen} — one
 * stack per screen. It used to be a {@code static} deque that was never
 * cleared, so a sticky WARN / ERROR chip followed the player onto the next
 * board GUI (and {@code clear()} had zero call sites); the screen now drops
 * the stack in {@link CChessBoardScreen#removed()}.
 *
 * <p>{@link #show} stays static because the network layer holds no screen
 * reference: it routes the chip to the board GUI that is open right now. With
 * no board GUI up there is nowhere to paint it, so the chip is dropped rather
 * than left behind for the next screen to inherit.
 *
 * <p>Render integration: called from {@code CChessBoardScreen.render()} (the
 * only screen with this GUI), so no mixin into the global HUD pipeline is
 * needed. Anywhere else (main HUD, pause menu, …) the overlay is invisible —
 * which is what we want, since chess prompts only make sense at a board.
 */
public final class PopupOverlay {

    /** Maximum simultaneous chips visible at once. */
    private static final int MAX_VISIBLE = 6;
    /** Default auto-dismiss for INFO chips when caller passed 0. */
    private static final int DEFAULT_AUTO_SEC = 3;
    /** Default first pixel row of the stack. */
    public static final int DEFAULT_TOP = 8;
    /** Chip layout. */
    private static final int CHIP_PAD_X = 10;
    private static final int CHIP_PAD_Y = 6;
    private static final int CHIP_GAP   = 4;

    private final Deque<Chip> active = new ArrayDeque<>();

    public PopupOverlay() {}

    /**
     * Push a chip onto this screen's stack. {@code autoSec == 0} uses the
     * default for INFO chips and means "stick until clicked" for
     * WARN / ERROR.
     */
    public void push(Component text, PopupS2CPacket.Severity sev, int autoSec) {
        if (text == null) return;
        int seconds = autoSec > 0 ? autoSec
                       : (sev == PopupS2CPacket.Severity.INFO ? DEFAULT_AUTO_SEC : 0);
        active.addFirst(new Chip(text, sev, seconds));
        while (active.size() > MAX_VISIBLE) active.removeLast();
    }

    /**
     * Static entry point for the network layer
     * ({@link com.qisheng.chess.network.PopupS2CPacket}), which has no screen
     * reference. Delivers to the board GUI that is currently open; drops the
     * chip when there is none (see the class javadoc).
     */
    public static void show(Component text, PopupS2CPacket.Severity sev, int autoSec) {
        if (text == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof CChessBoardScreen screen) {
            screen.popups.push(text, sev, autoSec);
        }
    }

    /** Advance dismiss timers. */
    public void tick() {
        if (active.isEmpty()) return;
        long now = System.currentTimeMillis();
        active.removeIf(c -> c.expiresAt > 0 && now >= c.expiresAt);
    }

    /** Wipe all active chips (screen teardown). */
    public void clear() { active.clear(); }

    /**
     * Render all chips inside the given screen viewport. Anchor: top-centre
     * of the visible area. Chips stack downward; the newest is at the top.
     *
     * <p>Intended to be called at the very end of
     * {@link net.minecraft.client.gui.screens.Screen#render} so the chips
     * paint over everything else (including the chess board).
     *
     * @param topY first pixel row of the stack — the screen pushes this below
     *             an active {@link ActionPopup} so the two stacks, whose
     *             hit-boxes both sit centre-screen, cannot overlap.
     */
    public void render(GuiGraphics gfx, int screenWidth, int topY) {
        if (active.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        int y = topY;
        for (Chip c : active) {
            int w = mc.font.width(c.text);
            int chipW = w + 2 * CHIP_PAD_X;
            int chipH = mc.font.lineHeight + 2 * CHIP_PAD_Y;
            int x0 = (screenWidth - chipW) / 2;
            int bg = c.severity.bgArgb();
            int border = c.severity.borderArgb();
            DrawUtil.outline(gfx, x0, y, chipW, chipH, border, bg);
            gfx.drawString(mc.font, c.text, x0 + CHIP_PAD_X, y + CHIP_PAD_Y, c.severity.textArgb());
            // Store rect for click hit-test.
            c.x0 = x0; c.y0 = y; c.x1 = x0 + chipW; c.y1 = y + chipH;
            c.placed = true;
            y += chipH + CHIP_GAP;
        }
    }

    /**
     * Dismiss the chip under the cursor, if any.
     *
     * @return {@code true} when a chip was actually dismissed — the caller
     *         must swallow that click instead of letting it reach the board.
     */
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0 || active.isEmpty()) return false;
        Iterator<Chip> it = active.iterator();
        while (it.hasNext()) {
            Chip c = it.next();
            if (!c.placed) continue;   // rect is only meaningful after a render
            if (c.x0 <= mx && mx <= c.x1 && c.y0 <= my && my <= c.y1) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    // ---------- Chip ----------

    private static class Chip {
        final Component text;
        final PopupS2CPacket.Severity severity;
        final long expiresAt;       // 0 = stick
        /**
         * Rect captured during the last render. {@code placed} guards the
         * first frame: before it, the rect is still 0,0,0,0 and a click in
         * the top-left corner would dismiss an invisible chip.
         */
        boolean placed = false;
        int x0, y0, x1, y1;

        Chip(Component text, PopupS2CPacket.Severity sev, int autoSec) {
            this.text = text;
            this.severity = sev;
            this.expiresAt = autoSec > 0
                    ? System.currentTimeMillis() + autoSec * 1000L
                    : 0L;
        }
    }
}
