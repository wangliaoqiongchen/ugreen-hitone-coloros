# -*- coding: utf-8 -*-
"""搜索系统应用里的降噪相关中文/英文 UI 文案，定位 UI 宿主。"""

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


def sh(c, t=900):
    return subprocess.run(["adb", "-s", dev, "exec-out", "su", "-c", c],
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          timeout=t).stdout.decode("utf-8", "replace").strip()


DIRS = ("/system/app /system/priv-app /system_ext/app /system_ext/priv-app "
        "/product/app /product/priv-app /my_product/app /my_product/priv-app "
        "/my_stock/app /my_stock/priv-app")

PATTERNS = [
    "降噪",
    "通透",
    "环境音",
    "noise_cancellation",
    "noiseCancellation",
    "transparency_mode",
]

for pat in PATTERNS:
    print("=" * 74)
    print("搜索:", pat)
    print("=" * 74)
    # 用 sh -c 传整个脚本，避免本地 shell 解析
    inner = ("for d in %s; do "
             "[ -d \"$d\" ] && grep -rl --include='*.apk' -e '%s' \"$d\" 2>/dev/null; "
             "done" % (DIRS, pat))
    out = sh(inner)
    if out:
        for line in out.splitlines():
            print("   ", line)
    else:
        print("    (无命中)")
    print()
