# -*- coding: utf-8 -*-
"""
导出 classes4.dex 的可读字符串，并按主题分类，帮助定位协议实现。

重点找：
  · 协议帧相关（frame / head / cmd / payload / len）
  · 校验相关
  · 设备型号与特性名
  · 非拉丁文字（可能是中文日志）
"""

import os
import re
import sys

DEX = r"D:\AndroidStudio\UGREENforColorOS\tools\report\dex\classes4.dex"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "report",
                   "classes4_strings.txt")


def extract(data, minlen=4):
    out = []
    cur = bytearray()
    for b in data:
        if 32 <= b < 127:
            cur.append(b)
        else:
            if len(cur) >= minlen:
                out.append(cur.decode("ascii", "replace"))
            cur = bytearray()
    if len(cur) >= minlen:
        out.append(cur.decode("ascii", "replace"))
    return out


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    data = open(DEX, "rb").read()
    strs = sorted(set(extract(data)))
    print("可读字符串（去重）: %d" % len(strs))

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        for s in strs:
            f.write(s + "\n")
    print("已写入:", OUT)

    groups = {
        "协议/帧": r"frame|Frame|head|Head|payload|Payload|cmdType|CmdType|opcode|OpCode",
        "校验": r"[Cc]heck[Ss]um|checksum|[Cc]rc|CRC|verify|Verify|parity|校验",
        "耳机功能": r"[Aa]nc|ANC|[Nn]oise|Transparen|[Gg]ame|[Ee]q\b|Equaliz|Battery|电量|降噪|通透",
        "设备型号": r"HiTune|UGREEN|EchoBuds|ClipBuds|Studio",
        "字符特征": r"CHARACTERISTIC|SERVICE_ID|6666|7777",
        "AT指令": r"^AT\+|^\+[A-Z]{3,}",
    }

    for title, pat in groups.items():
        rx = re.compile(pat)
        hits = [s for s in strs if rx.search(s)]
        print()
        print("=" * 90)
        print("[%s]  命中 %d 条" % (title, len(hits)))
        print("=" * 90)
        for s in hits[:60]:
            print("   ", s)


if __name__ == "__main__":
    main()
