# -*- coding: utf-8 -*-
"""
btsnoop 解析器 v3 —— 先打印原始事实，不做假设。

先回答三个问题：
  1. btsnoop 记录里 HCI 包类型字节在哪（有/无）
  2. HCI ACL 分片的 PB 标志分布
  3. 每条链路 (handle) 上的 L2CAP CID 分布

只读设备文件。
"""

import subprocess
import struct
import sys
import collections
import os

HCI_TYPES = {0x01, 0x02, 0x03, 0x04, 0x05, 0xFB, 0xFC}


def read_device(path):
    p = subprocess.run(["adb", "exec-out", "su", "-c", "cat %s" % path],
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    return p.stdout


def iter_records(data):
    pos, n = 16, len(data)
    while pos + 24 <= n:
        vals = struct.unpack(">IIIIII", data[pos:pos + 24])
        orig_len, incl_len, flags, drops, ts_sec, ts_usec = vals
        pos += 24
        if incl_len > 65535 or pos + incl_len > n:
            break
        payload = data[pos:pos + incl_len]
        pos += incl_len
        yield {
            "ts": ts_sec + ts_usec / 1e6,
            "dir": "RX" if (flags & 1) else "TX",
            "flags": flags,
            "data": payload,
        }


def main():
    path = sys.argv[1]
    data = read_device(path)
    print("bytes: %d" % len(data))

    recs = list(iter_records(data))
    print("records: %d" % len(recs))
    print()

    # ---- 问题1：payload 首字节分布 ----
    first = collections.Counter(r["data"][0] for r in recs if r["data"])
    print("记录 payload 首字节分布:")
    for b, n in first.most_common(10):
        tag = " <-- HCI 类型" if b in HCI_TYPES else ""
        print("   0x%02X  %-8d%s" % (b, n, tag))
    print()

    # ---- flags 分布 ----
    print("flags 分布: %s" % dict(collections.Counter(r["flags"] for r in recs)))
    print()

    # ---- 判定类型字节 ----
    has_type = all((r["data"][0] in HCI_TYPES) for r in recs if r["data"])
    print("每条记录都带 HCI 类型字节? %s" % has_type)
    print()

    # ---- 问题2：ACL 分片 PB 分布 ----
    pb_counter = collections.Counter()
    handle_counter = collections.Counter()
    acl_total = 0

    for r in recs:
        d = r["data"]
        off = 1 if has_type else 0
        if len(d) < off + 4:
            continue
        if has_type and d[0] != 0x02:
            continue
        hpb, dlen = struct.unpack("<HH", d[off:off + 4])
        h = hpb & 0x0FFF
        pb = (hpb >> 12) & 0x03
        pb_counter[pb] += 1
        handle_counter[(r["dir"], h)] += 1
        acl_total += 1

    print("ACL 包总数: %d" % acl_total)
    print("PB 标志分布: %s" % dict(pb_counter))
    print("链路 (dir, handle) 分布: %s" % dict(handle_counter))
    print()

    # ---- 问题3：不做重组，直接看每个 ACL 首片的 L2CAP 头 ----
    print("每个 ACL 包的 L2CAP 头样本（前 25 个）:")
    shown = 0
    for r in recs:
        d = r["data"]
        off = 1 if has_type else 0
        if has_type and (not d or d[0] != 0x02):
            continue
        if len(d) < off + 8:
            continue
        hpb, dlen = struct.unpack("<HH", d[off:off + 4])
        h = hpb & 0x0FFF
        pb = (hpb >> 12) & 0x03
        body = d[off + 4:off + 4 + dlen]
        if pb != 0x2 or len(body) < 4:
            continue
        l2len, cid = struct.unpack("<HH", body[0:4])
        print("   dir=%-3s h=0x%03X pb=%d dlen=%-4d | l2len=%-4d cid=0x%04X | %s"
              % (r["dir"], h, pb, dlen, l2len, cid, body[:32].hex().upper()))
        shown += 1
        if shown >= 25:
            break


if __name__ == "__main__":
    main()
