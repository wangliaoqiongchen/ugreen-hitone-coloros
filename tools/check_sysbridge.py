# -*- coding: utf-8 -*-
"""检查系统桥状态（交接书待办 1）。只读设备。"""

import subprocess
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


def pick_device():
    out = subprocess.run(["adb", "devices"], stdout=subprocess.PIPE).stdout.decode()
    for line in out.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            return parts[0]
    return None


D = pick_device()
if not D:
    print("没有可用设备")
    sys.exit(1)

print("设备:", D)
print()


def sh(c, t=90):
    try:
        return subprocess.run(["adb", "-s", D, "exec-out", "su", "-c", c],
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                              timeout=t).stdout.decode("utf-8", "replace")
    except Exception as e:
        return "ERR " + str(e)


print("=== 当前时间 ===")
print(sh("date '+%Y-%m-%d %H:%M:%S'").strip())
print()

print("=== 模块安装时间（确认是否新版本） ===")
print(sh("dumpsys package com.mo.ugreenprotocolhook | grep -E 'lastUpdateTime|versionName'").strip())
print()

print("=== 系统桥日志（三个候选） ===")
for p in ["/sdcard/Download/ugreen_system_bridge.log",
          "/sdcard/Android/data/com.android.bluetooth/files/ugreen_system_bridge.log",
          "/data/local/tmp/ugreen_system_bridge.log"]:
    out = sh("if [ -f '%s' ]; then ls -l '%s'; else echo MISSING; fi" % (p, p)).strip()
    print("  %-64s %s" % (p, out))
print()

print("=== 系统桥日志内容（任一存在的） ===")
print(sh("cat /sdcard/Download/ugreen_system_bridge.log 2>/dev/null "
         "|| cat /sdcard/Android/data/com.android.bluetooth/files/ugreen_system_bridge.log 2>/dev/null "
         "|| cat /data/local/tmp/ugreen_system_bridge.log 2>/dev/null "
         "|| echo '(三个都不存在)'"))
print()

print("=== 电量桥文件 ===")
for p in ["/sdcard/Download/ugreen_battery.txt",
          "/sdcard/Android/data/com.mo.ugreenforcoloros/files/ugreen_battery.txt",
          "/data/local/tmp/ugreen_battery.txt"]:
    out = sh("if [ -f '%s' ]; then echo '--- %s ---'; cat '%s'; else echo MISSING %s; fi"
             % (p, p, p, p)).strip()
    print(out)
print()

print("=== 蓝牙进程是否存在 ===")
print(sh("ps -A -o PID,NAME | grep -i bluetooth").strip())
