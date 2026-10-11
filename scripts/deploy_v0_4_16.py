"""Deploy v0.4.16: kill server, copy jar to server+client mods, restart server.

v0.4.16 changes:
- Two-click placement for gomoku/go: first click shows a translucent blue
  preview ring, second click on the same square confirms and sends
  ACTION_PLACE. Different preview position is moved by clicking elsewhere;
  occupied square click clears the preview. Ring is drawn after the stones
  so it always sits on top. Cleared on every server sync (applySync) so
  the preview cannot linger past the board state it was generated for.
"""
import hashlib
import os
import shutil
import subprocess
import sys
import time

# --- paths ----------------------------------------------------------------
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(SCRIPT_DIR)

SOURCE_JAR = os.path.join(PROJECT_ROOT, "fabric", "build", "libs",
                          "qisheng_chess-fabric-0.4.16.jar")
SERVER_DIR = r"D:\mc-server\1.20.1-fabric-0.19.5"
SERVER_MODS = os.path.join(SERVER_DIR, "mods")
SERVER_JAR = os.path.join(SERVER_MODS, "qisheng_chess-fabric-0.4.16.jar")

CLIENT_DIR = r"D:\PCL 正式版 2.12.6\89\.minecraft\versions\1.20.1-Fabric 0.19.5\mods"
CLIENT_JAR = os.path.join(CLIENT_DIR, "qisheng_chess-fabric-0.4.16.jar")

SERVER_LAUNCHER = os.path.join(SERVER_DIR, "fabric-server-launch.jar")

PS_KILL = (
    "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | "
    "Where-Object { $_.CommandLine -like '*fabric-server-launch*.jar*' } | "
    "ForEach-Object { Stop-Process -Id $_.ProcessId -Force; "
    "Write-Host (\"killed pid=\" + $_.ProcessId) }"
)


def sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(64 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def must_match(a: str, b: str, label: str) -> None:
    if not os.path.exists(b):
        print(f"[{label}] missing: {b}", file=sys.stderr)
        sys.exit(2)
    actual = sha256(b)
    if actual != a:
        print(f"[{label}] SHA256 mismatch!\n  expected: {a}\n  actual:   {actual}",
              file=sys.stderr)
        sys.exit(3)
    print(f"[{label}] SHA256 OK ({actual[:12]}...)")


def main() -> None:
    if not os.path.isfile(SOURCE_JAR):
        print(f"source jar not found: {SOURCE_JAR}", file=sys.stderr)
        sys.exit(1)
    src_hash = sha256(SOURCE_JAR)
    print(f"source: {SOURCE_JAR}")
    print(f"  size: {os.path.getsize(SOURCE_JAR)} B")
    print(f"  sha256: {src_hash}")

    print("\n[1/4] killing running fabric-server (if any)...")
    subprocess.run(
        ["powershell.exe", "-NoProfile", "-Command", PS_KILL],
        capture_output=True, text=True, check=False,
    )
    time.sleep(3)

    print("\n[2/4] replacing server jar...")
    for old in os.listdir(SERVER_MODS):
        if old.startswith("qisheng_chess-fabric-") and old.endswith(".jar") \
                and old != os.path.basename(SERVER_JAR):
            old_path = os.path.join(SERVER_MODS, old)
            os.remove(old_path)
            print(f"  removed old: {old}")
    shutil.copy2(SOURCE_JAR, SERVER_JAR)
    must_match(src_hash, SERVER_JAR, "server")

    print("\n[3/4] replacing client jar...")
    if not os.path.isdir(CLIENT_DIR):
        print(f"  client dir not found, skipping: {CLIENT_DIR}")
    else:
        for old in os.listdir(CLIENT_DIR):
            if old.startswith("qisheng_chess-fabric-") and old.endswith(".jar") \
                    and old != os.path.basename(CLIENT_JAR):
                old_path = os.path.join(CLIENT_DIR, old)
                os.remove(old_path)
                print(f"  removed old: {old}")
        shutil.copy2(SOURCE_JAR, CLIENT_JAR)
        must_match(src_hash, CLIENT_JAR, "client")

    print("\n[4/4] starting server (detached)...")
    JAVA = r"D:\工具\jdk-17.0.20.1+1\bin\java.exe"
    DETACHED_PROCESS = 0x00000008
    CREATE_NEW_PROCESS_GROUP = 0x00000200
    creationflags = DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP
    proc = subprocess.Popen(
        [JAVA, "-Xmx2G", "-Xms1G", "-jar", SERVER_LAUNCHER, "nogui"],
        cwd=SERVER_DIR,
        creationflags=creationflags,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        stdin=subprocess.DEVNULL,
        close_fds=True,
    )
    print(f"  launched pid={proc.pid}")

    # The server takes ~30-50s to fully start (mixin + world load); wait
    # up to 90s for port 25565.
    print("  waiting for port 25565 to open (up to 90s)...")
    import socket
    deadline = time.time() + 90
    while time.time() < deadline:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            s.settimeout(1)
            try:
                s.connect(("127.0.0.1", 25565))
                print("  port 25565 OPEN")
                return
            except OSError:
                time.sleep(1)
    print("  port 25565 did NOT open within 90s", file=sys.stderr)
    sys.exit(4)


if __name__ == "__main__":
    main()