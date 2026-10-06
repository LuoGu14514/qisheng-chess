#!/usr/bin/env python3
"""Check MinecraftServer class env tag."""
import zipfile, io, sys
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

paths = [
    (r'D:\PCL 正式版 2.12.6\89\.minecraft\versions\1.20.1-Fabric 0.19.5\.fabric\remappedJars\minecraft-1.20.1-0.19.5\client-intermediary.jar', 'CLIENT'),
    (r'D:\mc-server\1.20.1-fabric-0.19.5\.fabric\remappedJars\minecraft-1.20.1-0.19.5\server-intermediary.jar', 'SERVER'),
]
for p, label in paths:
    z = zipfile.ZipFile(p)
    for name in ['net/minecraft/server/MinecraftServer.class', 'net/minecraft/server/level/ServerPlayer.class']:
        if name in z.namelist():
            data = z.read(name)
            print(f'[{label}] {name} size={len(data)} hasEnv={("Environment" in str(data) or "EnvType" in str(data))}')
        else:
            print(f'[{label}] MISSING: {name}')