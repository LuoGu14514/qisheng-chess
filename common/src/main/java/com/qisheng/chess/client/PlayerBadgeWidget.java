package com.qisheng.chess.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Compact "this side's player" badge: head-skin + name + role tag, drawn
 * symmetrically on either side of the board (red's badge bottom-left,
 * black's bottom-right — by convention; the parent Screen decides).
 *
 * <p>Used both for live players and for "empty slot" placeholders. The
 * fields are not final so {@link #update(UUID, String)} can re-bind the
 * widget to a new player without churning the renderableWidgets list.
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

    public UUID getPlayerId() { return playerId; }
    public int getRole() { return role; }

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
        gfx.fill(x, y, x + w, y + 1, border);
        gfx.fill(x, y + h - 1, x + w, y + h, border);
        gfx.fill(x, y, x + 1, y + h, border);
        gfx.fill(x + w - 1, y, x + w, y + h, border);

        int headX = x + PAD_X;
        int headY = y + (h - HEAD) / 2;
        if (playerId != null) {
            ResourceLocation tex = PlayerAvatarCache.get(playerId);
            gfx.blit(tex, headX, headY, HEAD, HEAD, 8.0F, 8.0F, 8, 8, 64, 64);
            gfx.blit(tex, headX, headY, HEAD, HEAD, 40.0F, 8.0F, 8, 8, 64, 64);
        } else {
            gfx.fill(headX, headY, headX + HEAD, headY + HEAD, 0xFF555555);
        }

        int tx = headX + HEAD + GAP;
        String name = (playerName == null || playerName.isEmpty())
                ? (role == -1 ? "空位" : "?")
                : playerName;
        int nameColor = playerName == null ? 0xFF888888 : 0xFFEFEFEF;
        gfx.drawString(mc.font, name, tx, y + PAD_Y + 2, nameColor);
        String tag = role == 0 ? "红方" : role == 1 ? "黑方" : "旁观";
        int tagColor = role == 0 ? 0xFFFF8888 : role == 1 ? 0xFFAAAAAA : 0xFF888888;
        gfx.drawString(mc.font, tag, tx, y + h - mc.font.lineHeight - PAD_Y, tagColor);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {}
}