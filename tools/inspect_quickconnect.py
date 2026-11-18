# -*- coding: utf-8 -*-
"""查设备快连 DialogActivity 的触发条件与期望数据。"""

import os
import re
import sys
import zipfile

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

APK = r"D:\AndroidStudio\UGREENforColorOS\交流\设备快连_17.6.15.apk"
z = zipfile.ZipFile(APK)

TARGET = b"com/oplus/pantaconnect/appcore/devicediscovery/ui/DialogActivity"

# 找到含该类的 dex
data = None
name = None
for n in [x for x in z.namelist() if x.endswith(".dex")]:
    d = z.read(n)
    if TARGET in d:
        data = d
        name = n
        break

if data is None:
    print("未找到类")
    sys.exit(1)

print("类在:", name, " dex 大小:", len(data))
print()

# 1) 找类描述符位置，并 dump 附近的可读串
idx = data.find(TARGET)
print("=" * 76)
print("类描述符附近的可读字符串（含 action / extra 键名）")
print("=" * 76)
s = max(0, idx - 3000)
e = min(len(data), idx + 3000)
chunk = data[s:e]
seen = set()
for m in re.finditer(rb"[\x20-\x7e]{5,90}", chunk):
    t = m.group(0).decode("ascii", "ignore")
    if t in seen:
        continue
    seen.add(t)
    print("   ", t)
