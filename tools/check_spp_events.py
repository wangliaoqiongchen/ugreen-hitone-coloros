# -*- coding: utf-8 -*-
"""
关键事实核查：官方 APP 在"单耳"状态下，SPP 连接到底是断了还是保持？

做法：
  统计 bt_stack 日志里 RFCOMM 连接的建立/断开事件次数与时间分布。
  如果官方 APP 也在反复断连重连，说明这是耳机固有行为，只能靠重连逻辑兜。
  如果官方 APP 连接稳定，说明它用了我们没用到的保活手段。

只读设备。
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

    logs = sh("ls -t /data/misc/bluetooth/logs/*.log 2>/dev/null | head -2").split()
    print("分析日志:", [l.split('/')[-1] for l in logs])
    print()

    RE_T = re.compile(r"\[(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\]")

    for lg in logs:
        data = sh("cat %s" % lg)
        print("=" * 96)
        print("文件:", lg.split('/')[-1])
        print("=" * 96)

        counts = {
            "connectSocket 发起": 0,
            "on_cli_rfc_connect 成功": 0,
            "cleanup_rfc_slot 断开": 0,
            "ACL 断开(LE)": 0,
            "ACL 连接(LE)": 0,
        }
        events = []

        for line in data.splitlines():
            t = RE_T.search(line)
            ts = t.group(1).split()[1] if t else None

            if "connectSocket: device=" in line:
                counts["connectSocket 发起"] += 1
                events.append((ts, "发起连接"))
            elif "on_cli_rfc_connect: connected" in line:
                counts["on_cli_rfc_connect 成功"] += 1
                events.append((ts, "SPP 连上"))
            elif "cleanup_rfc_slot: disconnected" in line:
                counts["cleanup_rfc_slot 断开"] += 1
                events.append((ts, "SPP 断开"))
            elif "BT_ACL_STATE_DISCONNECTED" in line and ("LE" in line or "le" in line):
                counts["ACL 断开(LE)"] += 1
            elif "BT_ACL_STATE_CONNECTED" in line and ("LE" in line or "le" in line):
                counts["ACL 连接(LE)"] += 1

        for k, v in counts.items():
            print("  %-24s %d" % (k, v))

        print()
        print("  SPP 连接/断开事件时间线（前 40 条）:")
        for ts, what in events[:40]:
            print("     %s  %s" % (ts, what))
        print("     ...（共 %d 条）" % len(events))
        print()


if __name__ == "__main__":
    main()
