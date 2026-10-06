#!/usr/bin/env python3
"""Deploy v0.4.13: kill server, replace jars, restart."""
import os, sys, io, subprocess, time, socket, hashlib
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

SERVER_DIR = r'D:\mc-server\1.20.1-fabric-0.19.5'
CLIENT_DIR = r'D:\PCL 正式版 2.12.6\89\.minecraft\versions\1.20.1-Fabric 0.19.5\mods'
SOURCE_JAR = r'D:\代码\qisheng-chess\fabric\build\libs\qisheng_chess-fabric-0.4.13.jar'
LAUNCH = os.path.join(SERVER_DIR, 'fabric-server-launch.jar')
JAVA = r'D:\工具\jdk-17.0.20.1+1\bin\java.exe'

def sha256(p):
    return hashlib.sha256(open(p, 'rb').read()).hexdigest()

print('=== kill old server ===')
subprocess.run(['powershell', '-NoProfile', '-Command',
    "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | "
    "Where-Object { $_.CommandLine -like '*fabric-server-launch*' } | "
    "ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue; "
    "Write-Host ('killed pid ' + $_.ProcessId) }"],
    capture_output=True)
time.sleep(3)

# wait for port close
deadline = time.time() + 15
while time.time() < deadline:
    try:
        with socket.create_connection(('127.0.0.1', 25565), timeout=1):
            time.sleep(1)
    except OSError:
        print('  port 25565 closed')
        break
else:
    print('  port still open after 15s')

print('\n=== replace server jar ===')
old = os.path.join(SERVER_DIR, 'mods', 'qisheng_chess-fabric-0.4.12.jar')
new = os.path.join(SERVER_DIR, 'mods', 'qisheng_chess-fabric-0.4.13.jar')
if os.path.exists(old):
    os.remove(old)
    print(f'  removed {os.path.basename(old)}')
import shutil
shutil.copy2(SOURCE_JAR, new)
print(f'  copied source.jar -> {os.path.basename(new)}')
print(f'  server jar sha256: {sha256(new)}')

print('\n=== replace client jar ===')
old = os.path.join(CLIENT_DIR, 'qisheng_chess-fabric-0.4.12.jar')
new = os.path.join(CLIENT_DIR, 'qisheng_chess-fabric-0.4.13.jar')
if os.path.exists(old):
    os.remove(old)
    print(f'  removed {os.path.basename(old)}')
shutil.copy2(SOURCE_JAR, new)
print(f'  copied source.jar -> {os.path.basename(new)}')
print(f'  client jar sha256: {sha256(new)}')

print('\n=== start server ===')
flags = 0x00000008 | 0x00000200
cmd = [JAVA, '-Xmx2G', '-Xms1G', '-jar', LAUNCH, 'nogui']
print(f'  java: {JAVA}')
subprocess.Popen(cmd, cwd=SERVER_DIR, creationflags=flags)

print('\n=== wait for port 25565 OPEN ===')
deadline = time.time() + 60
opened = False
while time.time() < deadline:
    try:
        with socket.create_connection(('127.0.0.1', 25565), timeout=1):
            opened = True
            break
    except OSError:
        time.sleep(1)
if opened:
    print('  port 25565 OPEN')
else:
    print('  port 25565 TIMEOUT')
    raise SystemExit(1)

time.sleep(3)
log = os.path.join(SERVER_DIR, 'logs', 'latest.log')
if os.path.exists(log):
    lines = open(log, encoding='utf-8', errors='replace').readlines()
    relevant = [l for l in lines[-30:] if 'qisheng_chess' in l.lower() or 'online' in l.lower() or 'auth' in l.lower()]
    print('\n--- relevant log lines ---')
    print(''.join(relevant[-15:]))