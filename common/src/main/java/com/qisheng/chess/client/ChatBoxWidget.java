package com.qisheng.chess.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Simple in-GUI chat history + input box.
 *
 * <p>Layout:
 * <pre>
 *   ┌─────────────────────────┐
 *   │ (history, scrollable)   │
 *   │  alice: hello           │
 *   │  bob: hi                │
 *   │  ...                    │
 *   ├─────────────────────────┤
 *   │ (input)                 │
 *   └─────────────────────────┘
 * </pre>
 *
 * <p>Mouse wheel scrolls history. Press Enter in the input field to
 * submit (handled by the parent Screen's {@code keyPressed}).
 */
public class ChatBoxWidget extends AbstractWidget {

    private static final int MAX_LINES = 32;
    private static final int HISTORY_H = 100;
    private static final int INPUT_H = 20;
    private static final int PAD = 4;

    /** Each line: {@code (senderId, senderName, text)}. */
    private final List<Line> history = new ArrayList<>();
    private int scroll = 0;
    private String inputText = "";
    private boolean inputFocused = false;
    private int cursorPos = 0;

    public ChatBoxWidget(int x, int y, int w, int h) {
        super(x, y, w, h, Component.literal("评论"));
    }

    public void pushMessage(UUID senderId, String senderName, String text) {
        history.add(new Line(senderId, senderName, text));
        while (history.size() > MAX_LINES) history.remove(0);
        // Pin to bottom on new messages unless the user scrolled up.
        scroll = 0;
    }

    public boolean isInputFocused() { return inputFocused; }

    /** Returns the currently typed text and clears the input. Called by Screen on Enter. */
    public String consumeInput() {
        String t = inputText;
        inputText = "";
        cursorPos = 0;
        return t;
    }

    public void setFocused(boolean focused) {
        this.inputFocused = focused;
    }

    public void charTyped(char c) {
        if (!inputFocused) return;
        inputText = inputText.substring(0, cursorPos) + c + inputText.substring(cursorPos);
        cursorPos++;
    }

    public void backspace() {
        if (!inputFocused) return;
        if (cursorPos == 0) return;
        inputText = inputText.substring(0, cursorPos - 1) + inputText.substring(cursorPos);
        cursorPos--;
    }

    public void leftArrow() { if (inputFocused && cursorPos > 0) cursorPos--; }
    public void rightArrow() { if (inputFocused && cursorPos < inputText.length()) cursorPos++; }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int inX = this.getX(), inY = this.getY() + HISTORY_H, inW = this.getWidth();
        boolean inInputArea = mx >= inX && mx <= inX + inW
                && my >= inY && my <= inY + INPUT_H;
        if (inInputArea) {
            inputFocused = true;
            return true;
        }
        inputFocused = false;
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (!isMouseOver(mx, my)) return false;
        if (delta > 0) scroll--; else scroll++;
        int maxScroll = Math.max(0, history.size() - visibleLines());
        if (scroll < -maxScroll) scroll = -maxScroll;
        if (scroll > 0) scroll = 0;
        return true;
    }

    private int visibleLines() {
        return HISTORY_H / 12;
    }

    @Override
    public void renderWidget(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        int x = this.getX(), y = this.getY(), w = this.getWidth();
        // History background
        gfx.fill(x, y, x + w, y + HISTORY_H, 0xCC222222);
        gfx.fill(x, y, x + w, y + 1, 0xFF555555);
        gfx.fill(x, y + HISTORY_H - 1, x + w, y + HISTORY_H, 0xFF555555);
        // Input background
        gfx.fill(x, y + HISTORY_H, x + w, y + HISTORY_H + INPUT_H, 0xDD111111);
        gfx.fill(x, y + HISTORY_H, x + w, y + HISTORY_H + 1, 0xFF555555);
        if (inputFocused) {
            gfx.fill(x - 1, y + HISTORY_H - 1, x + w + 1, y + HISTORY_H + INPUT_H + 1, 0xFFE3C88F);
        }
        // History lines (newest at bottom; scroll == 0 means pinned to bottom)
        Minecraft mc = Minecraft.getInstance();
        int lineY = y + HISTORY_H - 12;
        // Newest line is at index history.size()-1; we render top-down so
        // newest at bottom — index decreases as we go up the box.
        for (int i = history.size() - 1 + scroll; i >= 0 && lineY >= y; i--) {
            Line l = history.get(i);
            if (l == null) continue;
            String name = (l.senderName == null || l.senderName.isEmpty())
                    ? "?" : l.senderName;
            // Build a Component so the font renders with proper colour codes.
            Component msg = Component.literal(name + ": ")
                    .withStyle(l.senderId != null
                            ? ChatFormatting.GRAY
                            : ChatFormatting.DARK_GRAY)
                    .append(Component.literal(l.text).withStyle(ChatFormatting.WHITE));
            gfx.drawString(mc.font, msg, x + PAD, lineY, 0xFFEFEFEF);
            lineY -= 12;
        }
        // Input text + cursor
        String shown = inputText;
        if (inputFocused && (System.currentTimeMillis() / 500) % 2 == 0) {
            shown = inputText.substring(0, cursorPos) + "|" + inputText.substring(cursorPos);
        }
        gfx.drawString(mc.font, shown, x + PAD, y + HISTORY_H + 6, 0xFFEFEFEF);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {}

    private record Line(UUID senderId, String senderName, String text) {}
}