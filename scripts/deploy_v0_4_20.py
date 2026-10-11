#!/usr/bin/env python3
"""Deploy v0.4.20 to server + client, then restart server.

Flow (fixed v0.4.18 deploy bug: kill server BEFORE deleting old jar,
otherwise the running JVM still holds the file and PermissionError on
Windows):

  1. Kill the running server JVM (PowerShell)
  2. Copy new jar to server mods/ + client mods/
  3. Verify SHA256 on both copies
  4. Start the server detached (JDK 17 explicit path)
  5. Wait up to 90s for port 25565 to come back OPEN
"""

from __future__ import annotations

import hashlib
import shutil
import subprocess
import sys
import time
from pathlib import Path

JAVA = r'D:\工具\jdk-17.0.20.1+1\bin\java.exe'
SERVER_DIR = Path(r'D:\mc-server\1.20.1-fabric-0.19.5')
CLIENT_DIR = Path(r'D:\PCL 正式版 2.12.6\89\.minecraft\versions\1.20.1-Fabric 0.19.5\mods')
SOURCE_JAR = Path(r'D:\代码\qisheng-chess\fabric\build\libs\qisheng_chess-fabric-0.4.20.jar')
VERSION = '0.4.20'

def sha256(p: Path) -> str:
    h = hashlib.sha256()
    h.update(p.read_bytes())
    return h.hexdigest()

def kill_server() -> None:
    subprocess.run(['powershell', '-NoProfile', '-Command',
                    'Get-CimInstance Win32_Process -Filter "Name=\'java.exe\'" '
                    '| Where-Object { $_.CommandLine -like \'*fabric-server-launch*\' } '
                    '| ForEach-Object { Stop-Process -Id $_.ProcessId -Force }'],
                   check=False)
    time.sleep(2.0)

def start_server() -> None:
    subprocess.Popen(
        [JAVA, '-Xmx2G', '-Xms1G', '-jar', 'fabric-server-launch.jar', 'nogui'],
        cwd=str(SERVER_DIR),
        creationflags=0x00000008 | 0x00000200,   # DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )

def port_open(port: int, timeout: float = 90.0) -> bool:
    end = time.time() + timeout
    while time.time() < end:
        r = subprocess.run(['powershell', '-NoProfile', '-Command',
                            f'(Test-NetConnection -Port {port} -InformationLevel Quiet)'],
                           capture_output=True, text=True)
        if 'True' in r.stdout:
            return True
        time.sleep(2.0)
    return False

def main() -> int:
    if not SOURCE_JAR.exists():
        print(f'FATAL: source jar not found: {SOURCE_JAR}', file=sys.stderr)
        return 2

    expected = sha256(SOURCE_JAR)
    print(f'[deploy v0.4.20] source SHA256: {expected}')

    print('[deploy v0.4.20] killing old server...')
    kill_server()

    server_jar = SERVER_DIR / 'mods' / f'qisheng_chess-fabric-{VERSION}.jar'
    client_jar = CLIENT_DIR / f'qisheng_chess-fabric-{VERSION}.jar'

    # Remove any prior version jar
    for p in [server_jar, client_jar]:
        if p.exists():
            p.unlink()

    # Copy new jar
    shutil.copy2(SOURCE_JAR, server_jar)
    shutil.copy2(SOURCE_JAR, client_jar)

    # Verify
    for p in [server_jar, client_jar]:
        actual = sha256(p)
        status = 'OK' if actual == expected else 'MISMATCH'
        print(f'  {p} — {status} ({p.stat().st_size} B)')

    print('[deploy v0.4.20] starting server...')
    start_server()

    if port_open(25565, timeout=90):
        print('[deploy v0.4.20] port 25565 OPEN ✓')
    else:
        print('[deploy v0.4.20] WARN: port 25565 not open after 90s', file=sys.stderr)
        return 1

    return 0

if __name__ == '__main__':
    sys.exit(main())