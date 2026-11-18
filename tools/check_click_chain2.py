# -*- coding: utf-8 -*-
"""读两个入口的点击链路日志。"""

import subprocess
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

DEV = "192.168.0.101:35727"


def sh(c, t=180):
    return subprocess.run(["adb", "-s", DEV, "exec-out", "su", "-c", c],
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          timeout=t).stdout.decode("utf-8", "replace").strip()


MEL = "/data/data/com.oplus.melody/files/ugreen_melody_hook.log"
MYD = "/data/data/com.heytap.mydevices/files/ugreen_mydevices_hook.log"

print("时间:", sh("date '+%H:%M:%S'"))
print("模块安装:", sh("dumpsys package com.mo.ugreenprotocolhook | grep lastUpdateTime").strip())
print()

print("=" * 76)
print("1. 音量面板：melody 是否收到 call 请求")
print("=" * 76)
print("有 [call] 的行数:", sh("grep -c '\\[call\\]' %s 2>/dev/null || echo 0" % MEL))
print()
print(sh("grep '\\[call\\]' %s 2>/dev/null | tail -12" % MEL))
print()
print("--- 降噪切换记录 ---")
print(sh("grep '切换降噪' %s 2>/dev/null | tail -8 || echo '(无)'" % MEL))

print()
print("=" * 76)
print("2. 音量面板：我们发的广播")
print("=" * 76)
print(sh("grep '降噪广播' %s 2>/dev/null | tail -8 || echo '(无)'" % MEL))

print()
print("=" * 76)
print("3. 设备空间：点击记录")
print("=" * 76)
print(sh("grep '用户点击\\|发送降噪广播\\|命中我们的' %s 2>/dev/null | tail -12 || echo '(无)'" % MYD))

print()
print("=" * 76)
print("4. 测试 APP 是否收到广播并执行")
print("=" * 76)
out = sh("logcat -d 2>/dev/null | grep -iE '系统设备空间|已响应系统|BLE 未就绪|注册系统降噪' | tail -15")
print(out if out else "(无)")

print()
print("=" * 76)
print("5. 协议桥：T6 命令是否真的发出")
print("=" * 76)
out = sh("logcat -d 2>/dev/null | grep -iE 'AA BB CC 09 01' | tail -10")
print(out if out else "(无)")

print()
print("=" * 76)
print("6. melody 日志最后 25 行")
print("=" * 76)
print(sh("tail -25 %s 2>/dev/null" % MEL))
