# -*- coding: utf-8 -*-
"""
一键诊断：把当前所有关键状态打印出来。

用法（在本目录执行）：
    python tools\\diag_all.py

AI 会自己跑这个脚本，用户不需要手动复制任何日志。
"""

import os
import subprocess
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


def pick_device():
    out = subprocess.run(["adb", "devices"], stdout=subprocess.PIPE).stdout.decode()
    for line in out.splitlines()[1:]:
        p = line.split()
        if len(p) >= 2 and p[1] == "device":
            return p[0]
    return None


D = pick_device()
if not D:
    print("!! 没有可用 adb 设备")
    print("   请确认：手机无线调试已打开，或 USB 已连接")
    sys.exit(1)

print("=" * 78)
print("设备:", D)
print("=" * 78)


def sh(c, t=90):
    try:
        return subprocess.run(["adb", "-s", D, "exec-out", "su", "-c", c],
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                              timeout=t).stdout.decode("utf-8", "replace")
    except Exception as e:
        return "ERR " + str(e)


print("\n【1】设备时间")
print(sh("date '+%Y-%m-%d %H:%M:%S'").strip())

print("\n【2】模块安装时间 / 版本")
print(sh("dumpsys package com.mo.ugreenprotocolhook | grep -E 'lastUpdateTime|versionName'").strip())

print("\n【3】进程")
print(sh("ps -A -o PID,ETIME,NAME | grep -E 'bluetooth|ugreen'").strip())

print("\n【4】系统桥日志 路径与大小")
print(sh("ls -l /data/misc/bluetooth/logs/ugreen_system_bridge.log "
         "/sdcard/Download/ugreen_system_bridge.log "
         "/data/local/tmp/ugreen_system_bridge.log 2>&1").strip())

print("\n【5】系统桥日志 最后 45 行  ← 最关键")
print(sh("tail -45 /data/misc/bluetooth/logs/ugreen_system_bridge.log 2>/dev/null "
         "|| tail -45 /sdcard/Download/ugreen_system_bridge.log 2>/dev/null "
         "|| tail -45 /data/local/tmp/ugreen_system_bridge.log 2>/dev/null "
         "|| echo '(不存在)'"))

print("\n【6】电量桥文件内容")
print(sh("cat /sdcard/Download/ugreen_battery.txt 2>/dev/null || echo '(不存在)'").strip())

print("\n【7】协议桥 信标（证明 Hook 加载过）")
print(sh("tail -4 /sdcard/Download/ugreen_hook_beacon.txt 2>/dev/null || echo '(不存在)'").strip())

print("\n【8】LSPosed 是否报告模块异常（logcat）")
out = sh("logcat -d 2>/dev/null | grep -iE 'UGREEN-HOOK|UGREEN-SYSBRIDGE' | tail -20")
print(out.strip() if out.strip() else "(无)")

print("\n" + "=" * 78)
print("诊断结束")
print("=" * 78)
