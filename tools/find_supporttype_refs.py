# -*- coding: utf-8 -*-
"""在 SystemUI 的 dex 里定位 EarphoneController.supportType 的使用点。"""

import re
import sys
import zipfile

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

APK = r"D:\AndroidStudio\UGREENforColorOS\tools\report\SystemUI.apk"
z = zipfile.ZipFile(APK)

KEYWORDS = [
    b"supportType",
    b"getSupportType",
    b"setActiveDevice",
    b"refreshSupportType",
    b"refreshActiveDevice",
]

SUBCLASS = b"EarphoneController$ActiveDevice"

for dex in [n for n in z.namelist() if n.endswith(".dex")]:
    data = z.read(dex)

    counts = {}
    for kw in KEYWORDS:
        counts[kw.decode()] = len(re.findall(re.escape(kw), data))

    if not any(counts.values()):
        continue

    print("=" * 74)
    print(dex, " 大小", len(data))
    print("=" * 74)
    for k, v in counts.items():
        print("   %-24s %d" % (k, v))

    offs = [m.start() for m in re.finditer(re.escape(SUBCLASS), data)]
    print("   ActiveDevice 描述符出现 %d 次" % len(offs))

    # supportType 字符串附近的上下文
    for m in list(re.finditer(b"supportType", data))[:8]:
        off = m.start()
        s = max(0, off - 90)
        e = min(len(data), off + 90)
        ctx = re.sub(rb"[^\x20-\x7e]+", b".", data[s:e]).decode("ascii", "ignore")
        print("   [supportType @%d] %s" % (off, ctx))

    # setActiveDevice 附近的字符串（可能暴露支持类型来源）
    for m in list(re.finditer(b"setActiveDevice", data))[:3]:
        off = m.start()
        s = max(0, off - 140)
        e = min(len(data), off + 140)
        ctx = re.sub(rb"[^\x20-\x7e]+", b".", data[s:e]).decode("ascii", "ignore")
        print("   [setActiveDevice @%d] %s" % (off, ctx))

    print()
