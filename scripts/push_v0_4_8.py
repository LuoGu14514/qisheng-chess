#!/usr/bin/env python3
"""Push v0.4.8 to GitHub. Retries up to 5 times with backoff because
github.com:443 has been unreliable from this machine."""

import io
import os
import subprocess
import sys
import time

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")

REPO = r"D:\代码\qisheng-chess"
GIT = r"C:\ProgramData\wdsj2\GitHubDesktop\app-3.5.12\resources\app\git\cmd\git.exe"


def run(*args, timeout=60):
    env = os.environ.copy()
    env["LC_ALL"] = "C.UTF-8"
    env["GIT_TERMINAL_PROMPT"] = "0"
    env["GCM_INTERACTIVE"] = "never"
    env["PYTHONIOENCODING"] = "utf-8"
    return subprocess.run([GIT] + list(args), cwd=REPO,
                          capture_output=True, text=True,
                          encoding="utf-8", errors="replace",
                          env=env, timeout=timeout)


def main():
    last_err = None
    for attempt in range(1, 6):
        print(f"\n[push attempt {attempt}/5]")
        try:
            r = run("push", "origin", "master", timeout=120)
            if r.returncode == 0:
                print("[push OK]")
                print(r.stdout)
                return 0
            print(f"[push FAIL rc={r.returncode}]")
            print("stdout:", r.stdout[:300])
            print("stderr:", r.stderr[:300])
            last_err = r.stderr
        except subprocess.TimeoutExpired:
            print(f"[push TIMEOUT after 120s]")
            last_err = "timeout"
        # backoff 5s, 10s, 15s, 20s
        if attempt < 5:
            time.sleep(5 * attempt)
    print(f"\n[push FAILED after 5 attempts] last_err={last_err}")
    return 1


if __name__ == "__main__":
    sys.exit(main())