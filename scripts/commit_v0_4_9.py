#!/usr/bin/env python3
"""Commit v0.4.9 singleplayer initServer fix. Explicit add avoids BOM phantom known_hosts."""
from __future__ import annotations
import io, os, subprocess, sys, time
from pathlib import Path

WORKSPACE = Path(r"D:\代码\qisheng-chess")
GIT_EXE = r"C:\ProgramData\wdsj2\GitHubDesktop\app-3.5.12\resources\app\git\cmd\git.exe"

# Force UTF-8 on Windows (default cp1252 would truncate non-ASCII in commit msg).
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

FILES = [
    "fabric/src/main/java/com/qisheng/chess/fabric/QishengChessFabric.java",
    "fabric/src/main/java/com/qisheng/chess/fabric/FabricServerNetworkBridge.java",
    "fabric/src/main/java/com/qisheng/chess/fabric/FabricClientNetworkBridge.java",
    "gradle.properties",
    "scripts/commit_v0_4_8.py",
    "scripts/deploy_v0_4_8.py",
    "scripts/push_v0_4_8.py",
    "scripts/deploy_v0_4_9.py",
]

MSG = (
    "v0.4.9: singleplayer (集成服) 服务端 init 修复 + 幂等 init\n\n"
    "- 上一版 Arch 提到的 ROOT cause is singleplayer (集成服) 模式:\n"
    "  Fabric server entrypoint 在 env=CLIENT (单集成服) 不发火,\n"
    "  FabricServerNetworkBridge.initServer() 从未调,\n"
    "  serverSender 一直是 throwing placeholder,点棋盘后 sendOpenScreen\n"
    "  IllegalStateException 被 silent-catch 吞,只看到 popup 没看到棋盘.\n"
    "- 修复: 在 main entrypoint (QishengChessFabric.onInitialize) 中通过反射\n"
    "  tryInvoke both FabricServerNetworkBridge.initServer() and\n"
    "  FabricClientNetworkBridge.initClient();两端都在 env 都调,\n"
    "  错误 env 上反射 tryInvoke (ClassNotFoundException 来自 env-strip)\n"
    "  仅 DEBUG 日志,不扰用户。\n"
    "- 幂等: FabricServerNetworkBridge.initServer() 与\n"
    "  FabricClientNetworkBridge.initClient() 加 volatile boolean initialized\n"
    "  守护;dedicated server 上 main + server entrypoint 都调,\n"
    "  singleplayer 上 main + client entrypoint 都调,都安全。\n"
    "- 验证 (dedicated server, latest.log):\n"
    "    [qisheng_chess] Fabric mod initialized\n"
    "    [qisheng_chess] Fabric server init   (no 'init: skipping' line\n"
    "     because first call from main already set the flag)\n"
    "  - user must retest in singleplayer (集成服) to confirm GUI now opens."
)

def run_git(args: list[str], cwd: Path = WORKSPACE) -> str:
    proc = subprocess.run([GIT_EXE] + args, cwd=cwd, capture_output=True, text=True,
                        encoding="utf-8", errors="replace")
    if proc.returncode != 0:
        print(f"[git] args={args}", file=sys.stderr)
        print(f"[git] stdout={proc.stdout}", file=sys.stderr)
        print(f"[git] stderr={proc.stderr}", file=sys.stderr)
        raise SystemExit(proc.returncode)
    return proc.stdout

def main() -> int:
    # Sanity: working tree clean of phantom known_hosts
    proc = subprocess.run([GIT_EXE, "ls-files", "-o", "--exclude-standard"],
                        cwd=WORKSPACE, capture_output=True, text=True, encoding="utf-8")
    print("[ls-files -o]", proc.stdout.strip())
    if "known_hosts" in proc.stdout:
        print("WARNING: known_hosts still untracked; double-check", file=sys.stderr)

    # Clear stale lock
    lock = WORKSPACE / ".git" / "index.lock"
    if lock.exists():
        lock.unlink()
        print("[lock] removed stale .git/index.lock")

    # Reset to HEAD, then explicit add to keep phantom known_hosts out
    run_git(["reset", "--mixed", "HEAD"])
    for f in FILES:
        run_git(["add", "--", f])
        print(f"[add] {f}")

    # Verify staged
    staged = run_git(["diff", "--cached", "--name-only"]).strip().splitlines()
    print(f"[diff --cached] {len(staged)} files")
    assert len(staged) == len(FILES), f"expected {len(FILES)} staged, got {len(staged)}: {staged}"

    # Verify no known_hosts in staged
    assert all("known_hosts" not in s for s in staged), f"phantom known_hosts in staged: {staged}"

    # Commit (UTF-8 via file to avoid cp936 argfile encoding)
    msg_path = WORKSPACE / ".git" / "COMMIT_EDITMSG.tmp"
    msg_path.write_text(MSG, encoding="utf-8")
    try:
        out = run_git(["commit", "-F", str(msg_path)])
        print("[commit]", out.strip())
    finally:
        msg_path.unlink(missing_ok=True)

    head = run_git(["log", "-1", "--pretty=format:%H %s"])
    print(f"[HEAD] {head}")
    return 0


if __name__ == "__main__":
    sys.exit(main())