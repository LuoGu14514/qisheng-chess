"""Deploy v0.4.18 jar to both server and client, restart server, verify port.

Pattern mirrors deploy_v0_4_17.py:
  1. Compute sha256 of the new jar
  2. Kill running server (PowerShell Get-CimInstance Win32_Process)
  3. Copy to server mods + client mods (lowercase sha verify)
  4. Start server detached with JDK 17
  5. Poll port 25565 until OPEN (90s timeout)
  6. Read latest.log tail to confirm v0.4.18 loaded
"""
import os
import sys
import shutil
import subprocess
import hashlib
import socket
import time
from pathlib import Path

ROOT = Path(r'D:\代码\qisheng-chess')
JAR_SRC = ROOT / 'fabric' / 'build' / 'libs' / 'qisheng_chess-fabric-0.4.18.jar'
SERVER_MODS = Path(r'D:\mc-server\1.20.1-fabric-0.19.5\mods')
CLIENT_MODS = Path(r'D:\PCL 正式版 2.12.6\89\.minecraft\versions\1.20.1-Fabric 0.19.5\mods')
SERVER_DIR = Path(r'D:\mc-server\1.20.1-fabric-0.19.5')
SERVER_LOG = SERVER_DIR / 'logs' / 'latest.log'
SERVER_LAUNCH = SERVER_DIR / 'fabric-server-launch.jar'
JAVA = r'D:\工具\jdk-17.0.20.1+1\bin\java.exe'


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda: f.read(65536), b''):
            h.update(chunk)
    return h.hexdigest()


def kill_server():
    """Kill the running server by its main-class via CIM."""
    ps = subprocess.run(
        ['powershell', '-NoProfile', '-Command',
         "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" "
         "| Where-Object { $_.CommandLine -like '*fabric-server-launch*' } "
         "| ForEach-Object { Stop-Process -Id $_.ProcessId -Force }"],
        capture_output=True, text=True, timeout=30,
    )
    killed = ps.stdout.strip()
    if killed:
        print(f'[kill] {killed}')
    else:
        print('[kill] no java process found running fabric-server-launch')
    time.sleep(3)


def port_open(port: int, timeout: float = 90.0) -> bool:
    end = time.time() + timeout
    while time.time() < end:
        try:
            with socket.create_connection(('127.0.0.1', port), timeout=2):
                return True
        except OSError:
            time.sleep(2)
    return False


def main():
    if not JAR_SRC.exists():
        print(f'ERR: jar not found: {JAR_SRC}', file=sys.stderr)
        sys.exit(1)

    expected = sha256(JAR_SRC)
    print(f'[sha] {JAR_SRC.name} = {expected}')

    # Kill the server first so we can replace its loaded jar.
    kill_server()

    # Remove old v0.4.x jars from server + client (keep qisheng_chess-0.4.18.jar)
    for mods_dir in (SERVER_MODS, CLIENT_MODS):
        for old in mods_dir.glob('qisheng_chess-fabric-0.4.*.jar'):
            if old.resolve() == JAR_SRC.resolve():
                continue
            if '0.4.18' in old.name:
                continue
            print(f'[rm ] {old}')
            old.unlink()

    # Copy new jar to server + client
    for mods_dir in (SERVER_MODS, CLIENT_MODS):
        dst = mods_dir / JAR_SRC.name
        shutil.copy2(JAR_SRC, dst)
        verify = sha256(dst).lower()
        if verify != expected.lower():
            print(f'ERR: sha mismatch on {dst}', file=sys.stderr)
            sys.exit(1)
        print(f'[copy] {dst} (sha verified)')
    DETACHED = 0x00000008 | 0x00000200  # DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP
    subprocess.Popen(
        [JAVA, '-Xmx2G', '-Xms1G', '-jar', str(SERVER_LAUNCH), 'nogui'],
        cwd=str(SERVER_DIR),
        creationflags=DETACHED,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        stdin=subprocess.DEVNULL,
        close_fds=True,
    )

    if not port_open(25565, timeout=90):
        print('ERR: port 25565 not open within 90s', file=sys.stderr)
        sys.exit(2)
    print('[port] 25565 OPEN')

    # Wait for "Done" line
    end = time.time() + 30
    while time.time() < end:
        if SERVER_LOG.exists():
            text = SERVER_LOG.read_text(encoding='utf-8', errors='replace')
            if '0.4.18' in text and 'Done (' in text:
                print(f'[log ] v0.4.18 + Done detected')
                print('OK: v0.4.18 deployed')
                return
        time.sleep(2)
    print('WARN: log did not show 0.4.18 + Done within 30s, check manually')


if __name__ == '__main__':
    main()