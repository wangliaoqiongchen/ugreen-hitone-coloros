# -*- coding: utf-8 -*-
"""深挖设备快连的「三方耳机」弹窗路径与触发条件。"""

import re
import sys
import zipfile

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

APK = r"D:\AndroidStudio\UGREENforColorOS\交流\设备快连_17.6.15.apk"
z = zipfile.ZipFile(APK)

KEYWORDS = [
    b"ThirdHeadSet", b"ThirdPart", b"thirdPart", b"third_headset",
    b"DialogSceneGuard", b"FilterReason", b"TriggerState",
    b"headset", b"Headset",
]

for n in [x for x in z.namelist() if x.endswith(".dex")]:
    data = z.read(n)
    hits = {}
    for k in KEYWORDS:
        c = data.count(k)
        if c:
            hits[k.decode()] = c
    if not hits:
        continue

    print("=" * 76)
    print(n, " 命中:", hits)
    print("=" * 76)

    # 找 ThirdHeadSet / ThirdPart 相关字符串上下文
    for kw in [b"startThirdHeadSetConnect", b"showThirdPartHeadsetDialog",
               b"DialogSceneGuard", b"TriggerState", b"FilterReason"]:
        for m in list(re.finditer(re.escape(kw), data))[:2]:
            off = m.start()
            s = max(0, off - 800)
            e = min(len(data), off + 800)
            chunk = data[s:e]

            print()
            print("--- %s @%d ---" % (kw.decode(), off))
            seen = set()
            cnt = 0
            for x in re.finditer(rb"[\x20-\x7e]{4,70}", chunk):
                t = x.group(0).decode("ascii", "ignore")
                if t in seen or t.startswith("Lcom/"):
                    continue
                seen.add(t)
                print("     ", t)
                cnt += 1
                if cnt >= 40:
                    break
    break
