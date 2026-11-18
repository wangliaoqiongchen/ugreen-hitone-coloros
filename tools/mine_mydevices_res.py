# -*- coding: utf-8 -*-
"""
从 MyDevices.apk 里挖出所有 earphone_* 相关的字符串资源名。

目的：确认 ColorOS "设备空间"里耳机降噪 UI 到底有哪些文案/档位，
      从而反推它期望的数据结构。
"""

import os
import re
import sys
import zipfile

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

APK = r"D:\AndroidStudio\UGREENforColorOS\tools\report\MyDevices.apk"

z = zipfile.ZipFile(APK)

# 1) 从 dex 的字符串表里找资源名模式
pat = re.compile(rb"[a-z][a-z0-9_]{3,60}")
prefixes = (b"earphone", b"headset", b"anc", b"noise",
            b"buds", b"tws", b"transparen")

found = set()
for name in z.namelist():
    if not name.endswith(".dex"):
        continue
    data = z.read(name)
    for m in pat.finditer(data):
        w = m.group(0)
        if w.startswith(prefixes) and len(w) > 6:
            found.add(w.decode("ascii", "ignore"))

print("=== earphone/headset/anc/noise 相关标识符（去重）===")
for w in sorted(found):
    print("   ", w)
print()
print("总数:", len(found))
