#!/usr/bin/env python3
"""Kill existing dedicated server (PowerShell CIM) and relaunch detached; wait for port 25565."""
from __future__ import annotations
import os, socket, subprocess, sys, time
from pathlib import Path

SERVER_CWD = Path(r"D:\mc-server\1.20.1-fabric-0.19.5")


def kill_server() -> int:
    ps = ("powershell -NoProfile -Command "
          "\"Get-CimInstance Win32_Process -Filter \\\"Name='java.exe'\\\" | "
          "Where-Object { $_.CommandLine -like '*fabric-server-launch*' } | "
          "ForEach-Object { Write-Output $_.ProcessId }\"")
    r = subprocess.run(ps, capture_output=True, text=True)
    pids = [int(x) for x in r.stdout.split() if x.strip().isdigit()]
    print(f"[restart] existing server pids: {pids}")
    killed = 0
    for pid in pids:
        subprocess.run(["taskkill", "/F", "/PID", str(pid)], capture_output=True)
        killed += 1
    time.sleep(3)
    return killed


def wait_port_closed(port: int, timeout: int = 15) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        s = socket.socket()
        s.settimeout(0.3)
        try:
            s.connect(("127.0.0.1", port))
            s.close()
        except OSError:
            s.close()
            return True
        time.sleep(0.5)
    return False


def wait_port_open(port: int, timeout: int = 60) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        s = socket.socket()
        s.settimeout(0.5)
        try:
            s.connect(("127.0.0.1", port))
            s.close()
            return True
        except OSError:
            s.close()
            time.sleep(1)
    return False


def launch_server() -> int:
    flags = 0x00000008 | 0x00000200  # DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP
    cmd = ["java", "-Xmx2G", "-Xms1G", "-jar", "fabric-server-launch.jar", "nogui"]
    proc = subprocess.Popen(cmd, cwd=str(SERVER_CWD), stdin=subprocess.DEVNULL,
                          stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                          creationflags=flags, close_fds=True)
    print(f"[restart] launched new server pid={proc.pid}")
    return proc.pid


def main() -> int:
    killed = kill_server()
    if not wait_port_closed(25565, timeout=15):
        print("[restart] WARNING: port 25565 still open after kill", file=sys.stderr)
    pid = launch_server()
    if wait_port_open(25565, timeout=60):
        print(f"[restart] port 25565 OPEN; server pid={pid} killed={killed}")
    else:
        print("[restart] FATAL: port 25565 did not open within 60s", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())