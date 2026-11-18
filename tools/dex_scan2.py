# -*- coding: utf-8 -*-
"""
在已下载的 DEX 里搜索协议/校验相关线索。

重点找：
  · 校验相关的类名/方法名
  · 命令构造相关的类名
  · 已知的协议魔数（0xAA 0xBB 0xCC 之类的字符串形式）
  · 绿联自己的协议类名（UGREEN / Ugreen 开头）

只读本地文件。
"""

import os
import re
import sys
import glob

DEX_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "report", "dex")

KW = [
    # 校验
    "CheckSum", "checkSum", "checksum", "CheckCode", "checkCode",
    "CalCheck", "calCheck", "CalcCheck", "crc", "Crc", "CRC",
    "Verify", "verify", "makeSum", "sumCheck", "CheckData",
    "校验", "VerifyData", "checkData", "CheckByte", "getCheck",
    # 协议/命令
    "CmdType", "cmdType", "CommandType", "Protocol", "protocol",
    "PacketUtil", "ByteUtil", "DataUtil", "HexUtil",
    "Ugreen", "UGREEN", "ugreen",
    # 设备控制
    "Anc", "ANC", "anc", "Noise", "noise", "Transparency",
    "GameMode", "gameMode", "Eq", "Equalizer",
    # 可能是魔数
    "AABBCC", "aabbcc", "0xAA", "170, 187, 204",
]


def dex_strings(data):
    out = []
    cur = bytearray()
    for b in data:
        if 32 <= b < 127:
            cur.append(b)
        else:
            if len(cur) >= 4:
                out.append(cur.decode("ascii", "replace"))
            cur = bytearray()
    if len(cur) >= 4:
        out.append(cur.decode("ascii", "replace"))
    return out


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    files = sorted(glob.glob(os.path.join(DEX_DIR, "*.dex")))
    if not files:
        print("没有找到 DEX 文件，请先运行 dex_scan.py")
        return

    print("DEX 文件: %s" % [os.path.basename(f) for f in files])
    print()

    # 收集所有字符串（去重）
    allstr = set()
    for f in files:
        data = open(f, "rb").read()
        allstr.update(dex_strings(data))
    print("字符串总数（去重）: %d" % len(allstr))
    print()

    for kw in KW:
        pat = re.compile(re.escape(kw))
        hits = sorted(s for s in allstr if pat.search(s))
        if not hits:
            continue
        print("=" * 90)
        print("[%s]  命中 %d 条" % (kw, len(hits)))
        print("=" * 90)
        for s in hits[:25]:
            print("   ", s)
        print()


if __name__ == "__main__":
    main()
