# -*- coding: utf-8 -*-
"""
转储设备空间 classes4.dex 里 preference / vmState 相关的字符串表。

目的：搞清「设备空间」蓝牙详情页的完整结构——
      它有哪些 preference 键、vmState 有哪些取值、
      AirPods 支持是怎么挂上去的。
"""

import os
import re
import sys
import zipfile

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

APK = r"D:\AndroidStudio\UGREENforColorOS\交流\设备空间_17.25.10.apk"
z = zipfile.ZipFile(APK)
data = z.read("classes4.dex")

print("classes4.dex 大小:", len(data))
print()

# 1) 转储 pref_bt_audio_settings 附近大窗口
print("=" * 92)
print("pref_bt_audio_settings 附近 2000 字节")
print("=" * 92)
idx = data.find(b"pref_bt_audio_settings")
if idx > 0:
    s = max(0, idx - 1000)
    e = min(len(data), idx + 1000)
    chunk = data[s:e]
    # 提取可读 ASCII 串（保持顺序）
    for m in re.finditer(rb"[\x20-\x7e]{4,}", chunk):
        print("   ", m.group(0).decode("ascii", "ignore")[:160])

# 2) 搜所有 pref_ 开头的键
print()
print("=" * 92)
print("所有 pref_* 键（全 dex）")
print("=" * 92)
keys = set()
for m in re.finditer(rb"pref_[a-z0-9_]{3,60}", data):
    keys.add(m.group(0).decode())
for k in sorted(keys):
    print("   ", k)
print("总数:", len(keys))

# 3) 搜 vmState 相关
print()
print("=" * 92)
print("vmState 相关字符串")
print("=" * 92)
seen = set()
for m in re.finditer(rb"[\x20-\x7e]{4,}", data):
    t = m.group(0).decode("ascii", "ignore")
    if "vmState" in t or "VmState" in t:
        if t not in seen:
            seen.add(t)
            print("   ", t[:170])
