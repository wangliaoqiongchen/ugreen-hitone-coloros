# -*- coding: utf-8 -*-
"""
在设备上的所有系统应用里搜索"降噪控制 UI"的宿主。

目标字符串选用最独特的几个（不容易误命中）：
    NOISE_CANCELLATION_MODE     降噪模式常量
    setNoiseMode
    getDeviceExtendFeatureMask  能力查询

做法：用 grep -l 直接扫 apk（grep 能把 dex 当二进制扫），
      只在 apk 文件里找，避免扫到无关文件。
"""

import subprocess
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

D = subprocess.run(["adb", "devices"], stdout=subprocess.PIPE).stdout.decode()
dev = None
for line in D.splitlines()[1:]:
    p = line.split()
    if len(p) >= 2 and p[1] == "device":
        dev = p[0]
        break


def sh(c, t=600):
    return subprocess.run(["adb", "-s", dev, "exec-out", "su", "-c", c],
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          timeout=t).stdout.decode("utf-8", "replace").strip()


SEARCH_DIRS = [
    "/system/app", "/system/priv-app",
    "/system_ext/app", "/system_ext/priv-app",
    "/product/app", "/product/priv-app",
    "/my_product/app", "/my_product/priv-app",
    "/my_stock/app", "/my_stock/priv-app",
    "/odm/app", "/odm/priv-app",
    "/vendor/app",
]

# 最独特的字符串（越独特越不容易误命中）
PATTERNS = [
    "NOISE_CANCELLATION_MODE",
    "NOISE_TRANSPARENT_MODE",
    "setOplusDeviceNoiseModeSupport",
    "registerBluetoothExtendFeatureCallback",
    "getDeviceExtendFeatureMask",
]

for pat in PATTERNS:
    print("=" * 78)
    print("搜索:", pat)
    print("=" * 78)
    cmd = ("for d in %s; do "
           "[ -d \"$d\" ] && grep -rl --include='*.apk' -e '%s' \"$d\" 2>/dev/null; "
           "done" % (" ".join(SEARCH_DIRS), pat))
    out = sh(cmd)
    if out:
        for line in out.splitlines():
            print("   ", line)
    else:
        print("    (无命中)")
    print()
