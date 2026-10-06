#!/usr/bin/env python3
"""Look for any class file matching ServerPlayer substring in all jars."""
import os, sys, io, zipfile
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

paths = [
    r'D:\PCL 正式版 2.12.6\89\.minecraft\versions\1.20.1-Fabric 0.19.5',
    r'C:\Users\wdsj2\.gradle\caches\modules-2',
    r'D:\mc-server\1.20.1-fabric-0.19.5',
    r'D:\PCL 正式版 2.12.6\89\.minecraft',
]
search_terms = ['ServerPlayer', 'class_3222', 'MinecraftServer', 'ServerLevel', 'ServerGamePacketListenerImpl', 'serverPlayer']

for base in paths:
    if not os.path.exists(base):
        continue
    print(f"=== {base} ===")
    for root, dirs, files in os.walk(base):
        for f in files:
            if f.endswith('.jar'):
                p = os.path.join(root, f)
                try:
                    z = zipfile.ZipFile(p)
                    for term in search_terms:
                        cls = [n for n in z.namelist() if term in n and n.endswith('.class') and '$' not in n]
                        if cls:
                            print(f"  {p[:120]}  ({term})")
                            for n in cls[:2]:
                                print(f"    {n}")
                            break
                except Exception:
                    pass