# -*- coding: utf-8 -*-
"""验证状态同步链路：APP 广播 → 两个钩子进程接收。"""

import subprocess
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


def pick():
    out = subprocess.run(["adb", "devices"], stdout=subprocess.PIPE).stdout.decode()
    cands = []
    for line in out.splitlines()[1:]:
        p = line.split()
        if len(p) >= 2 and p[1] == "device":
            cands.append(p[0])
    # 优先无线（IP 形式）
    for c in cands:
        if ":" in c:
            return c
    return cands[0] if cands else None


DEV = pick()
if not DEV:
    print("无设备")
    sys.exit(1)

print("设备:", DEV)


def sh(c, t=180):
    return subprocess.run(["adb", "-s", DEV, "exec-out", "su", "-c", c],
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          timeout=t).stdout.decode("utf-8", "replace").strip()


MEL = "/data/data/com.oplus.melody/files/ugreen_melody_hook.log"
MYD = "/data/data/com.heytap.mydevices/files/ugreen_mydevices_hook.log"

print("时间:", sh("date '+%H:%M:%S'"))
print("模块安装:", sh("dumpsys package com.mo.ugreenprotocolhook | grep lastUpdateTime").strip())
print("APP 安装:", sh("dumpsys package com.mo.ugreenforcoloros | grep lastUpdateTime").strip())
print()

print("=" * 74)
print("1. 无线耳机：状态同步广播接收")
print("=" * 74)
print(sh("grep '状态同步' %s 2>/dev/null | tail -12 || echo '(无)'" % MEL))
print()
print("--- 最近一次切换 + 通知 ---")
print(sh("grep -E '音量面板切换|已发送降噪广播|已通知降噪状态变更' %s 2>/dev/null | tail -8 || echo '(无)'" % MEL))

print()
print("=" * 74)
print("2. 设备空间：状态同步广播接收")
print("=" * 74)
print(sh("grep '状态同步' %s 2>/dev/null | tail -12 || echo '(无)'" % MYD))
print()
print("--- 设备空间的点击与广播 ---")
print(sh("grep -E '用户点击|已发送降噪广播' %s 2>/dev/null | tail -8 || echo '(无)'" % MYD))

print()
print("=" * 74)
print("3. 测试 APP：广播发送 + 自动重连")
print("=" * 74)
out = sh("logcat -d 2>/dev/null | grep -iE 'ACTION_ANC_CHANGED|状态 mode=|自动重连|已响应系统降噪|检测到上次' | tail -15")
print(out if out else "(无)")

print()
print("=" * 74)
print("4. 注册状态同步接收器")
print("=" * 74)
print(sh("grep '状态同步接收器' %s %s 2>/dev/null || echo '(无)'" % (MEL, MYD)))
