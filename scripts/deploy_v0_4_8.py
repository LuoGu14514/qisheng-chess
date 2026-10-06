#!/usr/bin/env python3
"""v0.4.8 deploy — replace jar on server + client, restart server.

v0.4.8 fixes the GUI-not-opening bug: ChessOpenScreenS2CPacket +
ChessSyncS2CPacket totalSquaresFor() used to hardcode "xiangqi → 256,
international → 64, default → 256". For gomoku (225), go9 (81) and go19
(361) that mismatch made the client-side bitmap read throw before
mc.setScreen could run — the popup arrived (CHESS_POPUP) but the GUI
never opened (CHESS_OPEN_SCREEN). Both packets now resolve
totalSquares via BoardRegistry.getByIdOrDefault(variantId).totalSquares()
so every variant lines up server write ↔ client read.

Also: Gomoku/Go winner mapping fix (GameSession.checkGameOver), go19
FEN rank encoding for ranks 10..19 ('a'..'j'), deleted redundant
halfmoveClock check, GameSessionWinnerTest with 3 tests.
"""

import os
import shutil
import subprocess
import sys
import time

SERVER_MODS = r"D:\mc-server\1.20.1-fabric-0.19.5\mods"
CLIENT_MODS = r"D:\PCL 正式版 2.12.6\89\.minecraft\versions\1.20.1-Fabric 0.19.5\mods"
BUILD_JAR = r"D:\代码\qisheng-chess\fabric\build\libs\qisheng_chess-fabric-0.4.8.jar"
VERSION = "0.4.8"


def sha256(path):
    import hashlib
    with open(path, "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()


def kill_server():
    """Kill any existing qisheng server process."""
    # PowerShell one-liner: find java.exe PIDs whose CommandLine contains
    # 'fabric-server-launch.jar'. wmic is deprecated on Windows 11.
    ps = (
        "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | "
        "Where-Object { $_.CommandLine -match 'fabric-server-launch\\.jar' } | "
        "Select-Object -ExpandProperty ProcessId")
    result = subprocess.run(
        ["powershell", "-NoProfile", "-Command", ps],
        capture_output=True, text=True, encoding="utf-8", errors="replace")
    pids = [line.strip() for line in result.stdout.splitlines()
            if line.strip().isdigit()]
    for pid in pids:
        print(f"[kill] pid={pid}")
        subprocess.run(["taskkill", "/F", "/PID", pid], capture_output=True)
    return True


def replace_in_dir(d, label):
    if not os.path.isdir(d):
        print(f"[{label}] missing dir: {d}")
        return False
    # Delete any older qisheng_chess-fabric-*.jar
    removed = []
    for name in os.listdir(d):
        if name.startswith("qisheng_chess-fabric-") and name.endswith(".jar"):
            old = os.path.join(d, name)
            try:
                os.remove(old)
                removed.append(name)
            except OSError as e:
                print(f"[{label}] remove fail: {old}: {e}")
    # Copy the new jar
    target = os.path.join(d, f"qisheng_chess-fabric-{VERSION}.jar")
    shutil.copy2(BUILD_JAR, target)
    new_sha = sha256(target)
    build_sha = sha256(BUILD_JAR)
    ok = new_sha == build_sha
    print(f"[{label}] {'OK' if ok else 'FAIL'} sha256={new_sha[:12]}… removed={removed}")
    return ok


def start_server():
    """Launch server detached, return when port 25565 is OPEN."""
    server_dir = r"D:\mc-server\1.20.1-fabric-0.19.5"
    log_path = os.path.join(server_dir, "logs", "latest.log")
    DETACHED = 0x00000008 | 0x00000200  # DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP
    proc = subprocess.Popen(
        ["java", "-Xmx2G", "-Xms1G", "-jar", "fabric-server-launch.jar", "nogui"],
        cwd=server_dir, stdin=subprocess.DEVNULL,
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        creationflags=DETACHED, close_fds=True)
    print(f"[server] launched pid={proc.pid}")
    # Wait for port
    import socket
    for attempt in range(60):
        try:
            with socket.create_connection(("127.0.0.1", 25565), timeout=1):
                print(f"[server] port 25565 OPEN (after {attempt}s)")
                return True
        except OSError:
            time.sleep(1)
    print("[server] port 25565 did NOT open in 60s")
    return False


def main():
    if not os.path.isfile(BUILD_JAR):
        print(f"[abort] jar missing: {BUILD_JAR}")
        return 1
    print(f"[info] jar: {BUILD_JAR} sha256={sha256(BUILD_JAR)[:12]}…")
    kill_server()
    time.sleep(2)
    ok_server = replace_in_dir(SERVER_MODS, "server")
    ok_client = replace_in_dir(CLIENT_MODS, "client")
    started = start_server()
    return 0 if (ok_server and ok_client and started) else 1


if __name__ == "__main__":
    sys.exit(main())