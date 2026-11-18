# -*- coding: utf-8 -*-
"""确认测试 APP 是否还装着 + DialogClientImpl 的真实全名。"""

import re
import subprocess
import sys
import zipfile

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


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


print("=" * 76)
print("1. 两个 APP 是否还装着")
print("=" * 76)
for pkg in ["com.mo.ugreenforcoloros", "com.ugreen.iot"]:
    r = sh("pm path %s 2>/dev/null | head -1" % pkg)
    print("  %-28s %s" % (pkg, r if r else "❌ 未安装"))

print()
print("=" * 76)
print("2. DialogClientImpl 的真实全名（修正正则）")
print("=" * 76)

APK = r"D:\AndroidStudio\UGREENforColorOS\交流\设备快连_17.6.15.apk"
z = zipfile.ZipFile(APK)

# 关键修正：内类描述符是 DialogClientImpl$xxx，所以不能要求以 ; 结尾
pat = re.compile(rb"L(com/oplus/pantaconnect/[A-Za-z0-9_/$]*DialogClient[A-Za-z]*)")
seen = set()
for n in [x for x in z.namelist() if x.endswith(".dex")]:
    data = z.read(n)
    for m in pat.finditer(data):
        c = m.group(1).decode()
        top = c.split("$")[0]
        if top not in seen:
            seen.add(top)
            print("  ✅", top.replace("/", "."), "  (在", n + ")")

if not seen:
    print("  ❌ 仍未找到")

print()
print("=" * 76)
print("3. 顺带看 controller 包下的类名")
print("=" * 76)
pat2 = re.compile(rb"Lcom/oplus/pantaconnect/appcore/devicediscovery/controller/([A-Za-z0-9_$]+)")
found = set()
for n in [x for x in z.namelist() if x.endswith(".dex")]:
    data = z.read(n)
    for m in pat2.finditer(data):
        top = m.group(1).decode().split("$")[0]
        found.add(top)
for c in sorted(found)[:30]:
    print("   ", c)
