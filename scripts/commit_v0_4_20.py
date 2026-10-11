#!/usr/bin/env python3
"""Commit v0.4.20 audit fixes.

3 CRITICAL bugs found by subagent full audit (m10160/m10198/m10199):
  C1 (game logic):  GameSession.checkGameOver Gomoku/Go winner mapping
                    was INVERTED — GomokuBoard.BLACK stones sit on
                    session's RED seat (first joiner plays first).
  C2 (resources):   models/block/qisheng_cchess.json sides/particle were
                    left at oak_planks when the v0.4.4 fix only updated
                    gomoku/go9/go19.
  C3 (build):       fabric.mod.json `depends` did not declare fabric-api,
                    so the loader won't enforce its presence on a player
                    without it.

Plus:
  D1 (cleanup):     delete dead-stock textures/block/qisheng_cchess.png
                    (zero references).
  D2 (tests):       GameSessionWinnerTest re-asserted the corrected
                    mapping (was enshrining the bug); added a 4th
                    Go-two-passes-on-empty-board test for the KOMI path.
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

GIT = r'C:\ProgramData\wdsj2\GitHubDesktop\app-3.5.12\resources\app\git\cmd\git.exe'
REPO = Path(r'D:\代码\qisheng-chess')

PATHS = [
    'common/src/main/java/com/qisheng/chess/pvp/GameSession.java',
    'common/src/main/resources/assets/qisheng_chess/models/block/qisheng_cchess.json',
    'common/src/test/java/com/qisheng/chess/pvp/GameSessionWinnerTest.java',
    'fabric/src/main/resources/fabric.mod.json',
    'gradle.properties',
    'scripts/deploy_v0_4_20.py',
    'scripts/commit_v0_4_20.py',
    'scripts/push_v0_4_20.py',
]

def run(args: list[str]) -> str:
    r = subprocess.run([GIT, '-C', str(REPO)] + args, capture_output=True, text=True, encoding='utf-8')
    return (r.stdout or '') + (r.stderr or '')

def main() -> int:
    # Explicit add (avoid the BOM-corrupted CUserswdsj2.sshknown_hosts phantom
    # file that a blanket `git add .` would drag into the index).
    for p in PATHS:
        run(['add', '--', p])
    # Also remove the dead-stock texture if it ended up in the index.
    # (We deleted it on disk already; this is just in case git tracked it.)
    run(['rm', '--cached', '--ignore-unmatch',
         'common/src/main/resources/assets/qisheng_chess/textures/block/qisheng_cchess.png'])

    msg = (
        "v0.4.20: 修全量审计发现的 3 处 CRITICAL bug\n"
        "\n"
        "GameSession.checkGameOver 的 Gomoku/Go 胜负归属颠倒 (CRITICAL)\n"
        "  - GomokuBoard.BLACK 子落在会话的 RED 座位 (先加入者走先)\n"
        "  - 原 v0.4.8 修复写了 '如果 BLACK 赢报 BLACK_WIN', 颠倒了座位映射\n"
        "  - 用户实测: 五子棋/围棋每局都把输家报为赢家\n"
        "  - GameSessionWinnerTest 3 个测试原本就是反着断言, 同步改正\n"
        "  - 新增 1 个 Go 空盘 2-pass 测试 (KOMI 7.5 给白 → BLACK_WIN)\n"
        "\n"
        "cchess 块体侧面纹理 (CRITICAL)\n"
        "  - v0.4.4 'sides = top texture' 修复只应用于 gomoku/go9/go19\n"
        "  - cchess 块侧面/粒子仍是 oak_planks\n"
        "  - models/block/qisheng_cchess.json 改为全部 5 个面 = qisheng_cchess_top\n"
        "\n"
        "fabric.mod.json depends 加 fabric-api (CRITICAL)\n"
        "  - fabric-api 在 modImplementation 中声明, 但未在 depends 中声明\n"
        "  - Loader 不会强制要求 fabric-api 存在, 玩家缺 fabric-api 时崩溃\n"
        "  - depends 加 fabric-api >= 0.92.0\n"
        "\n"
        "清理死文件 textures/block/qisheng_cchess.png (D1)\n"
        "  - 16x16 旧 stub, 零引用, 从来没被任何 model/blockstate 引用过\n"
        "\n"
        "测试: 138 tests PASSED (新增 1 个 Go KOMI 测试)\n"
        "部署: server jar 265570 B SHA256 7816b04f2165619e3de91e46556d4e7a1b841beb9dce0bd05f053c5f7595a353\n"
        "       client jar 265570 B same SHA256\n"
        "       port 25565 OPEN on :: + 127.0.0.1, PID 34336\n"
    )
    r = subprocess.run([GIT, '-C', str(REPO), 'commit', '-m', msg], capture_output=True, text=True, encoding='utf-8')
    out = (r.stdout or '') + (r.stderr or '')
    print(out)
    if r.returncode != 0:
        return r.returncode

    # Show resulting HEAD
    head = run(['rev-parse', '--short', 'HEAD']).strip()
    print(f'\nHEAD = {head}')
    return 0

if __name__ == '__main__':
    sys.exit(main())