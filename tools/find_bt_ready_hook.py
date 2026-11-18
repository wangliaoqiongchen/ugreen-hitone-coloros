# -*- coding: utf-8 -*-
"""
从真实 bt_stack 日志里找"蓝牙就绪"事件的方法名与类名。

目的：确定应该 Hook 哪个方法来做"二次探测"，
      而不是靠猜方法名（违反"不写死/不猜"的原则）。
"""

import subprocess
import re
import sys
import collections

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
    print("无设备")
    sys.exit(1)


def sh(c, t=300):
    return subprocess.run(["adb", "-s", D, "exec-out", "su", "-c", c],
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          timeout=t).stdout.decode("utf-8", "replace")


log = sh("ls -t /data/misc/bluetooth/logs/*.log | head -1").strip()
print("日志:", log)
data = sh("cat %s" % log)
lines = data.splitlines()
print("行数:", len(lines))
print()

# 1) 找含 state / adapter 状态相关的类与方法
pats = [
    r"onBluetoothStateChange",
    r"onAdapterStateChanged",
    r"AdapterState",
    r"STATE_ON",
    r"BluetoothState",
    r"setBluetoothState",
    r"onBluetoothEnabled",
    r"AdapterService",
    r"AdapterProperties",
]
print("=" * 92)
print("关键词命中统计")
print("=" * 92)
for p in pats:
    n = len([l for l in lines if re.search(p, l)])
    print("  %-28s %d" % (p, n))

print()
print("=" * 92)
print("含 AdapterService / AdapterProperties 的方法名样本")
print("=" * 92)
funcs = collections.Counter()
for l in lines:
    for m in re.finditer(r"\[(\w+\.cc:\d+)\]\[?\w*\]?\s*(\w+)", l):
        funcs[m.group(2)] += 1
for f, n in funcs.most_common(30):
    print("  %-46s %d" % (f, n))

print()
print("=" * 92)
print("含 'STATE_ON' 或 'enabled' 的原始行样本（前 20）")
print("=" * 92)
shown = 0
for l in lines:
    if re.search(r"STATE_ON|STATE_TURNING_ON|onBluetoothStateChange|bt_enabled", l):
        print(l[:230])
        shown += 1
        if shown >= 20:
            break
if shown == 0:
    print("  (没有找到)")
