# -*- coding: utf-8 -*-
"""读取「无线耳机」App 的 Hook 日志与相关状态。"""

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

if not dev:
    print("无设备")
    sys.exit(1)


def sh(c, t=180):
    return subprocess.run(["adb", "-s", dev, "exec-out", "su", "-c", c],
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          timeout=t).stdout.decode("utf-8", "replace").strip()


print("设备:", dev)
print("时间:", sh("date '+%H:%M:%S'"))
print("模块安装:", sh("dumpsys package com.mo.ugreenprotocolhook | grep lastUpdateTime").strip())
print()

LOG = "/data/data/com.oplus.melody/files/ugreen_melody_hook.log"

print("=" * 74)
print("1. melody Hook 日志是否存在")
print("=" * 74)
print(sh("ls -l %s 2>&1" % LOG))
print()

print("=" * 74)
print("2. 日志内容")
print("=" * 74)
print(sh("cat %s 2>/dev/null || echo '(不存在)'" % LOG))
print()

print("=" * 74)
print("3. logcat 里的 UGREEN-MELODY")
print("=" * 74)
out = sh("logcat -d 2>/dev/null | grep -i 'UGREEN-MELODY' | tail -40")
print(out if out else "(无)")
print()

print("=" * 74)
print("4. LSPosed 是否把模块注入 melody")
print("=" * 74)
out = sh("cat /data/adb/lspd/log/*.log 2>/dev/null | grep -i 'melody' | tail -10")
print(out if out else "(无)")
print()

print("=" * 74)
print("5. melody 进程")
print("=" * 74)
print(sh("ps -A -o PID,NAME | grep -i melody"))
