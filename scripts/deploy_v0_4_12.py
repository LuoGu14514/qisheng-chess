#!/usr/bin/env python3
"""Deploy v0.4.12 to BOTH dedicated server and PCL client."""
import os
import sys
import subprocess
import time
import hashlib
import shutil
import socket

if sys.platform == "win32":
    sys.stdout = __import__("io").TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

JAR = r"D:\代码\qisheng-chess\fabric\build\libs\qisheng_chess-fabric-0.4.12.jar"
SERVER_DIR = "D:\\mc-server\\1.20.1-fabric-0.19.5"
CLIENT_DIR = r"D:\PCL 正式版 2.12.6\89\.minecraft\versions\1.20.1-Fabric 0.19.5\mods"
SERVER_DIR_MODS = os.path.join(SERVER_DIR, "mods")
LAUNCH = os.path.join(SERVER_DIR, "fabric-server-launch.jar")


def sha256(path):
    return hashlib.sha256(open(path, "rb").read()).hexdigest()


def kill_server():
    script = (
        "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | "
        "Where-Object { $_.CommandLine -like '*fabric-server-launch*' } | "
        "ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue; "
        "Write-Host (\"killed pid \" + $_.ProcessId) }"
    )
    subprocess.run(["powershell", "-NoProfile", "-Command", script], capture_output=True)
    time.sleep(2)


def port_open(host="127.0.0.1", port=25565, timeout=30):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with socket.create_connection((host, port), timeout=1):
                return True
        except OSError:
            time.sleep(0.5)
    return False


def main():
    src = JAR
    print(f"src: {os.path.basename(src)} ({os.path.getsize(src)} B) sha256={sha256(src)[:16]}")

    kill_server()

    target_server = os.path.join(SERVER_DIR_MODS, "qisheng_chess-fabric-0.4.12.jar")
    for fname in os.listdir(SERVER_DIR_MODS):
        if fname.startswith("qisheng_chess-fabric-") and fname.endswith(".jar"):
            full = os.path.join(SERVER_DIR_MODS, fname)
            print(f"  removing old server jar: {fname} ({os.path.getsize(full)} B)")
            os.remove(full)
    shutil.copy2(src, target_server)
    print(f"  copied to server: {os.path.basename(target_server)} ({os.path.getsize(target_server)} B) sha256={sha256(target_server)[:16]}")

    target_client = os.path.join(CLIENT_DIR, "qisheng_chess-fabric-0.4.12.jar")
    if not os.path.isdir(CLIENT_DIR):
        raise SystemExit(f"client mods folder missing: {CLIENT_DIR}")
    for fname in os.listdir(CLIENT_DIR):
        if fname.startswith("qisheng_chess-fabric-") and fname.endswith(".jar"):
            full = os.path.join(CLIENT_DIR, fname)
            print(f"  removing old client jar: {fname} ({os.path.getsize(full)} B)")
            os.remove(full)
    shutil.copy2(src, target_client)
    print(f"  copied to client: {os.path.basename(target_client)} ({os.path.getsize(target_client)} B) sha256={sha256(target_client)[:16]}")

    flags = 0x00000008 | 0x00000200
    cmd = ["java", "-Xmx2G", "-Xms1G", "-jar", LAUNCH, "nogui"]
    print(f"  launching: {' '.join(cmd)}")
    subprocess.Popen(cmd, cwd=SERVER_DIR, creationflags=flags)
    print("  waiting for port 25565 ...", flush=True)
    if port_open(timeout=30):
        print("  port 25565 OPEN \u2713")
    else:
        raise SystemExit("port 25565 did not open in 30 s")
    print("done")


if __name__ == "__main__":
    main()