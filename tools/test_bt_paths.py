# -*- coding: utf-8 -*-
"""
决定性测试：蓝牙进程（uid 1002）到底能访问哪些路径。

用 root 直接「扮演」蓝牙 uid 去测试，
逐个打印 open / read 是否成功，定位卡在哪一层。
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


def sh(c, t=90):
    return subprocess.run(["adb", "-s", dev, "exec-out", "su", "-c", c],
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          timeout=t).stdout.decode("utf-8", "replace").strip()


BT = "1002"

print("=== 0. 各层目录权限 ===")
for d in ["/data", "/data/local", "/data/local/tmp",
          "/data/local/tmp/ugreen_bridge",
          "/data/misc", "/data/misc/bluetooth",
          "/data/misc/bluetooth/logs"]:
    print("  %-38s %s" % (d, sh("ls -ld %s 2>&1" % d)[:70]))

print()
print("=== 1. 用 setpriv 扮演蓝牙 uid 读文件 ===")

# 准备测试文件
sh("mkdir -p /data/local/tmp/ugreen_bridge && chmod 0777 /data/local/tmp/ugreen_bridge")
sh("printf 'left=100\\nright=55\\n' > /data/local/tmp/ugreen_bridge/t.txt")
sh("chmod 0666 /data/local/tmp/ugreen_bridge/t.txt")

targets = [
    "/data/local/tmp/ugreen_bridge/t.txt",
    "/data/misc/bluetooth/logs/t.txt",
    "/data/misc/bluetooth/t.txt",
]

# 在蓝牙自己的目录也放一份
sh("printf 'left=100\\nright=55\\n' > /data/misc/bluetooth/logs/t.txt")
sh("chmod 0666 /data/misc/bluetooth/logs/t.txt")
sh("printf 'left=100\\nright=55\\n' > /data/misc/bluetooth/t.txt")
sh("chmod 0666 /data/misc/bluetooth/t.txt")

for t in targets:
    r = sh("setpriv --reuid=%s --regid=%s --clear-groups cat %s 2>&1 || echo FAIL"
           % (BT, BT, t))
    ok = "FAIL" not in r and "Permission" not in r and "left=" in r
    print("  %-42s %s   %s" % (t, "OK  " if ok else "FAIL", r[:50].replace("\n", " ")))

print()
print("=== 2. 目录可搜索性（x 位）对比 ===")
for d in ["/data/local/tmp", "/data/misc/bluetooth/logs"]:
    r = sh("setpriv --reuid=%s --regid=%s --clear-groups ls %s >/dev/null 2>&1 "
           "&& echo LS_OK || echo LS_FAIL" % (BT, BT, d))
    r2 = sh("setpriv --reuid=%s --regid=%s --clear-groups "
            "test -x %s && echo X_OK || echo X_FAIL" % (BT, BT, d))
    print("  %-38s %s  %s" % (d, r, r2))

print()
print("=== 3. 结论性判断 ===")
r1 = sh("setpriv --reuid=%s --regid=%s --clear-groups cat "
        "/data/misc/bluetooth/logs/t.txt 2>&1" % (BT, BT))
r2 = sh("setpriv --reuid=%s --regid=%s --clear-groups cat "
        "/data/local/tmp/ugreen_bridge/t.txt 2>&1" % (BT, BT))
print("  蓝牙读 /data/misc/bluetooth/logs/t.txt  :", r1[:60].replace("\n", " "))
print("  蓝牙读 /data/local/tmp/ugreen_bridge/t.txt:", r2[:60].replace("\n", " "))

print()
print("=== 清理测试文件 ===")
sh("rm -f /data/local/tmp/ugreen_bridge/t.txt "
   "/data/misc/bluetooth/logs/t.txt /data/misc/bluetooth/t.txt")
print("done")
