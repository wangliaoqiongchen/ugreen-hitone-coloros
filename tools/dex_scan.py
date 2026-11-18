# -*- coding: utf-8 -*-
"""
从官方 APK 里提取 DEX，并搜索"校验/命令构造"相关的线索。

思路：
  1. 从设备上已安装的 com.ugreen.iot 里把 classes*.dex 只读取回
  2. 在 DEX 字符串池里搜索关键词（checksum / crc / verify / 校验 等）
  3. 在字符串池里搜索已知的命令头模式（AA BB CC 之类）
  4. 输出候选，供后续用 jadx 定点反编译

只读设备，不写入设备任何内容。
"""

import subprocess
import os
import re
import io

DEVICE = "192.168.0.101:43675"
APK_PATH = "/data/app/*/com.ugreen.iot*/base.apk"
OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "report", "dex")

# 搜索关键词（大小写不敏感）
KEYWORDS = [
    "checksum", "checkSum", "check_code", "checkCode", "calcCrc", "crc8",
    "crc16", "verify", "getCheck", "makeCheck", "校验", "sum8", "xor8",
    "parity", "encode", "decode", "encrypt", "aes", "cipher",
    "writeData", "sendData", "sendCmd", "buildCmd", "packet",
]


def sh(cmd, timeout=300):
    p = subprocess.run(["adb", "-s", DEVICE, "exec-out", "su", "-c", cmd],
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                       timeout=timeout)
    return p.stdout


def list_dex():
    out = sh("unzip -l %s 2>/dev/null | grep -E 'classes[0-9]*\\.dex' | awk '{print $4}'" % APK_PATH)
    names = [n.strip() for n in out.decode("utf-8", "replace").splitlines() if n.strip()]
    return names


def fetch_dex(member, dest):
    """把 apk 里的某个 dex 成员解出来并写到本地。"""
    cmd = "cd /data/local/tmp && rm -f %s && unzip -o -q %s %s -d /data/local/tmp && cat /data/local/tmp/%s" % (
        os.path.basename(member), APK_PATH, member, member)
    p = subprocess.run(["adb", "-s", DEVICE, "exec-out", "su", "-c", cmd],
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=600)
    data = p.stdout
    with open(dest, "wb") as f:
        f.write(data)
    return len(data)


def dex_strings(data):
    """
    粗略扫出 DEX 里的字符串。
    DEX 的 string_data_item 是 ULEB128 长度 + MUTF-8 文本，
    这里用"可打印 ASCII 连续串"的近似方式提取，足够做关键词搜索。
    """
    out = []
    cur = bytearray()
    for b in data:
        if 32 <= b < 127:
            cur.append(b)
        else:
            if len(cur) >= 5:
                out.append(cur.decode("ascii", "replace"))
            cur = bytearray()
    if len(cur) >= 5:
        out.append(cur.decode("ascii", "replace"))
    return out


def main():
    os.makedirs(OUT_DIR, exist_ok=True)

    print("列出 APK 内的 dex ...")
    names = list_dex()
    print("发现:", names)

    all_hits = {}
    for name in names:
        dest = os.path.join(OUT_DIR, os.path.basename(name))
        n = fetch_dex(name, dest)
        print("  %-16s %8.2f MB -> %s" % (name, n / 1048576, dest))

        data = open(dest, "rb").read()
        strs = dex_strings(data)
        print("     字符串样本数: %d" % len(strs))

        for kw in KEYWORDS:
            pat = re.compile(re.escape(kw), re.I)
            hits = [s for s in strs if pat.search(s)]
            if hits:
                all_hits.setdefault(kw, set()).update(hits[:40])

    print()
    print("=" * 90)
    print("关键词命中汇总")
    print("=" * 90)
    for kw in KEYWORDS:
        hs = all_hits.get(kw)
        if not hs:
            continue
        hs = sorted(hs)
        print("\n[%s]  共 %d 条（最多显示 40）" % (kw, len(hs)))
        for s in hs[:40]:
            print("   ", s)


if __name__ == "__main__":
    main()
