#!/usr/bin/env python3
"""Build v0.4.12 with UTF-8 paths."""
import subprocess, sys, os, io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

env = os.environ.copy()
env['JAVA_HOME'] = r'D:\工具\jdk-17.0.20.1+1'
env['LC_ALL'] = 'C.UTF-8'
env['LANG'] = 'C.UTF-8'

args = [
    r'D:\代码\qisheng-chess\gradlew.bat',
    ':common:clean',
    ':common:compileJava',
    ':common:test',
    ':fabric:compileJava',
    ':fabric:remapJar',
]
r = subprocess.run(args, cwd=r'D:\代码\qisheng-chess', env=env, capture_output=True)
print('returncode:', r.returncode)
print('--- stdout tail ---')
print(r.stdout.decode('utf-8', errors='replace').split('\n')[-80:])
print('\n--- stderr tail ---')
print(r.stderr.decode('utf-8', errors='replace').split('\n')[-30:])