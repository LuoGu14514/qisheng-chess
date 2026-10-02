package com.qisheng.chess.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * 中象 GUI 的公共绘制工具。
 *
 * <p>这里只收纳此前在多个 widget 里逐字抄写的绘制片段:
 * <ul>
 *   <li>{@link #border} —— 矩形<b>内侧</b> 1 像素边框,可按边选择
 *       (整圈 / 只画上下 / 只画上边 …);</li>
 *   <li>{@link #outline} —— 矩形<b>外侧</b> 1 像素描边 + 底色填充
 *       (弹窗 chip 的画法);</li>
 *   <li>{@link #avatar} —— 玩家头像(皮肤帽层两遍 blit),无 UUID 时退化成灰色占位块。</li>
 * </ul>
 *
 * <p>所有方法的像素输出与抽取前逐字一致 —— 只是把重复的 {@code gfx.fill} /
 * {@code gfx.blit} 调用收拢到一处,没有改任何坐标、颜色或绘制顺序。
 *
 * <p>{@code CChessBoardScreen} 里另有一份私有 {@code drawRectOutline}(支持任意
 * 线宽),但该文件不在本次清理范围内,故未合并。
 */
public final class DrawUtil {

    /** 边框的边选择位:上边。 */
    public static final int SIDE_TOP = 1;
    /** 边框的边选择位:下边。 */
    public static final int SIDE_BOTTOM = 2;
    /** 边框的边选择位:左边。 */
    public static final int SIDE_LEFT = 4;
    /** 边框的边选择位:右边。 */
    public static final int SIDE_RIGHT = 8;
    /** 四条边全画。 */
    public static final int SIDE_ALL = SIDE_TOP | SIDE_BOTTOM | SIDE_LEFT | SIDE_RIGHT;

    /** 无皮肤时的占位底色。 */
    private static final int PLACEHOLDER_BG = 0xFF555555;
    /** 占位块内芯色(仅旁观者列表使用)。 */
    private static final int PLACEHOLDER_INNER = 0xFF888888;

    private DrawUtil() {}

    /**
     * 画矩形内侧的 1 像素边框。
     *
     * <p>边框画在矩形内部,所以不改变矩形本身占用的像素范围;四条边都按
     * 整条边长绘制,交叠处的颜色相同,因此绘制顺序不影响结果。
     *
     * @param gfx   绘制上下文
     * @param x     矩形左边界(含)
     * @param y     矩形上边界(含)
     * @param w     矩形宽度
     * @param h     矩形高度
     * @param color 边框颜色(ARGB)
     * @param sides 要画的边,{@link #SIDE_TOP} / {@link #SIDE_BOTTOM} /
     *              {@link #SIDE_LEFT} / {@link #SIDE_RIGHT} 的按位或
     */
    public static void border(GuiGraphics gfx, int x, int y, int w, int h, int color, int sides) {
        if ((sides & SIDE_TOP) != 0) {
            gfx.fill(x, y, x + w, y + 1, color);
        }
        if ((sides & SIDE_BOTTOM) != 0) {
            gfx.fill(x, y + h - 1, x + w, y + h, color);
        }
        if ((sides & SIDE_LEFT) != 0) {
            gfx.fill(x, y, x + 1, y + h, color);
        }
        if ((sides & SIDE_RIGHT) != 0) {
            gfx.fill(x + w - 1, y, x + w, y + h, color);
        }
    }

    /** 画矩形内侧的 1 像素整圈边框,等价于 {@code border(..., SIDE_ALL)}。 */
    public static void border(GuiGraphics gfx, int x, int y, int w, int h, int color) {
        border(gfx, x, y, w, h, color, SIDE_ALL);
    }

    /**
     * 矩形<b>外侧</b> 1 像素描边,再用底色填满矩形内部。
     *
     * <p>先画 {@code (x-1, y-1, x+w+1, y+h+1)} 的整块描边色,再用底色覆盖
     * {@code (x, y, x+w, y+h)},于是只剩下外圈那 1 像素可见。弹窗 chip 用的
     * 就是这种画法,描边会往外扩 1 像素,不占用内容区。
     *
     * @param gfx         绘制上下文
     * @param x           内容区左边界(含)
     * @param y           内容区上边界(含)
     * @param w           内容区宽度
     * @param h           内容区高度
     * @param borderColor 外描边颜色(ARGB)
     * @param bgColor     内容区底色(ARGB)
     */
    public static void outline(GuiGraphics gfx, int x, int y, int w, int h,
                               int borderColor, int bgColor) {
        gfx.fill(x - 1, y - 1, x + w + 1, y + h + 1, borderColor);
        gfx.fill(x, y, x + w, y + h, bgColor);
    }

    /**
     * 画玩家头像:皮肤贴图的头部(底层)与帽层(顶层)各 blit 一次。
     *
     * <p>{@code playerId} 为 {@code null} 时退化成一个纯色占位方块 —— 与抽取前
     * {@code PlayerBadgeWidget} 的行为一致。
     *
     * @param gfx      绘制上下文
     * @param playerId 玩家 UUID,可为 {@code null}
     * @param x        头像左边界(含)
     * @param y        头像上边界(含)
     * @param size     头像边长(像素)
     */
    public static void avatar(GuiGraphics gfx, UUID playerId, int x, int y, int size) {
        avatar(gfx, playerId, x, y, size, false);
    }

    /**
     * 画玩家头像,占位样式可选。
     *
     * @param innerPlaceholder {@code true} 时占位块再叠一层浅灰内芯(旁观者列表
     *                         的画法),{@code false} 时只画纯色块(玩家徽章的画法)
     */
    public static void avatar(GuiGraphics gfx, UUID playerId, int x, int y, int size,
                              boolean innerPlaceholder) {
        if (playerId != null) {
            ResourceLocation tex = PlayerAvatarCache.get(playerId);
            gfx.blit(tex, x, y, size, size, 8.0F, 8.0F, 8, 8, 64, 64);
            gfx.blit(tex, x, y, size, size, 40.0F, 8.0F, 8, 8, 64, 64);
            return;
        }
        gfx.fill(x, y, x + size, y + size, PLACEHOLDER_BG);
        if (innerPlaceholder) {
            gfx.fill(x + 1, y + 1, x + size - 1, y + size - 1, PLACEHOLDER_INNER);
        }
    }
}
