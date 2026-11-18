# -*- coding: utf-8 -*-
"""全进程诊断：模块加载状态 + 各桥日志 + 崩溃检查。"""

import re
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


print("=" * 78)
print("设备:", dev)
print("时间:", sh("date '+%H:%M:%S'"))
print("模块安装:", sh("dumpsys package com.mo.ugreenprotocolhook | grep lastUpdateTime").strip())
print("=" * 78)

print()
print("【1】相关进程")
print(sh("ps -A -o PID,NAME | grep -E 'mydevices|melody|ugreen|bluetooth'"))

print()
print("【2】各桥日志文件")
PATHS = {
    "设备空间": "/data/data/com.heytap.mydevices/files/ugreen_mydevices_hook.log",
    "无线耳机": "/data/data/com.oplus.melody/files/ugreen_melody_hook.log",
    "系统桥": "/data/misc/bluetooth/logs/ugreen_system_bridge.log",
    "协议桥": "/sdcard/Download/ugreen_protocol.log",
}
for name, p in PATHS.items():
    print("  %-10s %s" % (name, sh("ls -l %s 2>&1" % p)[:100]))

print()
print("【3】设备空间 Hook 日志（关键）")
print(sh("cat /data/data/com.heytap.mydevices/files/ugreen_mydevices_hook.log "
         "2>/dev/null || echo '(不存在)'"))

print()
print("【4】LSPosed 是否注入 mydevices / melody")
out = sh("cat /data/adb/lspd/log/*.log 2>/dev/null | grep -iE 'mydevices|melody' | grep -i ugreen | tail -12")
print(out if out else "(无)")

print()
print("【5】测试 APP 是否还闪退")
out = sh("logcat -d 2>/dev/null | grep -B2 -A12 'FATAL EXCEPTION' | tail -40")
print(out if out else "(无崩溃)")

print()
print("【6】测试 APP 界面日志（最近）")
out = sh("logcat -d 2>/dev/null | grep -i 'UGREEN' | tail -20")
print(out if out else "(无)")
