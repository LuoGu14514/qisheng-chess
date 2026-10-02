package com.qisheng.chess.client;

import com.qisheng.chess.network.PopupS2CPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * In-GUI action popup (the buttoned sibling of {@link PopupOverlay}): a
 * centred chip with a message and an accept / reject button pair.
 *
 * <h2>Behaviour</h2>
 * <ul>
 *   <li><b>One popup at a time.</b> Only the newest popup is drawn and only
 *       the newest popup is hit-tested, so render and click handling always
 *       agree — previously up to three were painted while only the first was
 *       clickable, leaving "visible but dead" buttons that let the click fall
 *       through onto the board.</li>
 *   <li>A click on the dialog body is consumed as well, so it cannot play a
 *       board move behind the dialog.</li>
 *   <li>Button captions are the {@code acceptLabel} / {@code rejectLabel} the
 *       caller passed to {@link #show} (the old render hard-coded
 *       "接受" / "拒绝" and ignored them).</li>
 *   <li>{@link #dismiss(Tag)} retires an invitation as soon as its outcome is
 *       known (accepted / rejected / cancelled), so its buttons can no longer
 *       fire a response against stale server state.</li>
 * </ul>
 *
 * <h2>Lifetime</h2>
 * Instance state owned by {@link CChessBoardScreen}. The old {@code static}
 * deque never got cleared: the {@link Runnable}s kept the closed screen alive
 * and a live invitation followed the player onto the next board GUI.
 * {@link CChessBoardScreen#removed()} clears the stack.
 */
public final class ActionPopup {

    /** What a popup is asking about — used to retire stale invitations. */
    public enum Tag { DRAW_INVITE, SWITCH_INVITE, CONFIRM }

    /** Top of the popup band (below the {@link PopupOverlay} stack). */
    public static final int TOP_Y = 40;
    private static final int CHIP_PAD_X = 12;
    private static final int PAD_Y = 8;
    private static final int BTN_W = 60;
    /** Vertical room reserved under the message for the button row. */
    private static final int BTN_BAND_H = 24;
    private static final int MAX_STACK = 3;

    private final Deque<Action> active = new ArrayDeque<>();

    public ActionPopup() {}

    /**
     * Show a popup that is not tied to a server invitation (e.g. the resign
     * confirmation).
     */
    public void show(Component text, PopupS2CPacket.Severity sev,
                     String acceptLabel, String rejectLabel,
                     Runnable onAccept, Runnable onReject) {
        show(Tag.CONFIRM, text, sev, acceptLabel, rejectLabel, onAccept, onReject);
    }

    public void show(Tag tag, Component text, PopupS2CPacket.Severity sev,
                     String acceptLabel, String rejectLabel,
                     Runnable onAccept, Runnable onReject) {
        active.addFirst(new Action(tag, text, sev, acceptLabel, rejectLabel, onAccept, onReject));
        while (active.size() > MAX_STACK) active.removeLast();
    }

    /**
     * Retire every pending popup carrying {@code tag}. Called when the server
     * reports the outcome of that invitation — the invite can be CANCELLED
     * (draw withdrawn, opponent left, …) and the buttons must not stay live.
     */
    public void dismiss(Tag tag) {
        if (active.isEmpty()) return;
        active.removeIf(a -> a.tag == tag);
    }

    public void clear() { active.clear(); }

    /** Kept for the render loop; action popups never time out by themselves. */
    public void tick() {
        // Dismissal is driven by server results (dismiss(Tag)), not by a timer.
    }

    /**
     * Bottom edge (exclusive) of the drawn popup, or 0 when nothing is shown.
     * The screen uses it to place the {@link PopupOverlay} stack underneath so
     * the two stacks cannot overlap.
     */
    public int renderedBottom() {
        if (active.isEmpty()) return 0;
        return TOP_Y + chipHeight() + 1;
    }

    public void render(GuiGraphics gfx, int screenWidth) {
        Action a = active.peekFirst();   // …and it is the only one drawn
        if (a == null) return;
        Minecraft mc = Minecraft.getInstance();
        int w = mc.font.width(a.text);
        int chipW = w + 2 * CHIP_PAD_X;
        int chipH = chipHeight();
        int x0 = (screenWidth - chipW) / 2;
        // Chip background
        DrawUtil.outline(gfx, x0, TOP_Y, chipW, chipH,
                a.severity.borderArgb(), a.severity.bgArgb());
        // Text
        int tx = x0 + (chipW - w) / 2;
        int ty = TOP_Y + PAD_Y;
        gfx.drawString(mc.font, a.text, tx, ty, a.severity.textArgb());
        // Buttons — captions come from the caller, not from hard-coded text.
        int by = ty + mc.font.lineHeight + 6;
        int btnH = mc.font.lineHeight + 4;
        int acceptX = x0 + chipW / 2 - BTN_W - 4;
        int rejectX = x0 + chipW / 2 + 4;
        a.x0 = x0; a.y0 = TOP_Y; a.x1 = x0 + chipW; a.y1 = TOP_Y + chipH;
        a.placed = true;
        a.acceptX0 = acceptX; a.acceptY0 = by;
        a.acceptX1 = acceptX + BTN_W; a.acceptY1 = by + btnH;
        a.rejectX0 = rejectX; a.rejectY0 = by;
        a.rejectX1 = rejectX + BTN_W; a.rejectY1 = by + btnH;
        drawButton(gfx, acceptX, by, BTN_W, btnH,
                labelOr(a.acceptLabel,
                        Component.translatable("qisheng.chess.popup.accept").getString()),
                0xFF6FA85F, 0xFFFFFFFF);
        drawButton(gfx, rejectX, by, BTN_W, btnH,
                labelOr(a.rejectLabel,
                        Component.translatable("qisheng.chess.popup.reject").getString()),
                0xFFA85F5F, 0xFFFFFFFF);
    }

    private static String labelOr(String label, String fallback) {
        return (label == null || label.isEmpty()) ? fallback : label;
    }

    private static int chipHeight() {
        return Minecraft.getInstance().font.lineHeight + 2 * PAD_Y + BTN_BAND_H;
    }

    private static void drawButton(GuiGraphics gfx, int x, int y, int w, int h,
                                   String label, int bg, int text) {
        gfx.fill(x, y, x + w, y + h, bg);
        DrawUtil.border(gfx, x, y, w, h, 0xFF000000,
                DrawUtil.SIDE_TOP | DrawUtil.SIDE_BOTTOM);
        Minecraft mc = Minecraft.getInstance();
        int tw = mc.font.width(label);
        gfx.drawString(mc.font, label, x + (w - tw) / 2, y + 2, text);
    }

    /**
     * Hit-test exactly the popup {@link #render} drew (the topmost one). A
     * click anywhere on that dialog is consumed so it cannot reach the board.
     */
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return false;
        Action a = active.peekFirst();
        if (a == null || !a.placed) return false;
        if (inside(mx, my, a.acceptX0, a.acceptY0, a.acceptX1, a.acceptY1)) {
            active.removeFirst();
            a.onAccept.run();
            return true;
        }
        if (inside(mx, my, a.rejectX0, a.rejectY0, a.rejectX1, a.rejectY1)) {
            active.removeFirst();
            a.onReject.run();
            return true;
        }
        return inside(mx, my, a.x0, a.y0, a.x1, a.y1);
    }

    private static boolean inside(double mx, double my, int x0, int y0, int x1, int y1) {
        return x0 <= mx && mx <= x1 && y0 <= my && my <= y1;
    }

    private static class Action {
        final Tag tag;
        final Component text;
        final PopupS2CPacket.Severity severity;
        final String acceptLabel;
        final String rejectLabel;
        final Runnable onAccept;
        final Runnable onReject;
        boolean placed = false;
        int x0, y0, x1, y1;
        int acceptX0, acceptY0, acceptX1, acceptY1;
        int rejectX0, rejectY0, rejectX1, rejectY1;
        Action(Tag tag, Component text, PopupS2CPacket.Severity sev,
               String acceptLabel, String rejectLabel,
               Runnable onAccept, Runnable onReject) {
            this.tag = tag;
            this.text = text; this.severity = sev;
            this.acceptLabel = acceptLabel; this.rejectLabel = rejectLabel;
            this.onAccept = onAccept; this.onReject = onReject;
        }
    }
}
