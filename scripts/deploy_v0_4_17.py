#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v0.4.17 部署脚本 —— 交点视觉强化版
- kill 现有 fabric 服务器进程
- 复制 jar 到 server + client mods 目录
- 启动 detached server,等端口 OPEN
"""
import os
import shutil
import subprocess
import time
import hashlib
import socket

SOURCE_JAR = r'D:\代码\qisheng-chess\fabric\build\libs\qisheng_chess-fabric-0.4.17.jar'
SERVER_DIR = r'D:\mc-server\1.20.1-fabric-0.19.5\mods'
CLIENT_DIR = r'D:\PCL 正式版 2.12.6\89\.minecraft\versions\1.20.1-Fabric 0.19.5\mods'
SERVER_WORKDIR = r'D:\mc-server\1.20.1-fabric-0.19.5'
JAVA = r'D:\工具\jdk-17.0.20.1+1\bin\java.exe'
PORT = 25565


def sha256(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(8192), b''):
            h.update(chunk)
    return h.hexdigest()


def kill_server():
    print('[kill] 查找 fabric-server-launch 进程...')
    cmd = (
        "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | "
        "Where-Object { $_.CommandLine -like '*fabric-server-launch*' } | "
        "Select-Object -ExpandProperty ProcessId"
    )
    result = subprocess.run(
        ['powershell', '-NoProfile', '-Command', cmd],
        capture_output=True, text=True, timeout=30,
    )
    pids = [int(x) for x in result.stdout.split() if x.strip().isdigit()]
    if not pids:
        print('[kill] 没有运行中的服务器')
        return
    for pid in pids:
        print(f'[kill] Stop-Process -Id {pid} -Force')
        subprocess.run(
            ['powershell', '-NoProfile', '-Command', f'Stop-Process -Id {pid} -Force'],
            capture_output=True, timeout=30,
        )
    time.sleep(2)
    print(f'[kill] 已终止 {len(pids)} 个进程')


def wait_port_open(timeout=90):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with socket.create_connection(('127.0.0.1', PORT), timeout=2):
                return True
        except OSError:
            time.sleep(2)
    return False


def replace_jar(src, dst_dir):
    base = os.path.basename(src)
    dst = os.path.join(dst_dir, base)
    if not os.path.exists(dst_dir):
        raise FileNotFoundError(dst_dir)
    # 删除任何 0.4.16/0.4.17 jar,避免重复
    for fname in os.listdir(dst_dir):
        if fname.startswith('qisheng_chess-fabric-0.4.17') or fname.startswith('qisheng_chess-fabric-0.4.16'):
            os.remove(os.path.join(dst_dir, fname))
            print(f'[clean] {dst_dir}/{fname}')
    shutil.copy2(src, dst)
    h = sha256(dst)
    print(f'[copy] -> {dst}')
    print(f'[sha ] {h}')
    return h


def start_server():
    print('[start] launching fabric server...')
    args = [
        JAVA,
        '-Xmx2G', '-Xms1G',
        '-jar', 'fabric-server-launch.jar', 'nogui',
    ]
    DETACHED = 0x00000008
    NEW_PG = 0x00000200
    creationflags = DETACHED | NEW_PG
    subprocess.Popen(
        args,
        cwd=SERVER_WORKDIR,
        creationflags=creationflags,
        stdin=subprocess.DEVNULL,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )


def main():
    if not os.path.exists(SOURCE_JAR):
        raise FileNotFoundError(SOURCE_JAR)
    expected_sha = 'D3DF666727786EA298C8FC97D2677B95D5D2E6046D4E4B09B08EF6E8F45DB196'
    actual_sha = sha256(SOURCE_JAR)
    if actual_sha.lower() != expected_sha.lower():
        raise RuntimeError(f'SHA mismatch! expected {expected_sha}, got {actual_sha}')

    kill_server()
    srv_sha = replace_jar(SOURCE_JAR, SERVER_DIR)
    cli_sha = replace_jar(SOURCE_JAR, CLIENT_DIR)
    assert srv_sha.lower() == cli_sha.lower() == expected_sha.lower()

    start_server()
    print('[wait] 等 90s 服务器端口开放...')
    if wait_port_open(90):
        print(f'[ok] port {PORT} OPEN')
    else:
        print('[FAIL] 端口未开放')
        return 1
    return 0


if __name__ == '__main__':
    import sys
    sys.exit(main())