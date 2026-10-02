package com.qisheng.chess.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/**
 * 「本方玩家」徽章:头像 + 名字 + 身份标签。
 *
 * <p>本类只负责画自己那块矩形,摆在哪里由父 Screen 决定 ——
 * {@code CChessBoardScreen} 目前把红、黑两个徽章上下叠放在左侧面板里
 * (红方在上,黑方在下),并不是棋盘两侧对称摆放。
 *
 * <p>既用于真实玩家,也用于「空位」占位。字段不是 final,
 * 这样 {@link #update(UUID, String)} 就能在不重建 renderableWidgets
 * 列表的前提下把同一个 widget 重新绑定到别的玩家。
 */
public class PlayerBadgeWidget extends AbstractWidget {

    private static final int HEAD = 24;
    private static final int PAD_X = 8;
    private static final int PAD_Y = 4;
    private static final int GAP  = 6;
    private static final int FRAME = 0xFF6B4226;
    private static final int FRAME_SEL = 0xFFE3C88F;

    private UUID playerId;
    private String playerName;
    private final int role;
    private final boolean isYou;

    public PlayerBadgeWidget(int x, int y, int w, int h,
                             UUID playerId, String playerName, int role, boolean isYou) {
        super(x, y, w, h, Component.literal(playerName == null ? "" : playerName));
        this.playerId = playerId;
        this.playerName = playerName;
        this.role = role;
        this.isYou = isYou;
    }

    /** Re-bind this widget to a different player. Safe to call every tick. */
    public void update(UUID newId, String newName) {
        this.playerId = newId;
        this.playerName = newName;
    }

    @Override
    public void renderWidget(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        int x = this.getX(), y = this.getY(), w = this.getWidth(), h = this.getHeight();
        Minecraft mc = Minecraft.getInstance();
        gfx.fill(x, y, x + w, y + h, 0xCC222222);
        int border = isYou ? FRAME_SEL : FRAME;
        DrawUtil.border(gfx, x, y, w, h, border);

        // Clip skin + text to the badge rect so a long name cannot paint over
        // the panel border.
        gfx.enableScissor(x, y, x + w, y + h);

        int headX = x + PAD_X;
        int headY = y + (h - HEAD) / 2;
        DrawUtil.avatar(gfx, playerId, headX, headY, HEAD);

        int tx = headX + HEAD + GAP;
        String raw = TextSanitizer.strip(playerName);
        String name = raw.isEmpty()
                ? (role == -1
                        ? Component.translatable("qisheng.chess.badge.empty_slot").getString()
                        : "?")
                : raw;
        int nameAvail = Math.max(8, x + w - PAD_X - tx);
        if (mc.font.width(name) > nameAvail) {
            name = mc.font.plainSubstrByWidth(name, nameAvail);
        }
        int nameColor = playerName == null ? 0xFF888888 : 0xFFEFEFEF;
        gfx.drawString(mc.font, name, tx, y + PAD_Y + 2, nameColor);
        String tag = role == 0
                ? Component.translatable("qisheng.chess.role.red").getString()
                : role == 1
                        ? Component.translatable("qisheng.chess.role.black").getString()
                        : Component.translatable("qisheng.chess.role.spectator").getString();
        int tagColor = role == 0 ? 0xFFFF8888 : role == 1 ? 0xFFAAAAAA : 0xFF888888;
        gfx.drawString(mc.font, tag, tx, y + h - mc.font.lineHeight - PAD_Y, tagColor);

        gfx.disableScissor();
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {}
}