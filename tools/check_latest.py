# -*- coding: utf-8 -*-
"""
核对：找到最新的捕获，确认用户这次测试的时间窗与参数序列。

跨所有 bt_stack 文本日志文件搜索，避免漏掉日志轮转。
"""

import subprocess
import re
import sys

D = "192.168.0.101:43675"


def sh(c, t=300):
    return subprocess.run(["adb", "-s", D, "exec-out", "su", "-c", c],
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          timeout=t).stdout.decode("utf-8", "replace")


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    print("当前设备时间:", sh("date '+%Y-%m-%d %H:%M:%S'").strip())
    print()

    print("=== 最近的 bt_stack 文本日志文件 ===")
    print(sh("ls -lt /data/misc/bluetooth/logs/*.log 2>/dev/null | head -4"))

    print("=== 当前 Hook 日志（我们 APP 写的）===")
    print(sh("tail -40 /sdcard/Download/ugreen_protocol.log 2>/dev/null || echo '(无)'"))

    # 在所有文本日志里找 16:30 之后的 09/08 应答
    logs = sh("ls -t /data/misc/bluetooth/logs/*.log 2>/dev/null | head -3").split()
    RE = re.compile(
        r"\[(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\].*?"
        r"send_data_to_app:.*?data_hex=([0-9a-fA-F]+)"
    )

    print()
    print("=== 跨文件搜索 16:48 之后的 09/08 应答 ===")
    found = []
    for lg in logs:
        data = sh("cat %s" % lg)
        for line in data.splitlines():
            m = RE.search(line)
            if not m:
                continue
            hx = m.group(2).upper()
            ts = m.group(1).split()[1]
            if (hx.startswith("DDEEFF09") or hx.startswith("DDEEFF08")) and ts >= "16:48":
                found.append((ts, hx, lg.split("/")[-1]))

    if not found:
        print("  (没有找到 16:48 之后的记录)")
    for ts, hx, lg in found:
        print("  %s   param=%s   %s   [%s]" % (ts, hx[12:14], hx, lg))


if __name__ == "__main__":
    main()
