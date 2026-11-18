# -*- coding: utf-8 -*-
"""读本轮三个关注点的日志：设备快连 / 设备空间摘要 / BLE 延迟。"""

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
print()

print("=" * 76)
print("1. 设备快连 Hook 是否加载")
print("=" * 76)
print(sh("ls -l %s 2>&1" % ACC))
print()
print(sh("cat %s 2>/dev/null || echo '(日志不存在)'" % ACC))

print()
print("=" * 76)
print("2. LSPosed 是否注入 accessory")
print("=" * 76)
out = sh("cat /data/adb/lspd/log/*.log 2>/dev/null | grep -i 'accessory' | tail -10")
print(out if out else "(无)")

print()
print("=" * 76)
print("3. 设备空间：摘要刷新记录")
print("=" * 76)
print(sh("grep -E '刷新|状态同步|列表项' %s 2>/dev/null | tail -15 || echo '(无)'" % MYD))

print()
print("=" * 76)
print("4. BLE 延迟（模块直连耗时）")
print("=" * 76)
print(sh("logcat -d 2>/dev/null | grep 'UGREEN-BLE' | tail -30 || echo '(无)'"))

print()
print("=" * 76)
print("5. 模块直连成功/回退统计")
print("=" * 76)
out = sh("logcat -d 2>/dev/null | grep -E '模块直连' | tail -12")
print(out if out else "(无)")

print()
print("=" * 76)
print("6. 相关进程")
print("=" * 76)
print(sh("ps -A -o PID,NAME | grep -E 'accessory|mydevices|melody|ugreen'"))
