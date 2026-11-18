# -*- coding: utf-8 -*-
"""
检查设备上各个蓝牙日志文件的真实格式（只读）。

判断依据：
  * btsnoop 二进制：文件头为 b'btsnoop\\x00'
  * bt_stack 文本日志：开头是 "Current process ID:"
"""

import subprocess
import os
import sys

# 输出强制 UTF-8，避免 Windows GBK 控制台报错
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

DIR = "/data/misc/bluetooth/logs"

FILES = [
    "bluetooth_20261002_140747_02997.log",   # 30.0 MB，10-03 10:37 结束
    "bluetooth_20261003_103725_02997.log",   # 31.0 MB，10-03 13:54 结束
    "bluetooth_20261003_135449_02997.log",   # 16.6 MB，10-03 14:47 结束
]


def head(path, n=16):
    p = subprocess.run(
        ["adb", "exec-out", "su", "-c", "head -c %d %s" % (n, path)],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    return p.stdout


def main():
    for name in FILES:
        path = "%s/%s" % (DIR, name)
        magic = head(path, 16)
        kind = "?"
        if magic.startswith(b"btsnoop"):
            kind = "BTSNOOP 二进制 (HCI 原始数据)"
        elif magic.startswith(b"Current process"):
            kind = "bt_stack 文本日志"
        else:
            kind = "未知"

        print("%-42s %-32s %r" % (name, kind, magic[:16]))


if __name__ == "__main__":
    main()
