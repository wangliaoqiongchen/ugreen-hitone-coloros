# -*- coding: utf-8 -*-
"""
验证华为用的 Hook 目标在本机「设备空间」里是否存在。

华为用的两个关键点：
    1. com.heytap.mydevices.plugin.bluetooth.fragment.BtDetailPageFragment
       （17.x 的蓝牙详情页）
    2. androidx.preference.Preference 的绑定方法
    3. preference key: pref_bt_audio_settings

本脚本在设备空间 APK 里搜这些字符串。
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

if not os.path.exists(APK):
    print("APK 不存在:", APK)
    sys.exit(1)

z = zipfile.ZipFile(APK)
dexes = [n for n in z.namelist() if n.endswith(".dex")]
print("APK:", os.path.basename(APK), os.path.getsize(APK), "bytes")
print("dex:", dexes)
print()

TARGETS = [
    b"BtDetailPageFragment",
    b"com/heytap/mydevices/plugin/bluetooth/fragment",
    b"com/oplus/mydevices/bluetooth/fragment",
    b"pref_bt_audio_settings",
    b"androidx/preference/Preference",
    b"onViewCreated",
    b"findPreference",
    b"bluetooth/fragment",
    b"BtDetailPage",
]

for t in TARGETS:
    hits = []
    for d in dexes:
        data = z.read(d)
        n = data.count(t)
        if n:
            hits.append("%s x%d" % (d, n))
    print("  %-52s %s" % (t.decode(), ", ".join(hits) if hits else "❌ 无"))
