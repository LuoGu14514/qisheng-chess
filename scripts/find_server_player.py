#!/usr/bin/env python3
"""Search all jars under server folder for ServerPlayer class."""
import os
import sys
import zipfile

if sys.platform == "win32":
    sys.stdout = __import__("io").TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

base = r"D:\mc-server\1.20.1-fabric-0.19.5"
hits = []
for root, dirs, files in os.walk(base):
    for f in files:
        if f.endswith(".jar"):
            fp = os.path.join(root, f)
            try:
                z = zipfile.ZipFile(fp)
                for n in z.namelist():
                    if "server/level/ServerPlayer" in n and n.endswith(".class") and "$" not in n:
                        hits.append((fp, n))
            except Exception:
                pass

print(f"Found {len(hits)} matches:")
for fp, n in hits[:20]:
    print(f"  {n}")
    print(f"    {fp}")