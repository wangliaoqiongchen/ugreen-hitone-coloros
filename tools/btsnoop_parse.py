# -*- coding: utf-8 -*-
"""
btsnoop 解析器 v4 —— 事实版。

已用调试脚本证实的关键事实：
  * 每条 btsnoop 记录都带 1 字节 HCI 类型
  * ACL 包（类型 0x02）的 PB 标志只有 {0, 2}
      → 0 = 无分包信息（TX 常见） 2 = 首个/完整分片
      → 【没有任何后续分片】每包恰好一个完整 L2CAP PDU
  * flags: 0/1 = ACL(TX/RX)，2/3 = 事件

本脚本：按 L2CAP CID 导出全部数据面，重点找 8 字节业务帧。

只读设备文件。
"""

import subprocess
import struct
import sys
import os
import collections

HCI_ACL = 0x02


def read_device(path):
    p = subprocess.run(["adb", "exec-out", "su", "-c", "cat %s" % path],
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if p.returncode != 0:
        sys.stderr.write(p.stderr.decode("utf-8", "replace"))
    return p.stdout


def iter_acl_l2cap(data):
    """产出 (ts, dir, handle, cid, payload)。"""
    pos, n = 16, len(data)
    out = []
    while pos + 24 <= n:
        orig_len, incl_len, flags, drops, ts_sec, ts_usec = struct.unpack(
            ">IIIIII", data[pos:pos + 24])
        pos += 24
        if incl_len > 65535 or pos + incl_len > n:
            break
        payload = data[pos:pos + incl_len]
        pos += incl_len

        direction = "RX" if (flags & 1) else "TX"

        # 事件包跳过
        if not payload or payload[0] != HCI_ACL:
            continue
        if len(payload) < 5:
            continue

        hpb, dlen = struct.unpack("<HH", payload[1:5])
        handle = hpb & 0x0FFF
        body = payload[5:5 + dlen]

        if len(body) < 4:
            continue
        l2len, cid = struct.unpack("<HH", body[0:4])
        l2payload = body[4:4 + l2len]

        out.append((ts_sec + ts_usec / 1e6, direction, handle, cid, l2payload))

    return out


def main():
    path = sys.argv[1]
    out_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "report")
    os.makedirs(out_dir, exist_ok=True)

    print("reading (read-only) ...")
    data = read_device(path)
    pdus = iter_acl_l2cap(data)
    print("ACL/L2CAP PDU: %d" % len(pdus))

    # 按 (handle, cid) 统计
    per = collections.Counter((h, c) for _, _, h, c, _ in pdus)
    print()
    print("(handle, CID) 分布:")
    for (h, c), n in per.most_common(25):
        lens = [len(p) for _, _, hh, cc, p in pdus if hh == h and cc == c]
        print("   h=0x%03X CID=0x%04X  n=%-4d len范围=%s"
              % (h, c, n, sorted(set(lens))))

    # 全部导出
    t0 = min(p[0] for p in pdus)
    out_path = os.path.join(out_dir, "btsnoop_all_pdus.txt")
    with open(out_path, "w", encoding="utf-8") as fh:
        fh.write("btsnoop 全部 L2CAP PDU\n源: %s\n" % path)
        fh.write("=" * 112 + "\n\n")
        for ts, d, h, c, pay in pdus:
            fh.write("+%9.3fs %-3s h=0x%03X CID=0x%04X len=%-4d %s\n"
                     % (ts - t0, d, h, c, len(pay), pay.hex().upper()))
    print()
    print("written: %s" % out_path)


if __name__ == "__main__":
    main()
