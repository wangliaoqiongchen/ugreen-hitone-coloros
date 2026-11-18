# -*- coding: utf-8 -*-
"""模块结构审计（修复引号问题版）。"""

import os
import re
import sys
import collections

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

BASE = r"D:\AndroidStudio\UGREENforColorOS\protocolhook\src\main\java\com\mo\ugreenprotocolhook"

FILES = [
    "HookEntry.java",
    "ColorOsBatteryBridge.java",
    "SystemBridgeLog.java",
    "ProtocolLog.java",
    "MainActivity.java",
]

problems = 0

print("=== 1. 括号平衡 + 字段重复 ===")
for f in FILES:
    src = open(os.path.join(BASE, f), encoding="utf-8").read()
    nc = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    nc = re.sub(r"//[^\n]*", "", nc)

    b = nc.count("{") - nc.count("}")
    p = nc.count("(") - nc.count(")")

    pat = re.compile(
        r"^    (?:private|public|protected|static|final|volatile)[\w\s]*?\s"
        r"([A-Za-z_]\w*)\s*(?:=|;)", re.M)
    dup = {n: c for n, c in collections.Counter(pat.findall(nc)).items() if c > 1}

    bad = (b != 0) or (p != 0) or bool(dup)
    problems += 1 if bad else 0
    print("  %-28s {}=%-3d ()=%-3d dup=%-6s %s"
          % (f, b, p, str(dup) if dup else "none", ">>> 问题" if bad else "OK"))

print()
print("=== 2. SystemBridgeLog 里的路径 ===")
src = open(os.path.join(BASE, "SystemBridgeLog.java"), encoding="utf-8").read()
for m in re.finditer(r'"([^"]*)"', src):
    v = m.group(1)
    if v.startswith("/"):
        print("   ", v)

print()
print("=== 3. ClassLoader 使用点 ===")
for f in ["HookEntry.java", "ColorOsBatteryBridge.java"]:
    src = open(os.path.join(BASE, f), encoding="utf-8").read()
    nc = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    nc = re.sub(r"//[^\n]*", "", nc)
    for m in re.finditer(r".*(?:getClassLoader|lpparam\.classLoader).*", nc):
        line = nc[:m.start()].count("\n") + 1
        t = m.group(0).strip()
        print("  %-26s L%-4d %s" % (f, line, t[:88]))

print()
print("=== 4. Kotlin 习惯 / 非法转型 ===")
for f in FILES:
    src = open(os.path.join(BASE, f), encoding="utf-8").read()
    nc = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    nc = re.sub(r"//[^\n]*", "", nc)
    bad = []
    if re.search(r"\.text\s*=", nc):
        bad.append(".text=")
    if re.search(r"\(byte\)\s*\(Integer\)", nc):
        bad.append("(byte)(Integer)")
    for m in re.finditer(r"\b(?:int|long|byte|short)\s+\w+\s*=\s*\(Integer\)(?!\s*\()", nc):
        bad.append("缺 intValue(): " + m.group(0).strip())
    print("  %-28s %s" % (f, bad if bad else "OK"))

print()
print("=" * 62)
print("问题总数:", problems)
print("=" * 62)
