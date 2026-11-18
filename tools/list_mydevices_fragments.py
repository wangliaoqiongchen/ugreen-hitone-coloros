# -*- coding: utf-8 -*-
"""列出设备空间 APK 里 bluetooth.fragment 包的全部类，并提取 dex。"""

import os
import re
import sys
import zipfile

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

APK = r"D:\AndroidStudio\UGREENforColorOS\交流\设备空间_17.25.10.apk"
OUT = r"D:\AndroidStudio\UGREENforColorOS\tools\report\mydevicesdex"

os.makedirs(OUT, exist_ok=True)
z = zipfile.ZipFile(APK)

PREFIX = "Lcom/heytap/mydevices/plugin/bluetooth/fragment/"

found = {}
for d in [n for n in z.namelist() if n.endswith(".dex")]:
    data = z.read(d)
    for m in re.finditer(re.escape(PREFIX.encode()) + rb"([A-Za-z0-9_$]+);", data):
        cn = m.group(1).decode()
        found.setdefault(cn, set()).add(d)

print("=== com.heytap.mydevices.plugin.bluetooth.fragment 下的类 ===")
for cn in sorted(found):
    print("   %-52s %s" % (cn, ",".join(sorted(found[cn]))))
print()
print("总数:", len(found))

# 提取含 BtDetailPageFragment 的 dex
print()
print("=== 提取 dex（供反编译）===")
for d in [n for n in z.namelist() if n.endswith(".dex")]:
    data = z.read(d)
    if b"BtDetailPageFragment" in data:
        out = os.path.join(OUT, d)
        open(out, "wb").write(data)
        print("   %s -> %d bytes" % (d, len(data)))

# 找 pref_bt_audio_settings 附近上下文
print()
print("=== pref_bt_audio_settings 上下文 ===")
for d in [n for n in z.namelist() if n.endswith(".dex")]:
    data = z.read(d)
    for m in re.finditer(b"pref_bt_audio_settings", data):
        off = m.start()
        s = max(0, off - 150)
        e = min(len(data), off + 150)
        ctx = re.sub(rb"[^\x20-\x7e]+", b".", data[s:e]).decode("ascii", "ignore")
        print("   [%s @%d] %s" % (d, off, ctx))
