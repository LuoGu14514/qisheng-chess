package com.qisheng.chess.client;

import com.qisheng.chess.network.ChatPackets;
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
 * <p>Mouse wheel scrolls history: wheel <b>up</b> walks back to older lines,
 * wheel <b>down</b> returns towards the newest — the same direction as
 * {@link SpectatorListWidget} and vanilla chat. Press Enter in the input field
 * to submit (handled by the parent Screen's {@code keyPressed}).
 *
 * <p>The history list is owned by {@link CChessBoardScreen} and handed in by
 * reference, so a window resize (which rebuilds the widgets) no longer throws
 * the log away. Rendering is clipped to the widget rect and every line is
 * fitted to that width, so long names / messages cannot bleed over the panel
 * border.
 */
public class ChatBoxWidget extends AbstractWidget {

    /** Hard cap on the retained history. */
    public static final int MAX_LINES = 32;
    /**
     * Character cap for one outgoing line. The wire limit is assumed to be
     * 256 (see {@link ChatPackets#MAX_LEN}); we stay well below it.
     */
    public static final int MAX_INPUT_CHARS = 200;
    /** Absolute ceiling on the bytes we are willing to put on the wire. */
    private static final int MAX_WIRE_BYTES = 256;
    private static final int MAX_INPUT_H = 20;
    private static final int MIN_INPUT_H = 12;
    private static final int PAD = 4;
    private static final int LINE_H = 12;

    /** One history line. Public so the Screen can own the log across resizes. */
    public record Message(UUID senderId, String senderName, String text) {}

    /** Screen-owned log; shared by reference, never replaced by this widget. */
    private final List<Message> history;
    /** 0 = pinned to the newest line, negative = scrolled back in time. */
    private int scroll = 0;
    private String inputText = "";
    private boolean inputFocused = false;
    private int cursorPos = 0;

    public ChatBoxWidget(int x, int y, int w, int h) {
        this(x, y, w, h, new ArrayList<>());
    }

    public ChatBoxWidget(int x, int y, int w, int h, List<Message> history) {
        super(x, y, w, h, Component.translatable("qisheng.chess.chat.title"));
        this.history = history;
    }

    /**
     * Append to a shared log, trimming the oldest lines.
     *
     * <p>Static so {@link CChessBoardScreen} can keep logging before / without
     * a live widget instance.
     */
    public static void append(List<Message> log, Message m) {
        if (log == null || m == null) return;
        log.add(m);
        while (log.size() > MAX_LINES) log.remove(0);
    }

    /** Append a message to this widget's (shared) log. */
    public void pushMessage(UUID senderId, String senderName, String text) {
        append(history, new Message(senderId, senderName, text));
        onHistoryChanged();
    }

    /**
     * Called after the shared log grew. Auto-scrolls <b>only</b> when the view
     * is already pinned to the newest line: a player reading history must not
     * be yanked back to the bottom by an incoming message.
     */
    public void onHistoryChanged() {
        if (scroll == 0) return;   // pinned: the new line simply appears
        scroll--;                  // keep the same lines in view
        clampScroll();
    }

    public boolean isInputFocused() { return inputFocused; }

    /** Current draft, so the Screen can restore it after a resize. */
    public String getInputText() { return inputText; }

    /** Restore a draft (used when {@code init()} rebuilds the widget). */
    public void setInputText(String text) {
        this.inputText = clampToWire(text);
        this.cursorPos = this.inputText.length();
    }

    /** Returns the typed text (wire-safe) and clears the input. Called on Enter. */
    public String consumeInput() {
        String t = clampToWire(inputText);
        inputText = "";
        cursorPos = 0;
        return t;
    }

    public void setFocused(boolean focused) {
        this.inputFocused = focused;
    }

    public void charTyped(char c) {
        if (!inputFocused) return;
        if (c == '\u00A7') return;                  // formatting marker: never accept
        if (inputText.length() >= MAX_INPUT_CHARS) return;
        String next = inputText.substring(0, cursorPos) + c + inputText.substring(cursorPos);
        next = clampToWire(next);
        if (next.length() <= inputText.length()) return;   // byte budget exhausted
        inputText = next;
        cursorPos = Math.min(inputText.length(), cursorPos + 1);
    }

    public void backspace() {
        if (!inputFocused) return;
        if (cursorPos == 0) return;
        inputText = inputText.substring(0, cursorPos - 1) + inputText.substring(cursorPos);
        cursorPos--;
    }

    public void leftArrow() { if (inputFocused && cursorPos > 0) cursorPos--; }
    public void rightArrow() { if (inputFocused && cursorPos < inputText.length()) cursorPos++; }

    /**
     * Truncate a line so {@code FriendlyByteBuf.writeUtf(text, limit)} can
     * never throw {@code EncoderException} — exceeding it used to kill the
     * client on the next key press.
     *
     * <p>Two caps apply: {@link #MAX_INPUT_CHARS} characters, and the UTF-8
     * byte budget of the packet — {@code wireByteLimit()} 取
     * {@link ChatPackets#MAX_LEN} 与 {@link #MAX_WIRE_BYTES} 中较小者
     * (目前两者都是 256 字节)。一个 CJK 字符占 3 字节,所以纯中文约 85 字;
     * 纯 ASCII 则先撞上 200 字符的上限。
     */
    public static String clampToWire(String s) {
        if (s == null) return "";
        int limit = wireByteLimit();
        int bytes = 0;
        int chars = 0;
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            int cpChars = Character.charCount(cp);
            if (chars + cpChars > MAX_INPUT_CHARS) break;
            int cpBytes = utf8Length(cp);
            if (bytes + cpBytes > limit) break;
            bytes += cpBytes;
            chars += cpChars;
            i += cpChars;
        }
        return i >= s.length() ? s : s.substring(0, i);
    }

    /** UTF-8 byte length of one code point, without allocating. */
    private static int utf8Length(int cp) {
        if (cp < 0x80) return 1;
        if (cp < 0x800) return 2;
        if (cp < 0x10000) return 3;
        return 4;
    }

    private static int wireByteLimit() {
        int lim = ChatPackets.MAX_LEN;
        if (lim <= 0 || lim > MAX_WIRE_BYTES) lim = MAX_WIRE_BYTES;
        return lim;
    }

    // ---------- layout (derived from the widget's real height) ----------

    private int inputHeight() {
        return Math.max(MIN_INPUT_H, Math.min(MAX_INPUT_H, this.getHeight() / 3));
    }

    private int historyHeight() {
        return Math.max(1, this.getHeight() - inputHeight());
    }

    private int inputTop() { return this.getY() + historyHeight(); }

    private int visibleLines() {
        return Math.max(1, historyHeight() / LINE_H);
    }

    private void clampScroll() {
        int maxScroll = Math.max(0, history.size() - visibleLines());
        if (scroll < -maxScroll) scroll = -maxScroll;
        if (scroll > 0) scroll = 0;
    }

    // ---------- input ----------

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        // Only the left button focuses the input — the mod's own "right-click
        // the board" prompt must not park the caret in the chat box.
        if (button != 0) return false;
        int inX = this.getX(), inY = inputTop(), inW = Math.max(1, this.getWidth());
        boolean inInputArea = mx >= inX && mx <= inX + inW
                && my >= inY && my <= inY + inputHeight();
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
        // Wheel up (delta > 0) walks back to older lines, wheel down returns
        // towards the newest — matches SpectatorListWidget.
        if (delta > 0) scroll--; else scroll++;
        clampScroll();
        return true;
    }

    // ---------- rendering ----------

    @Override
    public void renderWidget(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        int x = this.getX(), y = this.getY();
        int w = Math.max(1, this.getWidth());
        int histH = historyHeight();
        int inH = inputHeight();
        int inY = y + histH;

        // History background
        gfx.fill(x, y, x + w, y + histH, 0xCC222222);
        DrawUtil.border(gfx, x, y, w, histH, 0xFF555555,
                DrawUtil.SIDE_TOP | DrawUtil.SIDE_BOTTOM);
        // Input background
        gfx.fill(x, inY, x + w, inY + inH, 0xDD111111);
        DrawUtil.border(gfx, x, inY, w, inH, 0xFF555555, DrawUtil.SIDE_TOP);
        if (inputFocused) {
            // Focus marker drawn inside the rect so the scissor keeps it.
            DrawUtil.border(gfx, x, inY, w, inH, 0xFFE3C88F,
                    DrawUtil.SIDE_TOP | DrawUtil.SIDE_BOTTOM);
        }

        Minecraft mc = Minecraft.getInstance();
        int avail = Math.max(8, w - 2 * PAD);

        // Everything below is clipped to our own rect: a long name / message
        // must not paint over the panel border, and the widget can be shorter
        // than the history + input rows it wants.
        gfx.enableScissor(x, y, x + w, inY + inH);

        // History lines (newest at bottom; scroll == 0 means pinned to bottom).
        // Newest line is at index history.size()-1; we render top-down so
        // index decreases as we go up the box.
        int lineY = inY - LINE_H;
        for (int i = history.size() - 1 + scroll; i >= 0 && lineY >= y; i--) {
            Message l = history.get(i);
            if (l != null) {
                gfx.drawString(mc.font, formatLine(mc, l, avail), x + PAD, lineY, 0xFFEFEFEF);
            }
            lineY -= LINE_H;
        }

        // Input text + cursor
        String shown = inputText;
        if (inputFocused && (System.currentTimeMillis() / 500) % 2 == 0) {
            shown = inputText.substring(0, cursorPos) + "|" + inputText.substring(cursorPos);
        }
        gfx.drawString(mc.font, fitInput(mc, shown, avail), x + PAD,
                inY + (inH - mc.font.lineHeight) / 2, 0xFFEFEFEF);

        gfx.disableScissor();
    }

    /**
     * Build a sanitised, width-clipped {@code name: text} line. The prefix
     * keeps its own colour so the sender still stands out from the body.
     */
    private static Component formatLine(Minecraft mc, Message l, int avail) {
        String name = TextSanitizer.strip(l.senderName());
        if (name.isEmpty()) name = "?";
        String prefix = name + ": ";
        int prefixW = mc.font.width(prefix);
        if (prefixW > avail) {
            prefix = mc.font.plainSubstrByWidth(prefix, avail);
            prefixW = mc.font.width(prefix);
        }
        String body = TextSanitizer.strip(l.text());
        int bodyAvail = avail - prefixW;
        if (bodyAvail <= 0) {
            body = "";
        } else if (mc.font.width(body) > bodyAvail) {
            body = mc.font.plainSubstrByWidth(body, bodyAvail);
        }
        // Build a Component so the font renders with proper colour codes.
        return Component.literal(prefix)
                .withStyle(l.senderId() != null
                        ? ChatFormatting.GRAY
                        : ChatFormatting.DARK_GRAY)
                .append(Component.literal(body).withStyle(ChatFormatting.WHITE));
    }

    /**
     * Fit the input line into the box while keeping the caret visible: drop
     * whole characters from the front instead of cutting the tail.
     */
    private static String fitInput(Minecraft mc, String shown, int avail) {
        if (mc.font.width(shown) <= avail) return shown;
        int start = 0;
        int acc = 0;
        for (int i = shown.length(); i > 0; i--) {
            int cw = mc.font.width(shown.substring(i - 1, i));
            if (acc + cw > avail) { start = i; break; }
            acc += cw;
        }
        return shown.substring(start);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {}
}
