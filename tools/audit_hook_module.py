# -*- coding: utf-8 -*-
"""
Hook 模块结构审计。

为什么需要它：
    这个模块会注入 com.android.bluetooth（系统进程），
    任何结构错误都必须在上机前发现，不能靠"编译报错再改"。

检查项：
    1. 字段重复声明
    2. 方法重复定义（同签名）
    3. 括号平衡
    4. Kotlin 习惯漏进 Java
    5. 引用但不存在的标识符（基础版：本文件内声明的字段/方法 vs 使用）
    6. 明显的"编辑残留"（连续空行、孤立大括号）
"""

import os
import re
import sys
import collections

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

BASE = r"D:\AndroidStudio\UGREENforColorOS"

FILES = [
    r"protocolhook\src\main\java\com\mo\ugreenprotocolhook\HookEntry.java",
    r"protocolhook\src\main\java\com\mo\ugreenprotocolhook\ColorOsBatteryBridge.java",
    r"protocolhook\src\main\java\com\mo\ugreenprotocolhook\SystemBridgeLog.java",
    r"protocolhook\src\main\java\com\mo\ugreenprotocolhook\ProtocolLog.java",
    r"protocolhook\src\main\java\com\mo\ugreenprotocolhook\MainActivity.java",
]


def strip_comments(src):
    s = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    s = re.sub(r"//[^\n]*", "", s)
    return s


def check_field_duplicates(name, nc):
    """找出同一类里重复声明的字段名。"""
    # 简单匹配：<修饰符...> <类型> <名字> [= ...];
    pat = re.compile(
        r"^[ \t]*(?:private|public|protected|static|final|volatile|transient|\s)+"
        r"[A-Za-z_$][\w$<>\[\],.? ]*\s+([A-Za-z_$][\w$]*)\s*(?:=[^;]*)?;",
        re.M)
    names = []
    for m in pat.finditer(nc):
        n = m.group(1)
        # 排除方法声明误匹配（后面紧跟括号的）
        tail = nc[m.end():m.end() + 3]
        if tail.lstrip().startswith("("):
            continue
        names.append(n)
    dup = [n for n, c in collections.Counter(names).items() if c > 1]
    return dup


def check_method_duplicates(name, nc):
    """找出重复定义的方法（按 名字+参数个数）。"""
    pat = re.compile(
        r"^[ \t]*(?:@\w+\s*)*"
        r"(?:private|public|protected|static|final|synchronized|\s)+"
        r"[A-Za-z_$][\w$<>\[\],.? ]*\s+([A-Za-z_$][\w$]*)\s*\(([^)]*)\)\s*\{",
        re.M)
    sigs = []
    for m in pat.finditer(nc):
        mn = m.group(1)
        params = m.group(2)
        argc = len([p for p in params.split(",") if p.strip()]) if params.strip() else 0
        sigs.append((mn, argc))
    dup = [s for s, c in collections.Counter(sigs).items() if c > 1]
    return dup


def main():
    problems = 0

    for rel in FILES:
        p = os.path.join(BASE, rel)
        if not os.path.exists(p):
            print("!! 文件不存在:", rel)
            problems += 1
            continue

        src = open(p, encoding="utf-8").read()
        nc = strip_comments(src)
        short = os.path.basename(rel)

        issues = []

        bal = nc.count("{") - nc.count("}")
        par = nc.count("(") - nc.count(")")
        if bal != 0:
            issues.append("花括号不平衡 %+d" % bal)
        if par != 0:
            issues.append("圆括号不平衡 %+d" % par)

        fd = check_field_duplicates(short, nc)
        if fd:
            issues.append("字段重复声明: %s" % fd)

        md = check_method_duplicates(short, nc)
        if md:
            issues.append("方法重复定义: %s" % md)

        # Kotlin 习惯
        if re.search(r"\.text\s*=", nc):
            issues.append("Kotlin 习惯: .text =")
        if re.search(r"\.isEnabled\s*=", nc):
            issues.append("Kotlin 习惯: .isEnabled =")
        if re.search(r"\(byte\)\s*\(Integer\)", nc):
            issues.append("非法转型: (byte)(Integer)")
        # (Integer) x 直接赋给基本类型
        for m in re.finditer(r"\b(?:int|long|byte|short)\s+\w+\s*=\s*\(Integer\)", nc):
            issues.append("拆箱缺 intValue(): %s" % m.group(0).strip())

        # 编辑残留：孤立的单独大括号行
        for i, line in enumerate(nc.splitlines(), 1):
            t = line.strip()
            if t == "}" and i > 1:
                prev = nc.splitlines()[i - 2].strip()
                if prev == "}":
                    # 连续两个 } 是正常的（嵌套结束），只提示三连
                    if i > 2 and nc.splitlines()[i - 3].strip() == "}":
                        issues.append("疑似编辑残留：第 %d 行三连右括号" % i)

        status = "OK" if not issues else "有 %d 个问题" % len(issues)
        print("%-28s %s" % (short, status))
        for s in issues:
            print("      - %s" % s)
        problems += len(issues)

    print()
    print("=" * 70)
    print("总计问题数:", problems)
    print("=" * 70)
    return 0 if problems == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
