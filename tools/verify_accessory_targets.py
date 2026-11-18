# -*- coding: utf-8 -*-
"""验证「设备快连」（com.heytap.accessory）的 Hook 目标是否存在。"""

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
dexes = [n for n in z.namelist() if n.endswith(".dex")]
print("APK:", os.path.basename(APK), os.path.getsize(APK))
print("dex:", dexes)
print()

# 华为 to.java 用到的关键点
TARGETS = [
    b"com/oplus/pantaconnect/appcore/devicediscovery/ui/DialogActivity",
    b"DialogActivity",
    b"base_view_data",
    b"intent_bundle",
    b"BaseViewData",
    b"pantaconnect",
    b"getPluginViewData",
    b"getDialogTitle",
    b"getPackageName",
    b"COLOROS_POPUP",
    b"dispatchTouchEvent",
    b"onCreate",
]

for t in TARGETS:
    hits = []
    for d in dexes:
        data = z.read(d)
        n = data.count(t)
        if n:
            hits.append("%s x%d" % (d, n))
    mark = "✅" if hits else "❌"
    print("  %s %-62s %s" % (mark, t.decode(), ", ".join(hits) if hits else "无"))

# 列出 pantaconnect 下的类
print()
print("=== com.oplus.pantaconnect 下的类（前 40）===")
found = set()
for d in dexes:
    data = z.read(d)
    for m in re.finditer(rb"Lcom/oplus/pantaconnect/[A-Za-z0-9_/$]+;", data):
        found.add(m.group(0).decode()[1:-1].replace("/", "."))
for c in sorted(found)[:40]:
    print("   ", c)
print("总数:", len(found))
