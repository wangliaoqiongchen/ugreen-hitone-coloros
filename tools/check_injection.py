# -*- coding: utf-8 -*-
"""判定模块是否真的注入了蓝牙进程，并定位原因。"""

import subprocess
import sys
import re

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

if not dev:
    print("无设备")
    sys.exit(1)


def sh(c, t=90):
    return subprocess.run(["adb", "-s", dev, "exec-out", "su", "-c", c],
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          timeout=t).stdout.decode("utf-8", "replace")


# 取蓝牙进程 PID
pid_out = sh("ps -A -o PID,NAME | grep com.android.bluetooth | head -1").strip()
print("蓝牙进程行:", pid_out)

m = re.match(r"\s*(\d+)", pid_out)
if not m:
    print("!! 取不到蓝牙 PID")
    sys.exit(1)

pid = m.group(1)
print("蓝牙 PID:", pid)
print()

print("=== 模块 APK 是否被加载进蓝牙进程（maps） ===")
maps = sh("grep -i ugreenprotocolhook /proc/%s/maps 2>/dev/null | head -5" % pid)
print(maps.strip() if maps.strip() else "  ❌ 没有任何映射 —— 模块没有被注入")

print()
print("=== 对比：模块是否被注入官方 APP 进程 ===")
pid2 = sh("ps -A -o PID,NAME | grep com.ugreen.iot | head -1").strip()
m2 = re.match(r"\s*(\d+)", pid2)
if m2:
    p2 = m2.group(1)
    maps2 = sh("grep -i ugreenprotocolhook /proc/%s/maps 2>/dev/null | head -3" % p2)
    print("  官方 APP PID:", p2)
    print("  " + (maps2.strip() if maps2.strip() else "❌ 也没被注入"))

print()
print("=== 蓝牙进程 UID ===")
print(sh("stat -c '%%u %%U' /proc/%s" % pid))

print()
print("=== 日志目录可写性（蓝牙进程视角）===")
print(sh("ls -ld /sdcard/Download"))
print(sh("ls -l /sdcard/Download/ugreen_system_bridge.log"))

print()
print("=== LSPosed 日志里搜我们的包名 ===")
out = sh("cat /data/adb/lspd/log/*.log 2>/dev/null | grep -i ugreenprotocolhook | tail -10")
print(out.strip() if out.strip() else "  (LSPosed 日志里完全没有我们的模块)")

print()
print("=== LSPosed 日志里搜 bluetooth（看有没有尝试注入） ===")
out = sh("cat /data/adb/lspd/log/*.log 2>/dev/null | grep -i 'bluetooth' | tail -15")
print(out.strip() if out.strip() else "  (无)")

print()
print("=== LSPosed monitor 文件（记录被监控的进程）===")
print(sh("cat /data/adb/lspd/monitor 2>/dev/null"))
