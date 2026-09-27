package com.qisheng.chess.client;

import com.qisheng.chess.network.PopupS2CPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * In-GUI action popup (extends {@link PopupOverlay} model): a centred
 * chip with a message, optional Accept / Reject buttons. Stays on screen
 * until the player clicks one of the buttons (or the chip itself).
 *
 * <p>Used for invite flows: draw, role-switch, etc. Decoupled from
 * {@link PopupOverlay} (which only carries plain text) so the two stacks
 * don't collide visually.
 */
public final class ActionPopup {

    private static final Deque<Action> ACTIVE = new ArrayDeque<>();

    private ActionPopup() {}

    public static void show(Component text, PopupS2CPacket.Severity sev,
                            String acceptLabel, String rejectLabel,
                            Runnable onAccept, Runnable onReject) {
        ACTIVE.addFirst(new Action(text, sev, acceptLabel, rejectLabel, onAccept, onReject));
        while (ACTIVE.size() > 3) ACTIVE.removeLast();
    }

    public static void tick() {
        // No auto-dismiss for action popups — they stay until clicked.
    }

    public static void clear() { ACTIVE.clear(); }

    public static boolean isActive() { return !ACTIVE.isEmpty(); }

    public static void render(GuiGraphics gfx, int screenWidth) {
        if (ACTIVE.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        int y = 40;  // below the regular PopupOverlay stack
        for (Action a : ACTIVE) {
            int w = mc.font.width(a.text);
            int chipW = w + 2 * 12;
            int chipH = mc.font.lineHeight + 2 * 8 + 24;   // room for buttons
            int x0 = (screenWidth - chipW) / 2;
            // Chip background
            gfx.fill(x0 - 1, y - 1, x0 + chipW + 1, y + chipH + 1, a.severity.borderArgb());
            gfx.fill(x0, y, x0 + chipW, y + chipH, a.severity.bgArgb());
            // Text
            int tx = x0 + (chipW - w) / 2;
            int ty = y + 8;
            gfx.drawString(mc.font, a.text, tx, ty, a.severity.textArgb());
            // Buttons
            int by = ty + mc.font.lineHeight + 6;
            int btnW = 60;
            int btnH = mc.font.lineHeight + 4;
            int acceptX = x0 + chipW / 2 - btnW - 4;
            int rejectX  = x0 + chipW / 2 + 4;
            a.acceptX0 = acceptX; a.acceptY0 = by;
            a.acceptX1 = acceptX + btnW; a.acceptY1 = by + btnH;
            a.rejectX0 = rejectX;  a.rejectY0 = by;
            a.rejectX1 = rejectX + btnW;  a.rejectY1 = by + btnH;
            drawButton(gfx, acceptX, by, btnW, btnH, "接受", 0xFF6FA85F, 0xFFFFFFFF);
            drawButton(gfx, rejectX,  by, btnW, btnH, "拒绝", 0xFFA85F5F, 0xFFFFFFFF);
            y += chipH + 8;
        }
    }

    private static void drawButton(GuiGraphics gfx, int x, int y, int w, int h,
                                   String label, int bg, int text) {
        gfx.fill(x, y, x + w, y + h, bg);
        gfx.fill(x, y, x + w, y + 1, 0xFF000000);
        gfx.fill(x, y + h - 1, x + w, y + h, 0xFF000000);
        Minecraft mc = Minecraft.getInstance();
        int tw = mc.font.width(label);
        gfx.drawString(mc.font, label, x + (w - tw) / 2, y + 2, text);
    }

    public static boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return false;
        Action first = ACTIVE.peekFirst();
        if (first == null) return false;
        if (first.acceptX0 <= mx && mx <= first.acceptX1
                && first.acceptY0 <= my && my <= first.acceptY1) {
            ACTIVE.removeFirst();
            first.onAccept.run();
            return true;
        }
        if (first.rejectX0 <= mx && mx <= first.rejectX1
                && first.rejectY0 <= my && my <= first.rejectY1) {
            ACTIVE.removeFirst();
            first.onReject.run();
            return true;
        }
        return false;
    }

    private static class Action {
        final Component text;
        final PopupS2CPacket.Severity severity;
        final String acceptLabel;
        final String rejectLabel;
        final Runnable onAccept;
        final Runnable onReject;
        int acceptX0, acceptY0, acceptX1, acceptY1;
        int rejectX0, rejectY0, rejectX1, rejectY1;
        Action(Component text, PopupS2CPacket.Severity sev,
               String acceptLabel, String rejectLabel,
               Runnable onAccept, Runnable onReject) {
            this.text = text; this.severity = sev;
            this.acceptLabel = acceptLabel; this.rejectLabel = rejectLabel;
            this.onAccept = onAccept; this.onReject = onReject;
        }
    }
}