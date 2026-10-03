"""
Generate 256x256 top-board textures + 16x16 item textures for gomoku / go9 / go19.

These are original placeholder assets for the Qisheng Chess mod — distinct color
schemes per game type so players can tell them apart in the creative inventory:

- gomoku: warm tan wood, 15x15 light grid (15x15 board)
- go9:    dark oak, 9x9 grid with 4 star points (small Go board)
- go19:   dark oak, 19x19 grid with 9 star points (standard Go board)

Block textures: 256x256 PNG, dark border + light interior grid + faint surface noise.
Item textures:  16x16 PNG, downscaled / simplified view of the top texture.

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

def make_board_top(name: str, board_size: int, bg_rgb, line_rgb, star_rgb, border_rgb, label: str):
    """Generate a 256x256 board-top texture.

    name:        output file basename (without _top.png)
    board_size:  number of grid lines (15 for gomoku, 9 for go9, 19 for go19)
    bg_rgb:      background wood color
    line_rgb:    grid line color
    star_rgb:    star point color
    border_rgb:  outer border color
    label:       text shown in lower-center (e.g. "GOMOKU", "GO 9", "GO 19")
    """
    SIZE = 256
    img = Image.new("RGB", (SIZE, SIZE), bg_rgb)
    draw = ImageDraw.Draw(img)

    # Wood-grain noise — small per-pixel random variation.
    rng = random.Random(hash(name) & 0xFFFFFFFF)
    noise = Image.new("RGB", (SIZE, SIZE))
    nd = noise.load()
    for y in range(SIZE):
        for x in range(SIZE):
            # Horizontal-ish grain pattern: y dominates
            jitter = rng.randint(-6, 6) + rng.randint(-3, 3)
            r = max(0, min(255, bg_rgb[0] + jitter))
            g = max(0, min(255, bg_rgb[1] + jitter))
            b = max(0, min(255, bg_rgb[2] + jitter))
            nd[x, y] = (r, g, b)
    img = noise

    # Draw outer dark border (12px) — like the cchess texture.
    draw = ImageDraw.Draw(img)
    border = 12
    draw.rectangle([0, 0, SIZE - 1, SIZE - 1], outline=border_rgb, width=border)

    # Inner playable area.
    pad = 22
    inner_left = pad
    inner_top = pad
    inner_right = SIZE - pad
    inner_bottom = SIZE - pad
    inner_w = inner_right - inner_left
    inner_h = inner_bottom - inner_top

    # Draw grid lines.
    if board_size > 1:
        for i in range(board_size):
            t = i / (board_size - 1)
            x = inner_left + int(t * inner_w)
            draw.line([(x, inner_top), (x, inner_bottom)], fill=line_rgb, width=1)
            y = inner_top + int(t * inner_h)
            draw.line([(inner_left, y), (inner_right, y)], fill=line_rgb, width=1)

    # Star points — small filled squares at standard positions.
    # gomoku has 5 points (3-3, 3-11, 7-7, 11-3, 11-11 on a 15x15 board),
    # go9 has 4 points, go19 has 9 points.
    if board_size == 15:
        star_positions = [(3, 3), (3, 11), (7, 7), (11, 3), (11, 11)]
    elif board_size == 9:
        star_positions = [(2, 2), (2, 6), (6, 2), (6, 6)]
    elif board_size == 19:
        # Standard 9 star points on 19x19 (positions 3, 9, 15).
        star_positions = [(3, 3), (3, 9), (3, 15),
                          (9, 3), (9, 9), (9, 15),
                          (15, 3), (15, 9), (15, 15)]
    else:
        star_positions = []
    for (sx, sy) in star_positions:
        cx = inner_left + int(sx / (board_size - 1) * inner_w)
        cy = inner_top + int(sy / (board_size - 1) * inner_h)
        # 3x3 dark square.
        draw.rectangle([cx - 2, cy - 2, cx + 2, cy + 2], fill=star_rgb)

    # Label text (bottom-center) — best-effort, no font shipped.
    # Use default font; if it fails, skip silently.
    try:
        font = ImageFont.load_default()
        bbox = draw.textbbox((0, 0), label, font=font)
        tw = bbox[2] - bbox[0]
        th = bbox[3] - bbox[1]
        tx = (SIZE - tw) // 2
        ty = SIZE - border - th - 4
        # Subtle dark shadow first.
        draw.text((tx + 1, ty + 1), label, fill=(0, 0, 0), font=font)
        draw.text((tx, ty), label, fill=line_rgb, font=font)
    except Exception:
        pass

    out_path = os.path.join(BLOCK_DIR, f"qisheng_{name}_top.png")
    img.save(out_path, "PNG", optimize=True)
    print(f"  wrote {out_path} ({img.size[0]}x{img.size[1]})")

    # Item texture: downscale to 16x16 and re-draw a simplified version.
    make_item_texture(name, board_size, bg_rgb, line_rgb, star_rgb, border_rgb, label)


def make_item_texture(name: str, board_size: int, bg_rgb, line_rgb, star_rgb, border_rgb, label: str):
    """Generate a 16x16 item icon — tiny top-down view of the board."""
    SIZE = 16
    img = Image.new("RGB", (SIZE, SIZE), bg_rgb)
    draw = ImageDraw.Draw(img)

    # Border.
    draw.rectangle([0, 0, SIZE - 1, SIZE - 1], outline=border_rgb, width=1)

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
            draw.point((x, T), fill=line_rgb)
            draw.point((x, B), fill=line_rgb)
            draw.point((L, y), fill=line_rgb)
            draw.point((R, y), fill=line_rgb)
        # Fill cross intersections lightly.
        for i in range(board_size):
            for j in range(board_size):
                t1 = i / (board_size - 1)
                t2 = j / (board_size - 1)
                x = L + round(t1 * iw)
                y = T + round(t2 * ih)
                draw.point((x, y), fill=line_rgb)

    # One star point at the center (visible at 16x16).
    if board_size > 1:
        cx = L + iw // 2
        cy = T + ih // 2
        draw.point((cx, cy), fill=star_rgb)

    out_path = os.path.join(ITEM_DIR, f"{name}.png")
    img.save(out_path, "PNG", optimize=True)
    print(f"  wrote {out_path} ({img.size[0]}x{img.size[1]})")


# ---------------------------------------------------------------------------
# Generate all three boards.
# ---------------------------------------------------------------------------

print("Generating v0.4.2 board textures...")

# Gomoku — warm tan wood, 15x15 light grid, 5 star points.
make_board_top(
    name="gomoku",
    board_size=15,
    bg_rgb=(212, 180, 130),
    line_rgb=(70, 50, 30),
    star_rgb=(40, 25, 15),
    border_rgb=(60, 40, 25),
    label="GOMOKU",
)

# Go 9x9 — dark oak, 9x9 grid, 4 star points.
make_board_top(
    name="go9",
    board_size=9,
    bg_rgb=(180, 140, 90),
    line_rgb=(20, 15, 10),
    star_rgb=(0, 0, 0),
    border_rgb=(40, 30, 20),
    label="GO 9",
)

# Go 19x19 — same dark oak palette, denser 19x19 grid, 9 star points.
make_board_top(
    name="go19",
    board_size=19,
    bg_rgb=(180, 140, 90),
    line_rgb=(20, 15, 10),
    star_rgb=(0, 0, 0),
    border_rgb=(40, 30, 20),
    label="GO 19",
)

print("Done.")
