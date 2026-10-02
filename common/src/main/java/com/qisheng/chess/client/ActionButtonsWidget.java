package com.qisheng.chess.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Vertical stack of action buttons on the right side of the chess-board
 * GUI (or below, depending on layout). Buttons appear / disappear based
 * on session state and viewer role.
 *
 * <p>Each button:
 * <ul>
 *   <li>Width = 整个 widget 宽度(面板内缩由构造方 {@code CChessBoardScreen} 负责)</li>
 *   <li>Height = {@link #BTN_H}</li>
 *   <li>Spacing = {@link #BTN_GAP}</li>
 * </ul>
 *
 * <p>The list of buttons is recomputed whenever the screen layout changes
 * (init / resize / roster change). Buttons carry:
 * <ul>
 *   <li>{@code label} — shown on the chip</li>
 *   <li>{@code onClick} — what to do when clicked</li>
 *   <li>{@code kind} — drives styling (primary / danger / neutral)</li>
 * </ul>
 *
 * <p>There is no {@code enabled} flag: the screen only ever adds a button
 * when its action is legal right now (see
 * {@code CChessBoardScreen.rebuildActionPanel()}), so a disabled state had
 * no way to be reached.
 */
public class ActionButtonsWidget extends AbstractWidget {

    public enum Kind { PRIMARY, DANGER, NEUTRAL }

    public record Action(String label, Kind kind, Runnable onClick) {}

    /**
     * 单个按钮的高度。
     *
     * <p>必须与 {@code CChessBoardScreen.BTN_H} 一致:屏幕按
     * {@code maxButtons * (BTN_H + BTN_GAP)} 为右侧面板预留高度,本类再按同
     * 一个公式逐行绘制。两处不一致时面板会多出(或吃掉)一段空白。
     */
    private static final int BTN_H = 24;
    private static final int BTN_GAP = 4;

    private final List<Action> actions = new ArrayList<>();

    public ActionButtonsWidget(int x, int y, int w, int h) {
        super(x, y, w, h, Component.translatable("qisheng.chess.action.panel"));
    }

    public void setActions(List<Action> acts) {
        this.actions.clear();
        this.actions.addAll(acts);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return false;
        int x = this.getX(), y = this.getY();
        int by = y;
        for (Action a : actions) {
            if (mx >= x && mx <= x + this.getWidth() && my >= by && my <= by + BTN_H) {
                a.onClick.run();
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
                case PRIMARY -> 0xFF3F6F3F;
                case DANGER  -> 0xFF6F3F3F;
                default      -> 0xFF4A4A4A;
            };
            gfx.fill(x, by, x + this.getWidth(), by + BTN_H, bg);
            DrawUtil.border(gfx, x, by, this.getWidth(), BTN_H, 0xFFE3C88F,
                    DrawUtil.SIDE_TOP | DrawUtil.SIDE_BOTTOM);
            int tw = mc.font.width(a.label);
            gfx.drawString(mc.font, a.label, x + (this.getWidth() - tw) / 2,
                    by + (BTN_H - mc.font.lineHeight) / 2, 0xFFFFFFFF);
            by += BTN_H + BTN_GAP;
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {}
}