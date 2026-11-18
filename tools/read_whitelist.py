# -*- coding: utf-8 -*-
"""读取「无线耳机」App 的白名单文件内容。"""

import subprocess
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

D = subprocess.run(["adb", "devices"], stdout=subprocess.PIPE).stdout.decode()
dev = None
for line in D.splitlines()[1:]:
    p = line.split()
    if len(p) >= 2 and p[1] == "device":
        dev = p[0]
        break


def sh(c, t=180):
    return subprocess.run(["adb", "-s", dev, "exec-out", "su", "-c", c],
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          timeout=t).stdout.decode("utf-8", "replace").strip()


W = "/data/data/com.oplus.melody/files/melody-model-whitelist"

print("=" * 78)
print("outside_snapshot.json（明文 JSON）")
print("=" * 78)
print(sh("cat %s/outside_snapshot.json" % W))

print()
print("=" * 78)
print("encrypted.gz 的文件头（判断加密方式）")
print("=" * 78)
print(sh("head -c 64 %s/encrypted.gz | xxd | head -6" % W))
