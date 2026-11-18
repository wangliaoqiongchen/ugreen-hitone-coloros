# -*- coding: utf-8 -*-
"""挖出华为用的 ColorOS 弹窗宿主握手协议的确切 action 字符串。"""

import os
import re
import subprocess
import sys
import zipfile

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

APK = r"D:\AndroidStudio\UGREENforColorOS\交流\HuaweiPods-ColorOS-1.8.3-coloros.1.apk"
z = zipfile.ZipFile(APK)

print("=" * 76)
print("1. HuaweiPods 里的 POPUP_HOST / COLOROS 相关字符串")
print("=" * 76)

pats = [
    rb"[A-Za-z0-9_.]*POPUP_HOST[A-Za-z0-9_.]*",
    rb"[A-Za-z0-9_.]*COLOROS_POPUP[A-Za-z0-9_.]*",
    rb"[a-z][a-z0-9_.]*\.action\.[A-Za-z0-9_.]+",
    rb"com\.oplus\.[a-z0-9_.]*popup[A-Za-z0-9_.]*",
    rb"[A-Za-z0-9_.]*popup_host[A-Za-z0-9_.]*",
]

found = set()
for n in [x for x in z.namelist() if x.endswith(".dex")]:
    data = z.read(n)
    for p in pats:
        for m in re.finditer(p, data):
            s = m.group(0).decode("ascii", "ignore")
            if len(s) >= 8:
                found.add(s)

for s in sorted(found):
    print("   ", s)

if not found:
    print("   (无)")

print()
print("=" * 76)
print("2. 所有含 popup / host 的字符串（放宽）")
print("=" * 76)
found2 = set()
for n in [x for x in z.namelist() if x.endswith(".dex")]:
    data = z.read(n)
    for m in re.finditer(rb"[\x20-\x7e]{10,90}", data):
        s = m.group(0).decode("ascii", "ignore")
        low = s.lower()
        if ("popup" in low and "host" in low) or "popuphost" in low:
            found2.add(s)

for s in sorted(found2)[:40]:
    print("   ", s)
if not found2:
    print("   (无)")

print()
print("=" * 76)
print("3. 在设备上搜这套 action 是否被系统使用（蓝牙进程日志）")
print("=" * 76)


def pick():
    out = subprocess.run(["adb", "devices"], stdout=subprocess.PIPE).stdout.decode()
    c = [l.split()[0] for l in out.splitlines()[1:]
         if len(l.split()) >= 2 and l.split()[1] == "device"]
    for x in c:
        if ":" in x:
            return x
    return c[0] if c else None


DEV = pick()


def sh(c, t=150):
    return subprocess.run(["adb", "-s", DEV, "exec-out", "su", "-c", c],
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          timeout=t).stdout.decode("utf-8", "replace").strip()


# 在蓝牙 APK 和系统框架里搜这个 action
print("在 /system/app/Bluetooth/Bluetooth.apk 里搜 POPUP_HOST:")
print(sh("grep -c POPUP_HOST /system/app/Bluetooth/Bluetooth.apk 2>/dev/null || echo 0"))
print()
print("在 oplus_bluetooth_common_ext.jar 里搜:")
print(sh("grep -c POPUP_HOST /system_ext/framework/oplus_bluetooth_common_ext.jar 2>/dev/null || echo 0"))
print()
print("全系统 APK 搜 POPUP_HOST（可能较慢）:")
print(sh("for d in /system/app /system/priv-app /system_ext/app /system_ext/priv-app "
         "/product/app /product/priv-app /my_product/priv-app /my_stock/priv-app; do "
         "[ -d \"$d\" ] && grep -rl --include='*.apk' -e 'POPUP_HOST' \"$d\" 2>/dev/null; "
         "done | head -10"))
