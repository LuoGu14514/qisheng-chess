package com.qisheng.chess.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Vertical stack of action buttons on the right side of the chess-board
 * GUI (or below, depending on layout). Buttons appear / disappear based
 * on session state and viewer role.
 *
 * <p>Each button:
 * <ul>
 *   <li>Width = full widget width minus padding</li>
 *   <li>Height = {@link #BTN_H}</li>
 *   <li>Spacing = {@link #BTN_GAP}</li>
 * </ul>
 *
 * <p>The list of buttons is recomputed whenever the screen layout changes
 * (init / resize / roster change). Buttons carry:
 * <ul>
 *   <li>{@code label} — shown on the chip</li>
 *   <li>{@code enabled} — whether the click should be accepted</li>
 *   <li>{@code onClick} — what to do when clicked</li>
 *   <li>{@code kind} — drives styling (primary / danger / neutral)</li>
 * </ul>
 */
public class ActionButtonsWidget extends AbstractWidget {

    public enum Kind { PRIMARY, DANGER, NEUTRAL }

    public record Action(String label, Kind kind, boolean enabled, Runnable onClick) {}

    private static final int BTN_H = 20;
    private static final int BTN_GAP = 4;

    private final List<Action> actions = new ArrayList<>();

    public ActionButtonsWidget(int x, int y, int w, int h) {
        super(x, y, w, h, Component.literal("动作"));
    }

    public void setActions(List<Action> acts) {
        this.actions.clear();
        this.actions.addAll(acts);
    }

    public boolean isEmpty() { return actions.isEmpty(); }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return false;
        int x = this.getX(), y = this.getY();
        int by = y;
        for (Action a : actions) {
            if (mx >= x && mx <= x + this.getWidth() && my >= by && my <= by + BTN_H) {
                if (a.enabled) a.onClick.run();
                return true;
            }
            by += BTN_H + BTN_GAP;
        }
        return false;
    }

    @Override
    public void renderWidget(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        int x = this.getX(), y = this.getY();
        int by = y;
        Minecraft mc = Minecraft.getInstance();
        for (Action a : actions) {
            int bg = switch (a.kind) {
                case PRIMARY -> a.enabled ? 0xFF3F6F3F : 0xFF2A3F2A;
                case DANGER  -> a.enabled ? 0xFF6F3F3F : 0xFF3F2A2A;
                default      -> a.enabled ? 0xFF4A4A4A : 0xFF333333;
            };
            int border = a.enabled ? 0xFFE3C88F : 0xFF555555;
            int text   = a.enabled ? 0xFFFFFFFF : 0xFF777777;
            gfx.fill(x, by, x + this.getWidth(), by + BTN_H, bg);
            gfx.fill(x, by, x + this.getWidth(), by + 1, border);
            gfx.fill(x, by + BTN_H - 1, x + this.getWidth(), by + BTN_H, border);
            int tw = mc.font.width(a.label);
            gfx.drawString(mc.font, a.label, x + (this.getWidth() - tw) / 2,
                    by + (BTN_H - mc.font.lineHeight) / 2, text);
            by += BTN_H + BTN_GAP;
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {}
}