#!/usr/bin/env python3
"""Check inner classes of ServerPlayNetworking for env tags."""
import zipfile, io, sys
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

jar = r'C:\Users\wdsj2\.gradle\caches\modules-2\files-2.1\net.fabricmc.fabric-api\fabric-networking-api-v1\1.3.11+1802ada577\6703d1c46407150165d1a1829182f9f3607d76ca\fabric-networking-api-v1-1.3.11+1802ada577.jar'
z = zipfile.ZipFile(jar)

candidates = [
    'net/fabricmc/fabric/api/networking/v1/ServerPlayNetworking$PlayChannelHandler.class',
    'net/fabricmc/fabric/api/networking/v1/ServerPlayNetworking$PlayChannelHandlerProxy.class',
    'net/fabricmc/fabric/api/networking/v1/ServerPlayNetworking$PlayPacketHandler.class',
    'net/fabricmc/fabric/api/networking/v1/PacketSender.class',
]
for cls in candidates:
    if cls in z.namelist():
        data = z.read(cls)
        has_env = b'Environment' in data or b'EnvType' in data
        print(f'{cls} size={len(data)} hasEnv={has_env}')
    else:
        print(f'MISSING: {cls}')