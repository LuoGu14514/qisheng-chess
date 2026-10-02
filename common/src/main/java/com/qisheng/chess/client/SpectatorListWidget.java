package com.qisheng.chess.client;

import com.qisheng.chess.network.SpectatorListS2CPacket;
import com.qisheng.chess.network.SpectatorListS2CPacket.Roster;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Vertical scrollable list of session participants (red / black / spectators),
 * drawn on the left edge of the chess-board GUI.
 */
public class SpectatorListWidget extends AbstractWidget {

    private static final int ROW_H = 18;
    private static final int HEAD_H = 22;
    private static final int HEAD_PAD = 4;
    private static final int FACE_SIZE = 12;
    private static final int FACE_PAD = 3;
    private static final int ROW_INDENT = 4;
    private static final int SCROLLBAR_W = 4;

    private final int maxRowsVisible;
    private int scrollRows = 0;
    private Roster roster;

    public SpectatorListWidget(int x, int y, int w, int h) {
        super(x, y, w, h, Component.translatable("qisheng.chess.role.spectator"));
        int rowsForBody = Math.max(3, (h - HEAD_H) / ROW_H);
        this.maxRowsVisible = rowsForBody;
    }

    public void applyRoster(Roster r) { this.roster = r; this.clampScroll(); }

    private void clampScroll() {
        int max = Math.max(0, totalRows() - maxRowsVisible);
        if (scrollRows > max) scrollRows = max;
        if (scrollRows < 0) scrollRows = 0;
    }

    private int totalRows() {
        if (roster == null) return 0;
        return 3 + (roster.spectators == null ? 0 : roster.spectators.length);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (!isMouseOver(mx, my)) return false;
        // Wheel up (delta > 0) walks back to earlier rows, wheel down moves
        // towards newer ones — the same direction as ChatBoxWidget (this list
        // used to be inverted against the chat box).
        if (delta > 0) scrollRows -= 1; else scrollRows += 1;
        clampScroll();
        return true;
    }

    @Override
    public void renderWidget(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        int x = this.getX(), y = this.getY(), w = this.getWidth(), h = this.getHeight();
        gfx.fill(x, y, x + w, y + h, 0xCC222222);
        DrawUtil.border(gfx, x, y, w, h, 0xFF555555);

        Minecraft mc = Minecraft.getInstance();
        String header = roster == null
                ? Component.translatable("qisheng.chess.spectator.loading").getString()
                : Component.translatable("qisheng.chess.spectator.header",
                        roster.spectators.length).getString();
        gfx.drawString(mc.font, mc.font.plainSubstrByWidth(header, Math.max(8, w - 2 * HEAD_PAD)),
                x + HEAD_PAD, y + (HEAD_H - mc.font.lineHeight) / 2, 0xFFEFEFEF);

        if (roster == null) return;

        int bodyY = y + HEAD_H;
        int drawY = bodyY - scrollRows * ROW_H;
        List<Row> rows = new ArrayList<>();
        rows.add(Row.role(Component.translatable("qisheng.chess.role.red").getString(),
                roster.red, true));
        rows.add(Row.role(Component.translatable("qisheng.chess.role.black").getString(),
                roster.black, false));
        rows.add(Row.divider());
        for (SpectatorListS2CPacket.PlayerEntry p : roster.spectators) rows.add(Row.spec(p));

        int yBottom = y + h;
        // Clip the body to the panel: a row whose top sits just above the
        // bottom edge would otherwise paint a full row height over the border.
        gfx.enableScissor(x, bodyY, x + w, yBottom);
        for (Row row : rows) {
            if (drawY + ROW_H <= bodyY) { drawY += ROW_H; continue; }
            if (drawY >= yBottom) break;
            row.draw(gfx, x + ROW_INDENT, drawY, w - ROW_INDENT - SCROLLBAR_W - 2);
            drawY += ROW_H;
        }
        gfx.disableScissor();

        int total = totalRows();
        int max = Math.max(0, total - maxRowsVisible);
        if (max > 0) {
            int barX = x + w - SCROLLBAR_W - 1;
            int barY0 = bodyY;
            int barY1 = y + h;
            gfx.fill(barX, barY0, barX + SCROLLBAR_W, barY1, 0xFF444444);
            int thumbH = Math.max(8, (maxRowsVisible * (barY1 - barY0)) / total);
            int thumbY = barY0 + (int) (((double) scrollRows / max) * ((barY1 - barY0) - thumbH));
            gfx.fill(barX, thumbY, barX + SCROLLBAR_W, thumbY + thumbH, 0xFFAAAAAA);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {}

    private static final class Row {
        final String label;
        final SpectatorListS2CPacket.PlayerEntry entry;
        final boolean isRedRole;
        final boolean isDivider;
        final String roleTag;
        private Row(String label, SpectatorListS2CPacket.PlayerEntry e,
                    boolean isRedRole, boolean isDivider, String tag) {
            this.label = label; this.entry = e;
            this.isRedRole = isRedRole;
            this.isDivider = isDivider; this.roleTag = tag;
        }
        static Row role(String tag, SpectatorListS2CPacket.PlayerEntry e, boolean isRed) {
            return new Row(tag, e, isRed, false, tag);
        }
        static Row divider() {
            return new Row("—", null, false, true, "");
        }
        static Row spec(SpectatorListS2CPacket.PlayerEntry e) {
            return new Row(
                    Component.translatable("qisheng.chess.role.spectator").getString(),
                    e, false, false, "");
        }
        void draw(GuiGraphics gfx, int x, int y, int w) {
            Minecraft mc = Minecraft.getInstance();
            int textY = y + (ROW_H - mc.font.lineHeight) / 2;
            if (isDivider) {
                int midY = y + ROW_H / 2;
                gfx.fill(x, midY, x + w, midY + 1, 0xFF555555);
                return;
            }
            int fx = x;
            int fy = y + (ROW_H - FACE_SIZE) / 2;
            DrawUtil.avatar(gfx, entry == null ? null : entry.id, fx, fy, FACE_SIZE, true);
            int tx = x + FACE_SIZE + FACE_PAD;
            String raw = TextSanitizer.strip(entry == null ? null : entry.name);
            String name = raw.isEmpty()
                    ? Component.translatable("qisheng.chess.badge.empty_slot").getString()
                    : raw;
            // Keep the name clear of the role tag painted on the right.
            int tagW = roleTag.isEmpty() ? 0 : mc.font.width(roleTag) + 4;
            int nameAvail = Math.max(8, x + w - tagW - tx);
            if (mc.font.width(name) > nameAvail) {
                name = mc.font.plainSubstrByWidth(name, nameAvail);
            }
            int nameColor = entry == null ? 0xFF888888 : 0xFFEFEFEF;
            gfx.drawString(mc.font, name, tx, textY, nameColor);
            int tagX = x + w - mc.font.width(roleTag) - 4;
            if (!roleTag.isEmpty()) {
                gfx.drawString(mc.font, roleTag, tagX, textY,
                        isRedRole ? 0xFFFF8888 : 0xFF888888);
            }
        }
    }
}