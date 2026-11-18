# -*- coding: utf-8 -*-
"""
检查设备上各个候选日志路径的状态（只读）。

用来定位"Hook 到底有没有在写、写到哪了"。
"""

import subprocess
import sys

DEVICE = "192.168.0.101:43675"

PATHS = [
    "/sdcard/Download/ugreen_protocol.log",
    "/sdcard/Download/ugreen_hook_beacon.txt",
    "/sdcard/Download/.ugreen_clear_request",
    "/sdcard/Android/data/com.ugreen.iot/files/ugreen_protocol.log",
    "/sdcard/Android/data/com.ugreen.iot/files/ugreen_hook_beacon.txt",
    "/data/data/com.ugreen.iot/files/ugreen_protocol.log",
    "/data/data/com.ugreen.iot/files/ugreen_hook_beacon.txt",
    "/data/data/com.ugreen.iot/files/.ugreen_clear_request",
    "/data/local/tmp/ugreen_protocol.log",
    "/data/local/tmp/ugreen_hook_beacon.txt",
]


def sh(cmd, timeout=60):
    p = subprocess.run(["adb", "-s", DEVICE, "exec-out", "su", "-c", cmd],
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                       timeout=timeout)
    return p.stdout.decode("utf-8", "replace")


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    print("=" * 100)
    print("%-8s %-12s %-20s %s" % ("状态", "大小", "修改时间", "路径"))
    print("=" * 100)

    for path in PATHS:
        out = sh("if [ -f %s ]; then stat -c '%%s|%%y' %s; else echo MISSING; fi"
                 % (path, path)).strip()
        if out == "MISSING" or not out:
            print("%-8s %-12s %-20s %s" % ("缺失", "-", "-", path))
        else:
            parts = out.split("|")
            size = parts[0].strip() if parts else "?"
            mtime = parts[1].strip()[:19] if len(parts) > 1 else "?"
            print("%-8s %-12s %-20s %s" % ("存在", size, mtime, path))

    print()
    print("=" * 100)
    print("各目录里所有以 ugreen 开头的文件")
    print("=" * 100)
    for d in ["/sdcard/Download",
              "/sdcard/Android/data/com.ugreen.iot/files",
              "/data/data/com.ugreen.iot/files",
              "/data/local/tmp"]:
        out = sh("ls -la %s 2>/dev/null | grep -i ugreen" % d).strip()
        print("\n[%s]" % d)
        print(out if out else "   (无)")

    print()
    print("=" * 100)
    print("官方 APP 进程是否存活")
    print("=" * 100)
    print(sh("ps -A -o PID,USER,NAME 2>/dev/null | grep -i ugreen || echo '(未运行)'"))


if __name__ == "__main__":
    main()
