#!/usr/bin/env python3
"""Find ServerPlayer in mc jars."""
import os, sys, io, zipfile
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

paths = [
    r'D:\PCL 正式版 2.12.6\89\.minecraft\versions\1.20.1-Fabric 0.19.5',
    r'C:\Users\wdsj2\.gradle\caches\modules-2',
    r'D:\mc-server\1.20.1-fabric-0.19.5',
]
for base in paths:
    if not os.path.exists(base):
        continue
    for root, dirs, files in os.walk(base):
        for f in files:
            if (f.startswith('minecraft-') or f.startswith('client-') or f.startswith('server-')) and f.endswith('.jar'):
                p = os.path.join(root, f)
                try:
                    z = zipfile.ZipFile(p)
                    cls = [n for n in z.namelist() if ('ServerPlayer' in n or 'ServerLevel' in n or 'MinecraftServer.class' in n) and '$' not in n]
                    if cls:
                        print(p)
                        for n in cls[:5]:
                            print('  ', n)
                except Exception:
                    pass