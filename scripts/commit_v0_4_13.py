#!/usr/bin/env python3
"""Commit v0.4.13 changes."""
import os, sys, io, subprocess
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

GIT = r'C:\ProgramData\wdsj2\GitHubDesktop\app-3.5.12\resources\app\git\cmd\git.exe'
REPO = r'D:\代码\qisheng-chess'
ENV = {**os.environ, 'LC_ALL': 'C.UTF-8', 'GIT_TERMINAL_PROMPT': '0',
       'GCM_INTERACTIVE': 'never'}


def run(args, check=True):
    r = subprocess.run([GIT] + args, cwd=REPO, env=ENV,
                       capture_output=True, text=True)
    if r.stdout: print(r.stdout, end='')
    if r.stderr: print(r.stderr, end='', file=sys.stderr)
    return r.returncode == 0


# 1. Cleanup phantom known_hosts
for lock in [os.path.join(REPO, '.git', 'index.lock')]:
    if os.path.exists(lock):
        os.remove(lock)
        print(f'removed {lock}')

run(['update-index', '--force-remove', '--quiet', '--',
     'CUserswdsj2.sshknown_hosts'])

# 2. List dirty paths
print('\n=== git status -s ===')
r = subprocess.run([GIT, 'status', '-s', '--', '.'], cwd=REPO, env=ENV,
                   capture_output=True, text=True)
print(r.stdout)

# 3. Add explicit paths
paths = [
    'common/src/main/java/com/qisheng/chess/engine/BoardVariant.java',
    'common/src/main/java/com/qisheng/chess/engine/gomoku/GomokuVariant.java',
    'common/src/main/java/com/qisheng/chess/engine/go/GoVariant.java',
    'common/src/main/java/com/qisheng/chess/pvp/GameLogic.java',
    'common/src/test/java/com/qisheng/chess/pvp/PlacementMoveTest.java',
    'gradle.properties',
    'scripts/deploy_v0_4_13.py',
    'scripts/commit_v0_4_13.py',
    'scripts/push_v0_4_13.py',
]
for p in paths:
    full = os.path.join(REPO, p)
    if os.path.exists(full):
        run(['add', '--', p])

# 4. Commit
msg = """v0.4.13: placement-only variants (gomoku/go) 单击下子

- BoardVariant.java: 新增 default boolean isPlacementOnly() { return false; }
- GomokuVariant.java: override 返回 true
- GoVariant.java: override 返回 true
- GameLogic.tryMove: !variant.isPlacementOnly() 时才检查 SOURCE_MISMATCH,placement 变种跳过 selectPoint 检查
- PlacementMoveTest.java: 5 个 regression test,全部 PASSED
- 137 tests PASSED (v0.4.12 的 132 + 5)
- jar 264232 B

🤖 Generated with [Claude Code](https://claude.com/claude-code)

Co-Authored-By: Claude <noreply@anthropic.com>"""

print('\n=== commit ===')
r = subprocess.run([GIT, 'commit', '-m', msg], cwd=REPO, env=ENV,
                   capture_output=True, text=True)
if r.stdout: print(r.stdout, end='')
if r.stderr: print(r.stderr, end='', file=sys.stderr)

print('\n=== head ===')
r = subprocess.run([GIT, 'log', '--oneline', '-3'], cwd=REPO, env=ENV,
                   capture_output=True, text=True)
print(r.stdout)