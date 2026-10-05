"""
Generate 256x256 top-board textures + 16x16 item textures for gomoku / go9 / go19.

v0.4.5: switched from RGB to RGBA + explicit alpha=255 to match the format of
qisheng_cchess_top.png (which is RGBA). v0.4.4's gomoku/go textures were RGB
mode, and the user's client rendered the block as transparent — looks like
Fabric/MC's sampler treats RGB-mode PNGs as alpha=0 in some code paths.

Also: more vibrant colours, thicker grid lines (2px), larger star points (5x5),
no wood-grain noise (which only added visual noise without aiding recognition).

Outputs:
  common/src/main/resources/assets/qisheng_chess/textures/block/qisheng_gomoku_top.png
  common/src/main/resources/assets/qisheng_chess/textures/block/qisheng_go9_top.png
  common/src/main/resources/assets/qisheng_chess/textures/block/qisheng_go19_top.png
  common/src/main/resources/assets/qisheng_chess/textures/item/gomoku.png
  common/src/main/resources/assets/qisheng_chess/textures/item/go9.png
  common/src/main/resources/assets/qisheng_chess/textures/item/go19.png
"""

import os
import math
import random
from PIL import Image, ImageDraw, ImageFont

OUT_DIR = r"D:\代码\qisheng-chess\common\src\main\resources\assets\qisheng_chess\textures"
BLOCK_DIR = os.path.join(OUT_DIR, "block")
ITEM_DIR = os.path.join(OUT_DIR, "item")
os.makedirs(BLOCK_DIR, exist_ok=True)
os.makedirs(ITEM_DIR, exist_ok=True)

# ---------------------------------------------------------------------------
# Block top texture (256x256)
# ---------------------------------------------------------------------------

def make_board_top(name: str, board_size: int, bg_rgb, line_rgb, star_rgb,
                   border_rgb, label: str):
    """Generate a 256x256 RGBA board-top texture.

    All pixels are written as RGBA with alpha=255. Even though alpha=255 means
    "fully opaque", using RGBA mode (not RGB) ensures Minecraft's texture
    sampler treats the texture as opaque rather than alpha=0 (RGB-mode case).
    """
    SIZE = 256
    img = Image.new("RGBA", (SIZE, SIZE), (*bg_rgb, 255))
    draw = ImageDraw.Draw(img)

    # Outer dark border (12px). Drawn on top of the opaque background so
    # every pixel — including corners — has explicit alpha=255.
    border = 12
    draw.rectangle([0, 0, SIZE - 1, SIZE - 1], outline=(*border_rgb, 255), width=border)

    # Inner playable area.
    pad = 22
    inner_left = pad
    inner_top = pad
    inner_right = SIZE - pad
    inner_bottom = SIZE - pad
    inner_w = inner_right - inner_left
    inner_h = inner_bottom - inner_top

    # Grid lines — 2px thick so they survive the 256x256 → 16x16 mipmap chain.
    if board_size > 1:
        for i in range(board_size):
            t = i / (board_size - 1)
            x = inner_left + int(t * inner_w)
            draw.line([(x, inner_top), (x, inner_bottom)], fill=(*line_rgb, 255), width=2)
            y = inner_top + int(t * inner_h)
            draw.line([(inner_left, y), (inner_right, y)], fill=(*line_rgb, 255), width=2)

    # Star points — 5x5 filled squares (v0.4.4 was 3x3, too small to read at
    # distance). Standard positions for each board size.
    if board_size == 15:
        star_positions = [(3, 3), (3, 11), (7, 7), (11, 3), (11, 11)]
    elif board_size == 9:
        star_positions = [(2, 2), (2, 6), (6, 2), (6, 6)]
    elif board_size == 19:
        star_positions = [(3, 3), (3, 9), (3, 15),
                          (9, 3), (9, 9), (9, 15),
                          (15, 3), (15, 9), (15, 15)]
    else:
        star_positions = []
    for (sx, sy) in star_positions:
        cx = inner_left + int(sx / (board_size - 1) * inner_w)
        cy = inner_top + int(sy / (board_size - 1) * inner_h)
        draw.rectangle([cx - 2, cy - 2, cx + 2, cy + 2], fill=(*star_rgb, 255))

    # Label text (bottom-center).
    try:
        font = ImageFont.load_default()
        bbox = draw.textbbox((0, 0), label, font=font)
        tw = bbox[2] - bbox[0]
        th = bbox[3] - bbox[1]
        tx = (SIZE - tw) // 2
        ty = SIZE - border - th - 4
        draw.text((tx + 1, ty + 1), label, fill=(0, 0, 0, 255), font=font)
        draw.text((tx, ty), label, fill=(*line_rgb, 255), font=font)
    except Exception:
        pass

    out_path = os.path.join(BLOCK_DIR, f"qisheng_{name}_top.png")
    img.save(out_path, "PNG", optimize=True)
    print(f"  wrote {out_path} ({img.size[0]}x{img.size[1]}, {img.mode})")

    # Item texture (16x16).
    make_item_texture(name, board_size, bg_rgb, line_rgb, star_rgb, border_rgb, label)


def make_item_texture(name: str, board_size: int, bg_rgb, line_rgb, star_rgb,
                      border_rgb, label: str):
    """Generate a 16x16 RGBA item icon."""
    SIZE = 16
    img = Image.new("RGBA", (SIZE, SIZE), (*bg_rgb, 255))
    draw = ImageDraw.Draw(img)

    # Border.
    draw.rectangle([0, 0, SIZE - 1, SIZE - 1], outline=(*border_rgb, 255), width=1)

    # Inner area.
    pad = 2
    L, T, R, B = pad, pad, SIZE - 1 - pad, SIZE - 1 - pad
    iw = R - L
    ih = B - T

    # Grid.
    if board_size > 1:
        for i in range(board_size):
            t = i / (board_size - 1)
            x = L + round(t * iw)
            y = T + round(t * ih)
            draw.point((x, T), fill=(*line_rgb, 255))
            draw.point((x, B), fill=(*line_rgb, 255))
            draw.point((L, y), fill=(*line_rgb, 255))
            draw.point((R, y), fill=(*line_rgb, 255))
        for i in range(board_size):
            for j in range(board_size):
                t1 = i / (board_size - 1)
                t2 = j / (board_size - 1)
                x = L + round(t1 * iw)
                y = T + round(t2 * ih)
                draw.point((x, y), fill=(*line_rgb, 255))

    # Center star point.
    if board_size > 1:
        cx = L + iw // 2
        cy = T + ih // 2
        draw.point((cx, cy), fill=(*star_rgb, 255))

    out_path = os.path.join(ITEM_DIR, f"{name}.png")
    img.save(out_path, "PNG", optimize=True)
    print(f"  wrote {out_path} ({img.size[0]}x{img.size[1]}, {img.mode})")


# ---------------------------------------------------------------------------
# Generate all three boards. v0.4.5 uses more vibrant palettes than v0.4.4:
# higher saturation + bigger contrast between bg/grid/star/border.
# ---------------------------------------------------------------------------

print("Generating v0.4.5 board textures (RGBA + alpha=255)...")

# Gomoku — warm tan, 15x15 light grid, 5 star points.
make_board_top(
    name="gomoku",
    board_size=15,
    bg_rgb=(220, 184, 130),
    line_rgb=(60, 40, 25),
    star_rgb=(20, 10, 5),
    border_rgb=(40, 25, 15),
    label="GOMOKU",
)

# Go 9x9 — dark oak, 9x9 grid, 4 star points.
make_board_top(
    name="go9",
    board_size=9,
    bg_rgb=(180, 130, 75),
    line_rgb=(15, 10, 5),
    star_rgb=(0, 0, 0),
    border_rgb=(25, 18, 10),
    label="GO 9",
)

# Go 19x19 — same palette as go9, denser 19x19 grid, 9 star points.
make_board_top(
    name="go19",
    board_size=19,
    bg_rgb=(180, 130, 75),
    line_rgb=(15, 10, 5),
    star_rgb=(0, 0, 0),
    border_rgb=(25, 18, 10),
    label="GO 19",
)

print("Done.")