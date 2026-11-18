# -*- coding: utf-8 -*-
"""诊断 Hook 为什么停止写入。"""

import subprocess
import datetime
import sys

D = "192.168.0.101:43675"


def sh(c, t=90):
    return subprocess.run(["adb", "-s", D, "exec-out", "su", "-c", c],
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          timeout=t).stdout.decode("utf-8", "replace")


try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

print("=== 进程 ===")
print(sh("ps -A -o PID,ETIME,NAME | grep -i ugreen"))

print("=== 全盘找本模块产生的文件 ===")
print(sh("find /sdcard /data/data/com.ugreen.iot /data/local/tmp "
         "-name 'ugreen_*' 2>/dev/null"))

print("=== 各日志文件状态 ===")
for p in ["/sdcard/Download/ugreen_protocol.log",
          "/sdcard/Android/data/com.ugreen.iot/files/ugreen_protocol.log",
          "/data/data/com.ugreen.iot/files/ugreen_protocol.log",
          "/sdcard/Download/ugreen_hook_beacon.txt"]:
    out = sh("if [ -f '{0}' ]; then "
             "echo \"size=$(stat -c %s '{0}')  mtime=$(stat -c %y '{0}')\"; "
             "else echo MISSING; fi".format(p))
    print("  %-58s %s" % (p, out.strip()))

print()
print("=== 信标内容（末尾 6 条，带换算时间）===")
b = sh("tail -6 /sdcard/Download/ugreen_hook_beacon.txt")
print(b)
for line in b.splitlines():
    parts = line.split()
    if len(parts) >= 2 and parts[1].isdigit():
        ms = int(parts[1])
        print("    %-14s -> %s" % (parts[0],
                                   datetime.datetime.fromtimestamp(ms / 1000)))

print()
print("=== 耳机是否还连着 ===")
print(sh("dumpsys bluetooth_manager | grep -iE 'HiTune|90:A0:BE' | head -5"))

print()
print("=== 当前时间（对比日志最后一行 16:27:31）===")
print(sh("date '+%Y-%m-%d %H:%M:%S'"))
