#!/usr/bin/env python3
"""Check git status."""
import os, subprocess, sys, io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

env = os.environ.copy()
env['GIT_TERMINAL_PROMPT'] = '0'
env['GCM_INTERACTIVE'] = 'never'
env['LC_ALL'] = 'C.UTF-8'

GIT = r"C:\ProgramData\wdsj2\GitHubDesktop\app-3.5.12\resources\app\git\cmd\git.exe"

args = [GIT, '-C', r'D:\代码\qisheng-chess', 'status']
r = subprocess.run(args, env=env, capture_output=True)
print('returncode:', r.returncode)
print(r.stdout.decode('utf-8', errors='replace'))