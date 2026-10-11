#!/usr/bin/env python3
"""Commit v0.4.21 WARN fixes from the v0.4.20 audit."""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

GIT = r'C:\ProgramData\wdsj2\GitHubDesktop\app-3.5.12\resources\app\git\cmd\git.exe'
REPO = Path(r'D:\代码\qisheng-chess')

PATHS = [
    'common/src/main/java/com/qisheng/chess/block/AbstractChessBoardBlock.java',
    'common/src/main/java/com/qisheng/chess/block/GomokuBoardBlock.java',
    'common/src/main/java/com/qisheng/chess/block/GoBoard9Block.java',
    'common/src/main/java/com/qisheng/chess/block/GoBoard19Block.java',
    'common/src/main/java/com/qisheng/chess/tileentity/CChessTileEntity.java',
    'common/src/test/java/com/qisheng/chess/tileentity/EnsureSessionVariantTest.java',
    'gradle.properties',
    'scripts/deploy_v0_4_21.py',
    'scripts/commit_v0_4_21.py',
    'scripts/push_v0_4_21.py',
    'scripts/generate_board_textures.py',
]

def run(args: list[str]) -> str:
    r = subprocess.run([GIT, '-C', str(REPO)] + args, capture_output=True, text=True, encoding='utf-8')
    return (r.stdout or '') + (r.stderr or '')

def main() -> int:
    for p in PATHS:
        run(['add', '--', p])

    msg = (
        "v0.4.21: 修全量审计剩下的 WARN 项\n"
        "\n"
        "Joined-as-red 渠道: 右键路径改用 popup (W1 game)\n"
        "  - AbstractChessBoardBlock use() line 223 原本 sendSystemMessage (chat)\n"
        "  - 改为 GameBroadcaster.broadcastPopupTo (popup)\n"
        "  - 现在两条加入路径都走 popup, 与 C2S packet 路径一致\n"
        "  - popup 不会立刻被聊天记录淹没, 用户更容易看到\n"
        "\n"
        "ensureSession preferredVariantId 改为无条件应用 (W2 game)\n"
        "  - 原 gate: 仅当 live 仍是 DEFAULT_ID 时覆盖\n"
        "  - 新 gate: 只要 preferredVariantId != live.getVariantId 就覆盖\n"
        "  - 处理两种 race: (a) onPlace 创建时 variant 未知\n"
        "                    (b) 旧版本持久化下来的非默认 variant 现在需要更新\n"
        "  - 同步把 adopted 分支也加上同样的 gate\n"
        "  - 新增 EnsureSessionVariantTest 6 个测试覆盖: 默认/相同/不同/null/空/round-trip\n"
        "\n"
        "4 个 concrete 块类形状统一 (W3 resources)\n"
        "  - 全部用 VARIANT_ID 常量 (GoBoard9Block/GoBoard19Block 原本没有)\n"
        "  - 全部移除冗余的 newBlockEntity 覆盖 (父类 default 已正确)\n"
        "  - 全部移除冗余的 getVariantId(BlockState) 覆盖 (父类 default 已调用 getVariantId())\n"
        "  - 现在 4 个 concrete 块类的形状一致:\n"
        "      CChessBoardBlock:    22 行 (VARIANT_ID + getVariantId)\n"
        "      GomokuBoardBlock:    28 行 (同上)\n"
        "      GoBoard9Block:       28 行 (同上)\n"
        "      GoBoard19Block:      28 行 (同上)\n"
        "\n"
        "texture-gen script docstring 注明 cchess 故意不生成 (W4 resources)\n"
        "  - qisheng_cchess_top.png + item/cchess.png 是 v0.4.0 手绘的\n"
        "    (暗红河界 + 九宫斜线, 脚本的算法网格无法复现)\n"
        "  - 脚本现在 docstring 明确说明: 故意不生成 cchess\n"
        "\n"
        "测试: 153 tests PASSED (新增 6 个 EnsureSessionVariantTest)\n"
        "部署: server jar 265007 B SHA256 b4c985c75aefd44debb99aedebf76d8ecc28b63d44bda91c554c308d1c0d60aa\n"
        "       port 25565 OPEN on ::, PID 36028, v0.4.21 加载成功\n"
    )
    r = subprocess.run([GIT, '-C', str(REPO), 'commit', '-m', msg], capture_output=True, text=True, encoding='utf-8')
    out = (r.stdout or '') + (r.stderr or '')
    print(out)
    if r.returncode != 0:
        return r.returncode
    head = run(['rev-parse', '--short', 'HEAD']).strip()
    print(f'\nHEAD = {head}')
    return 0

if __name__ == '__main__':
    sys.exit(main())