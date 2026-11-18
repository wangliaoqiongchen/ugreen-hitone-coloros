# -*- coding: utf-8 -*-
"""
从 ColorOS bt_stack 文本日志中提取 SPP/RFCOMM 数据面（官方"声学"APP 的协议通道）。

只读设备日志，不向设备写入任何内容。

输出：
  tools/report/spp_packets.txt     按时间排序的完整包序列
  tools/report/spp_summary.txt     按 payload 去重后的协议清单
"""

import subprocess
import re
import os
import sys
import collections

LOG = "/data/misc/bluetooth/logs/bluetooth_20261003_135449_02997.log"

OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "report")

# send_data_to_app: slot_id=9 app_uid=10564 scn=13 peer=90:a0:be:2d:5e:ca len=8 data_hex=858687040164644B
RE_DUMP = re.compile(
    r"\[(?P<t1>[^\]]+)\]\[(?P<t2>[^\]]+)\]\[(?P<lvl>[A-Z])\]\[(?P<tid>\d+)\].*?"
    r"send_data_to_app: slot_id=(?P<slot>\d+) app_uid=(?P<uid>\d+) scn=(?P<scn>\d+) "
    r"peer=(?P<peer>[0-9a-fA-F:]+) len=(?P<len>\d+) data_hex=(?P<hex>[0-9a-fA-F]+)"
)


def read_log():
    """通过 root 只读地把日志流到内存。"""
    p = subprocess.run(
        ["adb", "exec-out", "su", "-c", "cat %s" % LOG],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if p.returncode != 0:
        sys.stderr.write(p.stderr.decode("utf-8", "replace"))
    return p.stdout.decode("utf-8", "replace").splitlines()


def try_utf8_strings(payload_hex):
    """尝试从载荷里提取可读 UTF-8 字符串（设备名之类）。"""
    try:
        raw = bytes.fromhex(payload_hex)
    except ValueError:
        return []

    found = []
    cur = bytearray()
    for b in raw:
        if 0x20 <= b < 0x7F:
            cur.append(b)
        elif b >= 0x80:
            cur.append(b)
        else:
            if len(cur) >= 3:
                found.append(bytes(cur))
            cur = bytearray()
    if len(cur) >= 3:
        found.append(bytes(cur))

    out = []
    for chunk in found:
        try:
            s = chunk.decode("utf-8")
        except UnicodeDecodeError:
            continue
        if any("\u4e00" <= ch <= "\u9fff" for ch in s):
            out.append(s)
    return out


def main():
    os.makedirs(OUT_DIR, exist_ok=True)

    print("reading log (read-only) ...")
    lines = read_log()
    print("lines: %d" % len(lines))

    packets = []
    for l in lines:
        m = RE_DUMP.search(l)
        if m:
            packets.append(m.groupdict())

    print("spp packets: %d" % len(packets))

    # ---------------- 完整序列 ----------------
    seq_path = os.path.join(OUT_DIR, "spp_packets.txt")
    with open(seq_path, "w", encoding="utf-8") as fh:
        fh.write("SPP/RFCOMM 数据面完整序列（耳机 -> 官方声学 APP）\n")
        fh.write("源日志: %s\n" % LOG)
        fh.write("包总数: %d\n" % len(packets))
        fh.write("=" * 100 + "\n\n")

        prev = None
        for i, p in enumerate(packets, 1):
            hx = p["hex"].upper()
            gap = ""
            if prev:
                gap = "gap=%s" % (p["t2"].split()[0])
            similar = " <== 与上一包相同" if prev == hx else ""
            fh.write("#%-4d %-12s len=%-3s %s%s\n"
                     % (i, p["t2"].split()[0], p["len"], hx, similar))

            strs = try_utf8_strings(hx)
            if strs:
                fh.write("        └─ 可读文本: %s\n" % " | ".join(strs))

            prev = hx

    # ---------------- 去重清单 ----------------
    counter = collections.Counter(p["hex"].upper() for p in packets)
    first_seen = {}
    for p in packets:
        hx = p["hex"].upper()
        if hx not in first_seen:
            first_seen[hx] = p["t2"].split()[0]

    sum_path = os.path.join(OUT_DIR, "spp_summary.txt")
    with open(sum_path, "w", encoding="utf-8") as fh:
        fh.write("SPP/RFCOMM 协议去重清单（按出现次数排序）\n")
        fh.write("=" * 100 + "\n\n")
        fh.write("%-5s %-14s %-10s %s\n" % ("次数", "首次出现", "长度", "HEX"))
        fh.write("-" * 100 + "\n")
        for hx, n in counter.most_common():
            fh.write("%-5d %-14s %-10d %s\n"
                     % (n, first_seen[hx], len(hx) // 2, hx))
            strs = try_utf8_strings(hx)
            if strs:
                fh.write("%-32s └─ 文本: %s\n" % ("", " | ".join(strs)))

    print("written: %s" % seq_path)
    print("written: %s" % sum_path)


if __name__ == "__main__":
    main()
