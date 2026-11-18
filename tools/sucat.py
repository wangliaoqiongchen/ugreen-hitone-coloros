# -*- coding: utf-8 -*-
"""
只读地从手机把文件以二进制原样拉到本地。

关键点：
  * 用 root 读取（su）
  * adb 的 stdout 直接指向本地文件对象，不经过任何文本管道 / 控制台
    → 保证二进制不被破坏

用法：
  python sucat.py <设备路径> <本地路径> [head_bytes]

  head_bytes 可选：只取前 N 字节（用于快速探测文件头）
"""

import subprocess
import sys
import shutil
import os

ADB = shutil.which("adb") or r"C:\Windows\system32\adb.exe"


def remote_cmd(path, head=None):
    # 只读取，绝不写入设备
    if head:
        return "dd if=%s bs=1 count=%d 2>/dev/null" % (path, head)
    return "cat %s" % path


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 2

    remote = sys.argv[1]
    local = sys.argv[2]
    head = int(sys.argv[3]) if len(sys.argv) > 3 else None

    cmd = remote_cmd(remote, head)

    # 依次尝试几种 su 调用形式，取第一个能用的
    variants = [
        ["adb", "exec-out", "su", "-c", cmd],
        ["adb", "exec-out", "su", "0", "sh", "-c", cmd],
        ["adb", "exec-out", "su", "root", "sh", "-c", cmd],
    ]

    os.makedirs(os.path.dirname(os.path.abspath(local)), exist_ok=True)

    for variant in variants:
        # stdout 直接落盘，不经管道
        with open(local, "wb") as out:
            proc = subprocess.run(
                variant,
                stdout=out,
                stderr=subprocess.PIPE,
            )

        size = os.path.getsize(local)

        if proc.returncode == 0 and size > 0:
            # 判断是不是合法的 btsnoop：魔数 b'bsnoop\x00'
            with open(local, "rb") as fh:
                magic = fh.read(8)

            ok = magic.startswith(b"btsnoop")

            print("CMD      : %s" % " ".join(variant))
            print("BYTES    : %d" % size)
            print("MAGIC    : %r  %s" % (magic, "BTSNOOP-OK" if ok else "NOT-BTSNOOP"))
            print("SAVED    : %s" % local)
            if proc.stderr:
                err = proc.stderr.decode("utf-8", "replace").strip()
                if err:
                    print("STDERR   : %s" % err[:400])
            return 0

        err = proc.stderr.decode("utf-8", "replace").strip() if proc.stderr else ""
        print("TRY FAILED rc=%d size=%d : %s | %s"
              % (proc.returncode, size, " ".join(variant), err[:200]))

    print("ALL VARIANTS FAILED")
    return 1


if __name__ == "__main__":
    sys.exit(main())
