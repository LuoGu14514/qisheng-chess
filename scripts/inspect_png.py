"""Inspect PNG metadata for transparency/sRGB chunks."""
from PIL import Image
import os

files = [
    r'common\src\main\resources\assets\qisheng_chess\textures\block\qisheng_gomoku_top.png',
    r'common\src\main\resources\assets\qisheng_chess\textures\block\qisheng_go9_top.png',
    r'common\src\main\resources\assets\qisheng_chess\textures\block\qisheng_go19_top.png',
    r'common\src\main\resources\assets\qisheng_chess\textures\block\qisheng_cchess_top.png',
]
for f in files:
    if not os.path.exists(f):
        print(f'MISSING: {f}')
        continue
    img = Image.open(f)
    print(f'{f}:')
    print(f'  mode={img.mode}, info keys={list(img.info.keys())}')
    if 'transparency' in img.info:
        print(f'  transparency chunk: {img.info["transparency"]}')
    if 'sRGB' in img.info:
        print(f'  sRGB chunk: {img.info["sRGB"]}')
    if 'gamma' in img.info:
        print(f'  gamma chunk: {img.info["gamma"]}')
    if 'icc_profile' in img.info:
        print(f'  icc_profile chunk present (len={len(img.info["icc_profile"])})')