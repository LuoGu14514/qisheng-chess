#!/usr/bin/env python3
"""Push HEAD to origin/master with retry/backoff for github.com:443 outages."""

from __future__ import annotations

import subprocess
import sys
import time

GIT = r'C:\ProgramData\wdsj2\GitHubDesktop\app-3.5.12\resources\app\git\cmd\git.exe'
REPO = r'D:\代码\qisheng-chess'

def run(args: list[str]) -> tuple[int, str]:
    env = {
        'GIT_TERMINAL_PROMPT': '0',
        'GCM_INTERACTIVE': 'Never',
        'LC_ALL': 'C.UTF-8',
    }
    r = subprocess.run([GIT, '-C', REPO] + args, capture_output=True, text=True, encoding='utf-8', env=env)
    return r.returncode, (r.stdout or '') + (r.stderr or '')

def main() -> int:
    backoffs = [0, 5, 15, 30, 60]
    for attempt, delay in enumerate(backoffs, 1):
        if delay:
            print(f'[push v0.4.21] attempt {attempt}: waiting {delay}s ...')
            time.sleep(delay)
        else:
            print(f'[push v0.4.21] attempt {attempt}: trying now')
        rc, out = run(['push', 'origin', 'master'])
        print(f'  -> exit {rc}')
        if out.strip():
            for line in out.strip().splitlines()[-6:]:
                print(f'     {line}')
        if rc == 0:
            print('[push v0.4.21] OK')
            return 0
    print('[push v0.4.21] FAILED — github.com:443 outage persists', file=sys.stderr)
    return 1

if __name__ == '__main__':
    sys.exit(main())