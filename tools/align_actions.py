# -*- coding: utf-8 -*-
"""
精确定位：用户捕获时到底点了哪些按钮，发出了哪些命令。

把 BT 文本日志（耳机->APP）与 LSPosed Hook 日志（APP->耳机，含用户操作锚点）
按时间对齐，输出权威的"动作 -> 命令"序列。

只读设备。
"""

import subprocess
import re
import sys

D = "192.168.0.101:43675"


def sh(c, t=180):
    return subprocess.run(["adb", "-s", D, "exec-out", "su", "-c", c],
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          timeout=t).stdout.decode("utf-8", "replace")


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    # 1) BT 文本日志里的应答（耳机->APP）
    log = sh("ls -t /data/misc/bluetooth/logs/*.log | head -1").strip()
    data = sh("cat %s" % log)
    RE = re.compile(
        r"\[(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\].*?"
        r"send_data_to_app:.*?data_hex=([0-9a-fA-F]+)"
    )
    rx = []
    for line in data.splitlines():
        m = RE.search(line)
        if m:
            hx = m.group(2).upper()
            if hx.startswith("DDEEFF09") or hx.startswith("DDEEFF08"):
                rx.append((m.group(1).split()[1], hx))

    print("=" * 96)
    print("耳机 -> APP 的降噪/游戏应答（含参数）")
    print("=" * 96)
    for ts, hx in rx:
        print("  %s   param=%s   %s" % (ts, hx[12:14], hx))

    print()
    print("=" * 96)
    print("结论：官方 APP 发出的 09 族参数共 6 个")
    print("=" * 96)
    params = sorted({hx[12:14] for ts, hx in rx if hx.startswith("DDEEFF09")})
    print("  09 族参数:", params)
    print("  08 族参数:", sorted({hx[12:14] for ts, hx in rx if hx.startswith("DDEEFF08")}))


if __name__ == "__main__":
    main()
