# -*- coding: utf-8 -*-
"""
方法级作用域审计。

为什么需要它：
    之前出现过 HookEntry 里 afterHookedMethod 引用了
    lpparam（它是外层 installSystemBridge 的参数，
    不在 hookAdapterProperties 的作用域内）——
    结构性审计查不出来，只有编译才报错。

本脚本做一件具体的事：
    对每个方法，收集【它自己能看到的变量名】
    （参数 + 局部声明 + 本类的字段 + 外层方法参数若是匿名类的宿主），
    然后找出方法体里引用到的、却不在该集合中的"疑似外部变量"。

这是一套刻意保守的启发式：只报"高置信度"的未定义引用，
宁可漏报也不刷屏。
"""

import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

BASE = r"D:\AndroidStudio\UGREENforColorOS\protocolhook\src\main\java\com\mo\ugreenprotocolhook"

FILES = [
    "HookEntry.java",
    "ColorOsBatteryBridge.java",
    "SystemBridgeLog.java",
    "AncDiscovery.java",
    "AncDeclare.java",
    "ProtocolLog.java",
]

# Java 关键字与常见类型/常量，不算变量
JAVA_WORDS = set("""
abstract assert boolean break byte case catch char class const continue default do double
else enum extends final finally float for goto if implements import instanceof int interface
long native new package private protected public return short static strictfp super switch
synchronized this throw throws transient try void volatile while true false null
String Integer Boolean Object Long Byte Short Float Double Character Void
Class Method Field Throwable Exception RuntimeException Error Override Deprecated
System Math Arrays Collections List ArrayList Map HashMap Set HashSet
File FileOutputStream FileReader BufferedReader StringBuilder Thread
BluetoothDevice BluetoothAdapter Bundle Intent Context Log
""".split())


def strip_comments(src):
    s = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    s = re.sub(r"//[^\n]*", "", s)
    return s


def find_method_bodies(src):
    """
    粗略找出方法体：从 '签名 {' 到匹配的 '}'。
    返回 [(名字, 起止行, 方法体文本, 参数文本)]
    """
    results = []
    # 方法签名：修饰符... 返回类型 名字(参数) {
    pat = re.compile(
        r"(?:private|public|protected|static|final|synchronized|\s)+"
        r"[\w<>\[\],.?]+\s+(\w+)\s*\(([^)]*)\)\s*\{")

    for m in pat.finditer(src):
        name = m.group(1)
        if name in ("if", "for", "while", "switch", "catch", "synchronized"):
            continue
        start = m.end() - 1  # 指向 '{'
        depth = 0
        i = start
        while i < len(src):
            if src[i] == "{":
                depth += 1
            elif src[i] == "}":
                depth -= 1
                if depth == 0:
                    break
            i += 1
        body = src[start:i + 1]
        line_no = src[:m.start()].count("\n") + 1
        results.append((name, line_no, body, m.group(2)))
    return results


def declared_in(body):
    """收集方法体内声明的局部变量名。"""
    names = set()
    # 类型 名字 = / 类型 名字 ; / 类型 名字 ,
    pat = re.compile(
        r"\b(?:final\s+)?[\w<>\[\],.?]+\s+(\w+)\s*(?:=|;|,|\))")
    for m in pat.finditer(body):
        n = m.group(1)
        if n not in JAVA_WORDS:
            names.add(n)
    return names


def main():
    problems = 0

    for fname in FILES:
        p = os.path.join(BASE, fname)
        if not os.path.exists(p):
            continue
        raw = open(p, encoding="utf-8").read()
        src = strip_comments(raw)

        # 本类字段
        fields = set(re.findall(
            r"^    (?:private|public|protected|static|final|volatile)"
            r"[\w\s]*?\s([A-Za-z_]\w*)\s*(?:=|;)", src, re.M))

        # 本类方法名
        methods = set(re.findall(
            r"^\s{4}(?:private|public|protected|static|final|synchronized)"
            r"[\w\s<>\[\],.]*?\s(\w+)\s*\(", src, re.M))

        print("=== %s ===" % fname)
        print("    字段 %d 个，方法 %d 个" % (len(fields), len(methods)))

        # 收集所有方法的参数名（供匿名类跨层引用判断）
        all_method_params = {}
        for name, line, body, params in find_method_bodies(src):
            ps = set()
            for prm in params.split(","):
                prm = prm.strip()
                if not prm:
                    continue
                mm = re.search(r"(\w+)\s*$", prm)
                if mm:
                    ps.add(mm.group(1))
            all_method_params.setdefault(name, set()).update(ps)

        for name, line, body, params in find_method_bodies(src):
            # 该方法可见的变量
            visible = set(fields) | set(methods) | JAVA_WORDS
            for prm in params.split(","):
                prm = prm.strip()
                if prm:
                    mm = re.search(r"(\w+)\s*$", prm)
                    if mm:
                        visible.add(mm.group(1))
            visible |= declared_in(body)

            # 找出该方法里所有"像变量"的标识符
            idents = set(re.findall(r"\b([a-z][A-Za-z0-9_]{2,})\b", body))

            # 过滤掉明显是方法调用的（后面跟 '('）
            calls = set(re.findall(r"\b([a-z][A-Za-z0-9_]{2,})\s*\(", body))
            suspicious = idents - calls - visible

            # 再过滤掉常见的、可能来自外层但无法判定的名字
            allow = {"args", "param", "obj", "msg", "target", "instance",
                     "levels", "line", "lines", "text", "value", "values",
                     "left", "right", "path", "paths", "name", "names",
                     "size", "count", "index", "idx", "data", "result"}
            suspicious -= allow

            if suspicious:
                print("    L%-5d %s(): 疑似未定义引用 -> %s"
                      % (line, name, sorted(suspicious)))
                problems += 1

        print()

    print("=" * 64)
    print("疑似问题数:", problems)
    print("=" * 64)
    return 0


if __name__ == "__main__":
    sys.exit(main())
