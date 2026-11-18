# -*- coding: utf-8 -*-
"""检查音量面板点击链路：SystemUI 是否把切换请求传到了 melody。"""

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


def sh(c, t=180):
    return subprocess.run(["adb", "-s", dev, "exec-out", "su", "-c", c],
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          timeout=t).stdout.decode("utf-8", "replace").strip()


LOG = "/data/data/com.oplus.melody/files/ugreen_melody_hook.log"
REQ = "/data/data/com.oplus.melody/files/ugreen_anc_request.txt"

print("时间:", sh("date '+%H:%M:%S'"))
print()

print("=" * 74)
print("1. 是否有 [call] 记录（SystemUI 的切换请求）")
print("=" * 74)
print("总条数:", sh("grep -c '\\[call\\]' %s 2>/dev/null || echo 0" % LOG))
print()
print(sh("grep '\\[call\\]' %s 2>/dev/null | tail -15" % LOG))

print()
print("=" * 74)
print("2. 降噪切换记录")
print("=" * 74)
print(sh("grep '切换降噪' %s 2>/dev/null | tail -10 || echo '(无)'" % LOG))

print()
print("=" * 74)
print("3. AncRequest 请求文件内容")
print("=" * 74)
print(sh("cat %s 2>/dev/null || echo '(不存在)'" % REQ))

print()
print("=" * 74)
print("4. 无线耳机日志最后 30 行")
print("=" * 74)
print(sh("tail -30 %s 2>/dev/null" % LOG))

print()
print("=" * 74)
print("5. 测试 APP 是否收到广播")
print("=" * 74)
out = sh("logcat -d 2>/dev/null | grep -iE '系统设备空间|系统请求|降噪请求|未就绪' | tail -12")
print(out if out else "(无)")
