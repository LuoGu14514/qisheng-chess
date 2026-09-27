package com.qisheng.chess.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.qisheng.chess.engine.xqwlight.Position;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Pure-NativeImage renderer for the board + piece-disc + piece-label layer
 * that the BER textured-quads onto the top face of a chess block.
 *
 * <h2>Per-viewer orientation (mirror for BLACK)</h2>
 * The board lives on the ground with fixed world orientation (BLACK back
 * rank at the north edge, RED back rank at the south edge). For the
 * block surface:
 * <ul>
 *   <li><b>RED viewer</b> (camera on +Z side) — pieces drawn at their
 *       natural FEN positions: BLACK at the top of the texture, RED at
 *       the bottom.</li>
 *   <li><b>BLACK viewer</b> (camera on -Z side) — pieces drawn mirrored
 *       both axes: BLACK at the bottom of the texture, RED at the top,
 *       matching what you'd see sitting on BLACK's side of a physical
 *       board.</li>
 * </ul>
 * Piece labels (Chinese glyphs) are drawn once per viewer in screen-space
 * — the labels themselves never get mirrored, so they stay upright from
 * either perspective. Grid / river / palace are already symmetric and
 * need no viewer logic.
 *
 * <h2>Why AWT (not MC's 3D Font)</h2>
 * AWT renders the glyphs once into a NativeImage so the BER only needs a
 * single textured quad — avoids per-frame billboarded {@code Font.drawInBatch}
 * for every piece (32+ draw calls, fragile matrix math, depth-test pain
 * on top of a quad already at +0.01 Y offset). AWT is available on the
 * MC 1.20.1 client (it's used by the screenshot path), and the bundled
 * {@code Microsoft YaHei} / {@code PingFang SC} / {@code Noto Sans CJK}
 * fallback chain covers Windows / macOS / Linux.
 */
public final class BoardSurfaceRenderer {

    public static final int CELL = 16;
    public static final int W = 9 * CELL;     // 144
    public static final int H = 10 * CELL;    // 160

    // ---- Palette ----
    private static final int COL_BOARD_BG      = 0xFFE8C788;
    private static final int COL_GRID          = 0xFF4A2810;
    private static final int COL_RIVER_BG      = 0xFFD9B989;
    private static final int COL_RED_FILL      = 0xFFE85D5D;
    private static final int COL_RED_RING      = 0xFF8C1F1F;
    private static final int COL_BLACK_FILL    = 0xFF2C2C2C;
    private static final int COL_BLACK_RING    = 0xFF000000;
    private static final int COL_INNER_RING    = 0xFFD4A857;
    private static final int COL_SEL_BORDER    = 0xFFFFFF00;

    // Glyph color: dark on red disc, light on black disc, so the label
    // is legible from either side.
    private static final int COL_RED_GLYPH      = 0xFF3A0808;
    private static final int COL_BLACK_GLYPH    = 0xFFFFFFFF;

    // ---- Font ----
    private static final Font FONT;

    static {
        Font f = null;
        String[] candidates = {
                "Microsoft YaHei", "Microsoft JhengHei", "PingFang SC",
                "Hiragino Sans GB", "Noto Sans CJK SC", "Noto Sans SC",
                "SimHei", "SimSun", "WenQuanYi Zen Hei", "Arial Unicode MS",
                "SansSerif"
        };
        for (String name : candidates) {
            try {
                Font cand = new Font(name, Font.BOLD, 11);
                if (cand.getFamily().equalsIgnoreCase(name) || name.equals("SansSerif")) {
                    f = cand; break;
                }
            } catch (Throwable ignored) {}
        }
        if (f == null) f = new Font(Font.SANS_SERIF, Font.BOLD, 11);
        FONT = f;
    }

    private BoardSurfaceRenderer() {}

    /**
     * Paint the full board (background, grid, river, palace, piece discs,
     * piece labels, selection ring) into {@code img}, oriented for the
     * current viewer. {@code viewerIsBlack = true} mirrors both axes
     * (BLACK near, RED far) — matching BLACK's perspective from the
     * opposite side of a physical board.
     */
    public static void paint(NativeImage img, String fen, int selectPoint,
                             boolean viewerIsBlack) {
        fill(img, 0, 0, W, H, COL_BOARD_BG);
        fill(img, 0, 4 * CELL + 1, W, 5 * CELL - 1, COL_RIVER_BG);
        drawGrid(img);
        drawPalace(img);

        Position pos = parseFen(fen);
        if (pos != null) {
            drawDiscs(img, pos, viewerIsBlack);
            drawLabels(img, pos, viewerIsBlack);
        }
        if (selectPoint >= 0) {
            drawSelection(img, selectPoint, viewerIsBlack);
        }
    }

    /** Quick parse that returns null on garbage FEN rather than throwing. */
    private static Position parseFen(String fen) {
        if (fen == null || fen.isEmpty()) return null;
        try {
            Position p = new Position();
            p.fromFen(fen);
            return p;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- Coordinate transforms (FEN → screen, viewer-aware) ----
    private static int viewFile(int fenFile, boolean viewerIsBlack) {
        return viewerIsBlack ? (8 - fenFile) : fenFile;
    }
    private static int viewRank(int fenRank, boolean viewerIsBlack) {
        return viewerIsBlack ? (9 - fenRank) : fenRank;
    }
    private static int viewCX(int fenFile, boolean viewerIsBlack) {
        return viewFile(fenFile, viewerIsBlack) * CELL + CELL / 2;
    }
    private static int viewCY(int fenRank, boolean viewerIsBlack) {
        return viewRank(fenRank, viewerIsBlack) * CELL + CELL / 2;
    }

    // ---------- Primitives ----------

    private static void fill(NativeImage img, int x0, int y0, int x1, int y1, int argb) {
        x0 = Math.max(0, x0);
        y0 = Math.max(0, y0);
        x1 = Math.min(W, x1);
        y1 = Math.min(H, y1);
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                img.setPixelRGBA(x, y, argb);
            }
        }
    }

    private static void line(NativeImage img, int x0, int y0, int x1, int y1, int argb) {
        int dx = Math.abs(x1 - x0), sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0), sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        int x = x0, y = y0;
        while (true) {
            if (x >= 0 && x < W && y >= 0 && y < H) img.setPixelRGBA(x, y, argb);
            if (x == x1 && y == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x += sx; }
            if (e2 <= dx) { err += dx; y += sy; }
        }
    }

    private static void rectOutline(NativeImage img, int x0, int y0, int x1, int y1, int argb) {
        for (int x = x0; x <= x1; x++) {
            if (x >= 0 && x < W) {
                if (y0 >= 0 && y0 < H) img.setPixelRGBA(x, y0, argb);
                if (y1 >= 0 && y1 < H) img.setPixelRGBA(x, y1, argb);
            }
        }
        for (int y = y0; y <= y1; y++) {
            if (y >= 0 && y < H) {
                if (x0 >= 0 && x0 < W) img.setPixelRGBA(x0, y, argb);
                if (x1 >= 0 && x1 < W) img.setPixelRGBA(x1, y, argb);
            }
        }
    }

    private static void disc(NativeImage img, int cx, int cy, int r, int argb) {
        for (int yy = -r; yy <= r; yy++) {
            int dx = (int) Math.sqrt(Math.max(0, r * r - yy * yy));
            int top = cy + yy;
            if (top < 0 || top >= H) continue;
            int xLo = Math.max(0, cx - dx);
            int xHi = Math.min(W - 1, cx + dx);
            for (int xx = xLo; xx <= xHi; xx++) img.setPixelRGBA(xx, top, argb);
        }
    }

    // ---------- Board features ----------

    private static void drawGrid(NativeImage img) {
        for (int c = 0; c < 9; c++) {
            int x = c * CELL;
            line(img, x, 0 * CELL, x, 4 * CELL, COL_GRID);
            line(img, x, 5 * CELL, x, 9 * CELL, COL_GRID);
        }
        for (int r = 0; r < 10; r++) {
            int y = r * CELL;
            line(img, 0 * CELL, y, 8 * CELL, y, COL_GRID);
        }
    }

    private static void drawPalace(NativeImage img) {
        line(img, 3 * CELL, 0 * CELL, 5 * CELL, 2 * CELL, COL_GRID);
        line(img, 5 * CELL, 0 * CELL, 3 * CELL, 2 * CELL, COL_GRID);
        line(img, 3 * CELL, 7 * CELL, 5 * CELL, 9 * CELL, COL_GRID);
        line(img, 5 * CELL, 7 * CELL, 3 * CELL, 9 * CELL, COL_GRID);
    }

    private static void drawDiscs(NativeImage img, Position pos, boolean viewerIsBlack) {
        int discR = 7;
        for (int rank = 0; rank < 10; rank++) {
            for (int file = 0; file < 9; file++) {
                int sq = Position.COORD_XY(file + Position.FILE_LEFT,
                                           rank + Position.RANK_TOP);
                byte pc = pos.squares[sq];
                if (pc == 0) continue;
                int cx = viewCX(file, viewerIsBlack);
                int cy = viewCY(rank, viewerIsBlack);
                boolean isRed = (pc & 8) == 8;
                boolean isBlack = (pc & 16) == 16;
                if (!isRed && !isBlack) continue;
                int fillC = isRed ? COL_RED_FILL : COL_BLACK_FILL;
                int ringC = isRed ? COL_RED_RING : COL_BLACK_RING;
                disc(img, cx, cy, discR, fillC);
                rectOutline(img, cx - discR, cy - discR, cx + discR, cy + discR, ringC);
                rectOutline(img, cx - discR + 2, cy - discR + 2, cx + discR - 2, cy + discR - 2, COL_INNER_RING);
            }
        }
    }

    /**
     * Render the Chinese piece glyphs onto the disc layer via AWT.
     *
     * <p>Renders at 4× supersample then down-samples to 1× via area
     * averaging, so the 11 px CJK characters stay legible when shrunk
     * to fit a 14 px disc. Background is fully transparent — the disc
     * (drawn in {@link #drawDiscs}) shows through. Each glyph is drawn
     * at the screen position the current viewer sees the piece on — the
     * glyph itself is never mirrored, so it stays upright regardless of
     * which side of the block the camera is on.
     */
    private static void drawLabels(NativeImage img, Position pos, boolean viewerIsBlack) {
        final int SUP = 4;
        final int w = W * SUP, h = H * SUP;
        BufferedImage bi;
        try {
            bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        } catch (Throwable headless) {
            // AWT unavailable (shouldn't happen on MC client, but be safe).
            return;
        }
        Graphics2D g = bi.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                               RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                               RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                               RenderingHints.VALUE_RENDER_QUALITY);
            g.setComposite(AlphaComposite.Src);
            g.setFont(FONT.deriveFont(Font.BOLD, 11f * SUP));

            for (int rank = 0; rank < 10; rank++) {
                for (int file = 0; file < 9; file++) {
                    int sq = Position.COORD_XY(file + Position.FILE_LEFT,
                                               rank + Position.RANK_TOP);
                    byte pc = pos.squares[sq];
                    if (pc == 0) continue;
                    int pt = pc & 7;
                    boolean isRed = (pc & 8) == 8;
                    boolean isBlack = (pc & 16) == 16;
                    if (!isRed && !isBlack) continue;

                    String s = isRed ? redLabel(pt) : blackLabel(pt);
                    int textColor = isRed ? COL_RED_GLYPH : COL_BLACK_GLYPH;

                    int cx = viewCX(file, viewerIsBlack) * SUP;
                    int cy = viewCY(rank, viewerIsBlack) * SUP;
                    int textWidth = g.getFontMetrics().stringWidth(s);
                    int ascent = g.getFontMetrics().getAscent();
                    int descent = g.getFontMetrics().getDescent();

                    int tx = cx - textWidth / 2;
                    int ty = cy + (ascent - descent) / 2;

                    g.setColor(new Color(textColor, true));
                    g.drawString(s, tx, ty);
                }
            }
        } finally {
            g.dispose();
        }

        // Downsample each 14×14 cell box onto the matching disc, preserving
        // the disc colors underneath (we're painting glyph pixels into the
        // existing alpha buffer, not replacing the disc).
        for (int rank = 0; rank < 10; rank++) {
            for (int file = 0; file < 9; file++) {
                int sq = Position.COORD_XY(file + Position.FILE_LEFT,
                                           rank + Position.RANK_TOP);
                byte pc = pos.squares[sq];
                if (pc == 0) continue;
                if (((pc & 8) == 0) && ((pc & 16) == 0)) continue;

                int cx = viewCX(file, viewerIsBlack);
                int cy = viewCY(rank, viewerIsBlack);
                int x0 = cx - 7;
                int y0 = cy - 7;
                blitDownsampleAlpha(img, bi, x0, y0, 14, 14, x0, y0, 14, 14, SUP);
            }
        }
    }

    /** Area-average downsample of a {@code sw×sh} region from {@code src} (at SUP×) into {@code dst} (at 1×). Glyph alpha is preserved (SrcOver). */
    private static void blitDownsampleAlpha(NativeImage dst, BufferedImage src,
                                             int srcX0, int srcY0, int sw, int sh,
                                             int dstX0, int dstY0, int dw, int dh,
                                             int sup) {
        int sX0 = srcX0 * sup;
        int sY0 = srcY0 * sup;
        for (int dy = 0; dy < dh; dy++) {
            for (int dx = 0; dx < dw; dx++) {
                int a = 0, r = 0, g = 0, b = 0, n = 0;
                for (int sy = 0; sy < sup; sy++) {
                    for (int sx = 0; sx < sup; sx++) {
                        int argb = src.getRGB(sX0 + dx * sup + sx, sY0 + dy * sup + sy);
                        a += (argb >>> 24) & 0xFF;
                        r += (argb >>> 16) & 0xFF;
                        g += (argb >>>  8) & 0xFF;
                        b +=  argb         & 0xFF;
                        n++;
                    }
                }
                a /= n; r /= n; g /= n; b /= n;
                if (a == 0) continue;  // transparent — don't touch the underlying disc
                int outX = dstX0 + dx;
                int outY = dstY0 + dy;
                if (outX < 0 || outX >= W || outY < 0 || outY >= H) continue;
                // Composite glyph pixel (alpha-blended with current disc color)
                int dstARGB = dst.getPixelRGBA(outX, outY);
                int dstA = (dstARGB >>> 24) & 0xFF;
                int dstR = (dstARGB >>> 16) & 0xFF;
                int dstG = (dstARGB >>>  8) & 0xFF;
                int dstB =  dstARGB         & 0xFF;
                float af = a / 255f;
                int outR = (int) (r * af + dstR * (1f - af));
                int outG = (int) (g * af + dstG * (1f - af));
                int outB = (int) (b * af + dstB * (1f - af));
                int outA = Math.max(dstA, a);
                dst.setPixelRGBA(outX, outY, (outA << 24) | (outR << 16) | (outG << 8) | outB);
            }
        }
    }

    // ---------- Piece labels ----------

    private static String redLabel(int pt) {
        return switch (pt) {
            case 0 -> "帥"; case 1 -> "仕"; case 2 -> "相";
            case 3 -> "傌"; case 4 -> "俥"; case 5 -> "炮"; case 6 -> "兵";
            default -> "?";
        };
    }
    private static String blackLabel(int pt) {
        return switch (pt) {
            case 0 -> "將"; case 1 -> "士"; case 2 -> "象";
            case 3 -> "馬"; case 4 -> "車"; case 5 -> "砲"; case 6 -> "卒";
            default -> "?";
        };
    }

    private static void drawSelection(NativeImage img, int sq, boolean viewerIsBlack) {
        int file = (sq & 0xF) - Position.FILE_LEFT;
        int rank = ((sq >> 4) & 0xF) - Position.RANK_TOP;
        if (file < 0 || file >= 9 || rank < 0 || rank >= 10) return;
        int cx = viewCX(file, viewerIsBlack);
        int cy = viewCY(rank, viewerIsBlack);
        rectOutline(img, cx - 8, cy - 8, cx + 8, cy + 8, COL_SEL_BORDER);
    }
}