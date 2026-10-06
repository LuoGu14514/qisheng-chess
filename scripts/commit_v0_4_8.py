#!/usr/bin/env python3
"""Commit v0.4.8 — fixes GUI-not-opening bug on gomoku/go9/go19, plus
gomoku/go winner mapping, go19 FEN rank encoding, and added
GameSessionWinnerTest."""

import io
import os
import shutil
import subprocess
import sys

# Force UTF-8 stdout for paths with non-GBK chars
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")

REPO = r"D:\代码\qisheng-chess"
GIT = r"C:\ProgramData\wdsj2\GitHubDesktop\app-3.5.12\resources\app\git\cmd\git.exe"


def run(*args):
    """Run git with UTF-8 env; return (rc, stdout)."""
    env = os.environ.copy()
    env["LC_ALL"] = "C.UTF-8"
    env["PYTHONIOENCODING"] = "utf-8"
    r = subprocess.run([GIT] + list(args), cwd=REPO,
                       capture_output=True, text=True, encoding="utf-8",
                       errors="replace", env=env)
    return r.returncode, r.stdout, r.stderr


def main():
    # Show current status
    rc, out, err = run("status", "--short")
    print("[status]")
    print(out)

    # Remove any stale lock from a prior crash
    lock = os.path.join(REPO, ".git", "index.lock")
    if os.path.exists(lock):
        try:
            os.remove(lock)
            print(f"[lock] removed stale {lock}")
        except OSError as e:
            print(f"[lock] remove fail: {e}")

    # Add the v0.4.8 paths explicitly (avoid BOM phantom known_hosts)
    paths = [
        "gradle.properties",
        "common/src/main/java/com/qisheng/chess/pvp/GameSession.java",
        "common/src/main/java/com/qisheng/chess/engine/go/GoVariant.java",
        "common/src/main/java/com/qisheng/chess/network/ChessOpenScreenS2CPacket.java",
        "common/src/main/java/com/qisheng/chess/network/ChessSyncS2CPacket.java",
        "common/src/test/java/com/qisheng/chess/engine/go/GoVariantTest.java",
        "common/src/test/java/com/qisheng/chess/pvp/GameSessionWinnerTest.java",
        "AUDIT_REPORT_GUI_FLOW_RESOURCES.md",
    ]
    rc, out, err = run("add", "--", *paths)
    print(f"[add {'OK' if rc == 0 else 'FAIL'}] {out}{err}")

    # Confirm what's staged
    rc, out, err = run("status", "--short", "--staged")
    print("[staged]")
    print(out)

    msg = (
        "v0.4.8: 修复 GUI 不开 + Gomoku Go + go19 FEN rank\n"
        "\n"
        "关键修复:\n"
        "  * GUI 不开 (CRITICAL): ChessOpenScreenS2CPacket +\n"
        "    ChessSyncS2CPacket 的 totalSquaresFor() 改用\n"
        "    BoardRegistry.getByIdOrDefault(variantId).totalSquares(),\n"
        "    之前硬编码 xiangqi=256 / international=64 / default=256,\n"
        "    gomoku(225) / go9(81) / go19(361) 走 default=256 → bitmap\n"
        "    越界 → client 端 receive 抛异常 → mc.setScreen 未调用 →\n"
        "    popup 显示, GUI 不开. 已修.\n"
        "  * Gomoku/Go 胜方映射 (GameSession.checkGameOver): 旧实现用\n"
        "    sdPlayer 翻转推胜方, 对 Gomoku/Go 错 (Black 赢后 sdPlayer\n"
        "    已翻到 White, 旧代码会报 Red 赢). 已 special-case.\n"
        "  * go19 FEN rank 编码: 旧实现 ks.charAt(1) - '1' 只解 1-9,\n"
        "    go19 rank 10-19 (a-j) 会越界 → ko (null) 自 save/restore\n"
        "    失败. 加 parseRankChar/formatRankChar helper.\n"
        "  * 删除冗余 halfmoveClock >= 100 检查: InternationalChessVariant.\n"
        "    isStalemate() 已处理.\n"
        "  * 加 GameSessionWinnerTest (3 tests) + GoVariantTest 加 go19\n"
        "    koSquare 高 rank round-trip. 共 132 tests, 全 PASSED.\n"
        "\n"
        "审查: 3 路并行审计 (cross-variant API compliance / network wire\n"
        "format symmetry / GUI flow + resources) 已收. 报告存档\n"
        "AUDIT_REPORT_GUI_FLOW_RESOURCES.md."
    )

    rc, out, err = run("commit", "-m", msg)
    print(f"[commit {'OK' if rc == 0 else 'FAIL'}]")
    print(out)
    print(err)

    # Show new HEAD
    rc, out, err = run("log", "--oneline", "-1")
    print(f"[log] {out.strip()}")


if __name__ == "__main__":
    main()