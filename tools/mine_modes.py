# -*- coding: utf-8 -*-
"""
从 bt_stack 文本日志里挖出所有 09 族命令，建立"参数 -> 时间"序列。

目的：和用户报告的官方 APP 界面模式对齐，建立权威映射表。

只读设备。
"""

import subprocess
import re
import sys
import os

DEVICE = "192.168.0.101:43675"

# 最新的 bt_stack 文本日志（每次审计用 ls 找最新的）
LOG_DIR = "/data/misc/bluetooth/logs"


def sh(cmd, timeout=180):
    return subprocess.run(["adb", "-s", DEVICE, "exec-out", "su", "-c", cmd],
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          timeout=timeout).stdout.decode("utf-8", "replace")


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    # 找最新的文本日志
    listing = sh("ls -t %s/*.log 2>/dev/null | head -1" % LOG_DIR).strip()
    if not listing:
        print("找不到日志文件")
        return
    path = listing
    print("读取:", path)

    data = sh("cat %s" % path)
    lines = data.splitlines()
    print("总行数:", len(lines))
    print()

    RE = re.compile(
        r"\[(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\].*?"
        r"send_data_to_app:.*?len=(\d+) data_hex=([0-9a-fA-F]+)"
    )

    print("=" * 92)
    print("所有 09 族与 08 族命令（耳机->APP 方向）")
    print("=" * 92)

    hits = []
    for line in lines:
        m = RE.search(line)
        if not m:
            continue
        ts, ln, hx = m.group(1).split()[1], m.group(2), m.group(3).upper()
        # 09 族：DDEEFF09 01 01 <param>；08 族：DDEEFF08 01 01 <param>
        if hx.startswith("DDEEFF09") or hx.startswith("DDEEFF08"):
            hits.append((ts, hx))

    print("共 %d 条" % len(hits))
    for ts, hx in hits:
        param = hx[12:14] if len(hx) >= 14 else "??"
        print("  %s  param=%s   %s" % (ts, param, hx))

    print()
    print("=" * 92)
    print("09 族参数汇总")
    print("=" * 92)
    from collections import Counter
    c = Counter(hx[12:14] for _, hx in hits if hx.startswith("DDEEFF09"))
    for param, n in sorted(c.items()):
        print("  param=%s  出现 %d 次" % (param, n))


if __name__ == "__main__":
    main()
