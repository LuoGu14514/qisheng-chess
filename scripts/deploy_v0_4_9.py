#!/usr/bin/env python3
"""Deploy qisheng-chess v0.4.9 (singleplayer initServer fix) to server + client."""
from __future__ import annotations
import hashlib, os, shutil, subprocess, sys, time
from pathlib import Path

WORKSPACE = Path(r"D:\代码\qisheng-chess")
BUILT_JAR = WORKSPACE / "fabric" / "build" / "libs" / "qisheng_chess-fabric-0.4.9.jar"
EXPECTED_SHA = "81b6827a849a2271ba79438736978f4aa7a0323d0ae1ae728a3881d495c95edb"

SERVER_JAR_DIR = Path(r"D:\mc-server\1.20.1-fabric-0.19.5\mods")
CLIENT_JAR_DIR = Path(r"D:\PCL 正式版 2.12.6\89\.minecraft\versions\1.20.1-Fabric 0.19.5\mods")

# Local (server-side jar) copy for the running server (the server may have the jar
# open via mmap; we still try a copy then fall back to delete+copy).
SERVER_RUNTIME_JAR = Path(r"D:\mc-server\1.20.1-fabric-0.19.5\mods\qisheng_chess-fabric-0.4.9.jar")
SERVER_OLD_JARS = [Path(r"D:\mc-server\1.20.1-fabric-0.19.5\mods\qisheng_chess-fabric-0.4.8.jar")]
CLIENT_OLD_JARS = [Path(r"D:\PCL 正式版 2.12.6\89\.minecraft\versions\1.20.1-Fabric 0.19.5\mods\qisheng_chess-fabric-0.4.8.jar")]


def sha256_file(p: Path) -> str:
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 16), b""):
            h.update(chunk)
    return h.hexdigest()


def kill_existing_server() -> int:
    """Kill existing qisheng-chess server process (PowerShell CIM)."""
    ps = ("powershell -NoProfile -Command "
          "\"Get-CimInstance Win32_Process -Filter \\\"Name='java.exe'\\\" | "
          "Where-Object { $_.CommandLine -like '*fabric-server-launch*' } | "
          "ForEach-Object { Write-Output $_.ProcessId }\"")
    r = subprocess.run(ps, capture_output=True, text=True)
    pids = [int(x) for x in r.stdout.split() if x.strip().isdigit()]
    print(f"[deploy] existing server pids: {pids}")
    killed = 0
    for pid in pids:
        subprocess.run(["taskkill", "/F", "/PID", str(pid)], capture_output=True)
        killed += 1
    time.sleep(3)
    return killed


def launch_server() -> int:
    """Start the dedicated server detached, wait for port 25565 OPEN, return pid."""
    cwd = r"D:\mc-server\1.20.1-fabric-0.19.5"
    flags = 0x00000008 | 0x00000200  # DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP
    cmd = ["java","-Xmx2G","-Xms1G","-jar","fabric-server-launch.jar","nogui"]
    proc = subprocess.Popen(cmd, cwd=cwd, stdin=subprocess.DEVNULL,
                          stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                          creationflags=flags, close_fds=True)
    print(f"[deploy] launched server pid={proc.pid}")
    # wait for port 25565 OPEN
    import socket as _os
    deadline = time.time() + 60
    while time.time() < deadline:
        s = _os.socket()
        s.settimeout(0.5)
        try:
            s.connect(("127.0.0.1", 25565))
            s.close()
            print(f"[deploy] port 25565 OPEN after {int(time.time()-deadline+60)}s")
            return proc.pid
        except OSError:
            s.close()
            time.sleep(1)
    raise RuntimeError("port 25565 did not open within 60s")


def deploy_to_dir(target_dir: Path, old_jars: list[Path]) -> None:
    """Delete old + copy new + verify."""
    for old in old_jars:
        if old.exists():
            print(f"[deploy] removing old {old}")
            old.unlink()
    target = target_dir / BUILT_JAR.name
    if target.exists():
        print(f"[deploy] removing existing target {target}")
        target.unlink()
    print(f"[deploy] copying {BUILT_JAR} -> {target}")
    shutil.copy2(BUILT_JAR, target)
    actual = sha256_file(target)
    expected_full = sha256_file(BUILT_JAR)
    if actual != expected_full:
        raise RuntimeError(f"sha mismatch on {target}: expected {expected_full}, got {actual}")
    print(f"[deploy] sha256 verified {actual[:16]}")


def main() -> int:
    if not BUILT_JAR.exists():
        print(f"FATAL: jar not found: {BUILT_JAR}", file=sys.stderr)
        return 2
    if sha256_file(BUILT_JAR)[:len(EXPECTED_SHA)] != EXPECTED_SHA:
        print(f"FATAL: sha256 prefix mismatch on built jar", file=sys.stderr)
        return 3

    # 1. server side: kill, replace jar, restart
    killed = kill_existing_server()
    deploy_to_dir(SERVER_JAR_DIR, SERVER_OLD_JARS)
    pid = launch_server()

    # 2. client side: replace jar (client isn't running, plain copy)
    deploy_to_dir(CLIENT_JAR_DIR, CLIENT_OLD_JARS)

    print(f"[deploy] v0.4.9 deployed: killed={killed} server_pid={pid}")
    return 0


if __name__ == "__main__":
    sys.exit(main())