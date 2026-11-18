# -*- coding: utf-8 -*-
"""核实 DialogClientImpl 类名 + 查服务未自启的原因。"""

import os
import re
import subprocess
import sys
import zipfile

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

# ---------- 1. 在 APK 里核实类名 ----------
APK = r"D:\AndroidStudio\UGREENforColorOS\交流\设备快连_17.6.15.apk"
z = zipfile.ZipFile(APK)

print("=" * 76)
print("1. 核实 DialogClientImpl 的真实类名")
print("=" * 76)

# 精确找 "L...DialogClientImpl;"
pat = re.compile(rb"Lcom/oplus/pantaconnect/[A-Za-z0-9_/$]*DialogClientImpl;")
found = set()
for n in [x for x in z.namelist() if x.endswith(".dex")]:
    data = z.read(n)
    for m in pat.finditer(data):
        found.add((n, m.group(0).decode()[1:-1].replace("/", ".")))

if found:
    for n, c in sorted(found):
        print("   ✅", c, "  (在", n + ")")
else:
    print("   ❌ 没找到精确的 DialogClientImpl 类")
    print("   放宽搜索（含 DialogClient 的类）:")
    pat2 = re.compile(rb"Lcom/oplus/pantaconnect/[A-Za-z0-9_/$]*Dialog[A-Za-z]*;")
    seen = set()
    for n in [x for x in z.namelist() if x.endswith(".dex")]:
        data = z.read(n)
        for m in pat2.finditer(data):
            c = m.group(0).decode()[1:-1].replace("/", ".")
            if "Impl" in c and c not in seen:
                seen.add(c)
                print("      ", c)
            if len(seen) > 25:
                break
        if len(seen) > 25:
            break

# ---------- 2. 查 APP 服务状态 ----------
print()
print("=" * 76)
print("2. 测试 APP 的服务为什么没自启")
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


print("  APP 是否在运行:", sh("ps -A | grep ugreenforcoloros | head -3") or "(未运行)")

print()
print("  通知权限（前台服务必须）：")
print(sh("dumpsys package com.mo.ugreenforcoloros | grep -A2 POST_NOTIFICATIONS | head -6"))

print()
print("  是否处于 stopped 状态（stopped 的应用收不到 BOOT_COMPLETED）：")
print(sh("dumpsys package com.mo.ugreenforcoloros | grep -E 'stopped=|notLaunched=|enabled=' | head -5"))

print()
print("  该应用的后台限制：")
print(sh("dumpsys deviceidle whitelist 2>/dev/null | grep ugreen || echo '(不在白名单)'"))

print()
print("  logcat 里开机后的服务/广播线索：")
print(sh("logcat -d 2>/dev/null | grep -iE 'BOOT_COMPLETED|ugreenforcoloros' | tail -12 || echo '(无)'"))
