#!/usr/bin/env python3
"""Deploy qisheng-chess v0.4.10 (singleplayer diagnostic logging)."""
from __future__ import annotations
import hashlib, os, shutil, socket, subprocess, sys, time
from pathlib import Path

WORKSPACE = Path(r"D:\代码\qisheng-chess")
BUILT_JAR = WORKSPACE / "fabric" / "build" / "libs" / "qisheng_chess-fabric-0.4.10.jar"
SERVER_DIR = Path(r"D:\mc-server\1.20.1-fabric-0.19.5\mods")
CLIENT_DIR = Path(r"D:\PCL 正式版 2.12.6\89\.minecraft\versions\1.20.1-Fabric 0.19.5\mods")
SERVER_OLD = [SERVER_DIR / "qisheng_chess-fabric-0.4.9.jar"]
CLIENT_OLD = [CLIENT_DIR / "qisheng_chess-fabric-0.4.9.jar"]


def sha256_file(p: Path) -> str:
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 16), b""):
            h.update(chunk)
    return h.hexdigest()


def kill_server() -> int:
    ps = ("powershell -NoProfile -Command "
          "\"Get-CimInstance Win32_Process -Filter \\\"Name='java.exe'\\\" | "
          "Where-Object { $_.CommandLine -like '*fabric-server-launch*' } | "
          "ForEach-Object { Write-Output $_.ProcessId }\"")
    r = subprocess.run(ps, capture_output=True, text=True)
    pids = [int(x) for x in r.stdout.split() if x.strip().isdigit()]
    killed = 0
    for pid in pids:
        subprocess.run(["taskkill", "/F", "/PID", str(pid)], capture_output=True)
        killed += 1
    time.sleep(3)
    return killed


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
    flags = 0x00000008 | 0x00000200
    cmd = ["java", "-Xmx2G", "-Xms1G", "-jar", "fabric-server-launch.jar", "nogui"]
    proc = subprocess.Popen(cmd, cwd=str(SERVER_DIR.parent), stdin=subprocess.DEVNULL,
                          stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                          creationflags=flags, close_fds=True)
    return proc.pid


def deploy_to_dir(target_dir: Path, old_jars: list[Path]) -> None:
    for old in old_jars:
        if old.exists():
            old.unlink()
    target = target_dir / BUILT_JAR.name
    if target.exists():
        target.unlink()
    shutil.copy2(BUILT_JAR, target)
    actual = sha256_file(target)
    expected_full = sha256_file(BUILT_JAR)
    if actual != expected_full:
        raise RuntimeError(f"sha mismatch on {target}")
    print(f"[deploy] sha256 verified {actual[:16]}")


def main() -> int:
    if sha256_file(BUILT_JAR) != sha256_file(BUILT_JAR):
        pass
    killed = kill_server()
    deploy_to_dir(SERVER_DIR, SERVER_OLD)
    pid = launch_server()
    if not wait_port_open(25565, timeout=60):
        print("FATAL: port 25565 didn't open", file=sys.stderr)
        return 1
    print(f"[deploy] server pid={pid} port=25565 OPEN killed={killed}")
    deploy_to_dir(CLIENT_DIR, CLIENT_OLD)
    return 0


if __name__ == "__main__":
    sys.exit(main())