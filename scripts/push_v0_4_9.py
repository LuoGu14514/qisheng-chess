#!/usr/bin/env python3
"""Push v0.4.9 with retry+env override."""
from __future__ import annotations
import os, subprocess, sys, time
from pathlib import Path

WORKSPACE = Path(r"D:\代码\qisheng-chess")
GIT_EXE = r"C:\ProgramData\wdsj2\GitHubDesktop\app-3.5.12\resources\app\git\cmd\git.exe"

ENV_OVERRIDES = {
    "GIT_TERMINAL_PROMPT": "0",
    "GCM_INTERACTIVE": "never",
    "LC_ALL": "C.UTF-8",
    "PYTHONIOENCODING": "utf-8",
}

def run_push(attempt: int) -> bool:
    env = os.environ.copy()
    env.update(ENV_OVERRIDES)
    proc = subprocess.run([GIT_EXE, "push", "origin", "master"],
                        cwd=WORKSPACE, env=env, capture_output=True,
                        text=True, encoding="utf-8", errors="replace",
                        timeout=90)
    print(f"[push attempt {attempt}] rc={proc.returncode}")
    if proc.stdout.strip(): print(f"[stdout]\n{proc.stdout}")
    if proc.stderr.strip(): print(f"[stderr]\n{proc.stderr}")
    return proc.returncode == 0


def main() -> int:
    for attempt in range(1, 6):
        if run_push(attempt):
            head = subprocess.run([GIT_EXE, "log", "-1", "--pretty=format:%H %s"],
                                 cwd=WORKSPACE, capture_output=True, text=True,
                                 encoding="utf-8").stdout
            print(f"[HEAD after push] {head}")
            return 0
        backoff = min(5 * attempt, 30)
        print(f"[retry in {backoff}s]", file=sys.stderr)
        time.sleep(backoff)
    print("FATAL: all push attempts failed", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())