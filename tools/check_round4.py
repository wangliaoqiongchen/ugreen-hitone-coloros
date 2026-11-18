# -*- coding: utf-8 -*-
"""重启后的完整验证：三件事 + 服务状态。"""

import subprocess
import sys

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
if not DEV:
    print("无设备")
    sys.exit(1)
print("设备:", DEV)


def sh(c, t=180):
    return subprocess.run(["adb", "-s", DEV, "exec-out", "su", "-c", c],
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          timeout=t).stdout.decode("utf-8", "replace").strip()


ACC = "/data/data/com.heytap.accessory/files/ugreen_accessory_hook.log"
MYD = "/data/data/com.heytap.mydevices/files/ugreen_mydevices_hook.log"
MEL = "/data/data/com.oplus.melody/files/ugreen_melody_hook.log"

print("时间:", sh("date '+%H:%M:%S'"))
print("模块安装:", sh("dumpsys package com.mo.ugreenprotocolhook | grep lastUpdateTime").strip())
print("开机时长:", sh("cat /proc/uptime | awk '{print $1\" 秒\"}'"))
print()

print("=" * 76)
print("【A】设备空间：摘要刷新")
print("=" * 76)
print(sh("grep -E '刷新|列表项' %s 2>/dev/null | tail -10 || echo '(无刷新记录)'" % MYD))

print()
print("=" * 76)
print("【B】BLE 直连延迟")
print("=" * 76)
print(sh("logcat -d 2>/dev/null | grep 'UGREEN-BLE' | tail -40 || echo '(无 UGREEN-BLE 记录)'"))

print()
print("=" * 76)
print("【C】设备快连：DialogClient 是否被调用")
print("=" * 76)
print(sh("grep -E 'DialogClient|弹窗出现' %s 2>/dev/null | tail -25 || echo '(无)'" % ACC))

print()
print("=" * 76)
print("【D】后台服务是否自己起来了")
print("=" * 76)
out = sh("logcat -d 2>/dev/null | grep -E 'UGREEN-T6-SERVICE|UGREEN-BOOT' | tail -15")
print(out if out else "(无服务日志 —— 服务没启动)")

print()
print("=" * 76)
print("【E】系统降噪请求 → 走的哪条路")
print("=" * 76)
out = sh("logcat -d 2>/dev/null | grep -E '模块直连|回退' | tail -12")
print(out if out else "(无)")
