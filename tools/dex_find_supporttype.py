# -*- coding: utf-8 -*-
"""
从 dex 字节码里找 EarphoneController 对 supportType 字段的写入。

为什么需要它：
    jadx 反编译 EarphoneController 时出错（有空的 if 块），
    导致"填充 supportType"的关键逻辑丢失。
    直接读字节码能拿到确定的答案，不依赖反编译器。

方法：定位类 → 找方法 → 找 iput 指令（写字段）及其前后的字符串常量。
"""

import re
import sys
import zipfile
import struct

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

APK = r"D:\AndroidStudio\UGREENforColorOS\tools\report\SystemUI.apk"
TARGET_CLASS = "Lcom/oplus/systemui/qs/slider/detail/data/EarphoneController;"

# 先试哪个 dex 有（之前已知 classes4.dex）
z = zipfile.ZipFile(APK)
dex_name = None
for n in z.namelist():
    if n.endswith(".dex"):
        if TARGET_CLASS.encode() in z.read(n):
            dex_name = n
            break

if not dex_name:
    print("找不到目标类")
    sys.exit(1)

print("目标类在:", dex_name)
data = z.read(dex_name)
print("dex 大小:", len(data))


def uleb128(buf, off):
    result = 0
    shift = 0
    while True:
        b = buf[off]
        off += 1
        result |= (b & 0x7F) << shift
        if (b & 0x80) == 0:
            break
        shift += 7
    return result, off


# 解析 dex 头
string_ids_size, string_ids_off = struct.unpack_from("<II", data, 0x38)
type_ids_size, type_ids_off = struct.unpack_from("<II", data, 0x40)
proto_ids_size, proto_ids_off = struct.unpack_from("<II", data, 0x48)
field_ids_size, field_ids_off = struct.unpack_from("<II", data, 0x50)
method_ids_size, method_ids_off = struct.unpack_from("<II", data, 0x58)
class_defs_size, class_defs_off = struct.unpack_from("<II", data, 0x60)

print("string_ids=%d type_ids=%d field_ids=%d method_ids=%d class_defs=%d"
      % (string_ids_size, type_ids_size, field_ids_size, method_ids_size, class_defs_size))


def get_string(idx):
    off = struct.unpack_from("<I", data, string_ids_off + idx * 4)[0]
    size, p = uleb128(data, off)
    end = data.index(b"\x00", p)
    return data[p:end].decode("utf-8", "replace")


def get_type(idx):
    return get_string(struct.unpack_from("<I", data, type_ids_off + idx * 4)[0])


def get_field(idx):
    cls, typ, name = struct.unpack_from("<HHI", data, field_ids_off + idx * 8)
    return get_type(cls), get_type(typ), get_string(name)


# 找目标类的 class_def
target_type_idx = None
for i in range(type_ids_size):
    if get_type(i) == TARGET_CLASS:
        target_type_idx = i
        break

if target_type_idx is None:
    print("类型未在 type_ids 中")
    sys.exit(1)

print("目标 type_idx =", target_type_idx)

class_def = None
for i in range(class_defs_size):
    off = class_defs_off + i * 32
    t = struct.unpack_from("<I", data, off)[0]
    if t == target_type_idx:
        class_def = off
        break

if class_def is None:
    print("class_def 未找到")
    sys.exit(1)

class_data_off = struct.unpack_from("<I", data, class_def + 24)[0]
print("class_data_off =", class_data_off)

p = class_data_off
static_fields_size, p = uleb128(data, p)
instance_fields_size, p = uleb128(data, p)
direct_methods_size, p = uleb128(data, p)
virtual_methods_size, p = uleb128(data, p)
print("实例字段 %d，虚方法 %d" % (instance_fields_size, virtual_methods_size))

# 跳过 static fields
for _ in range(static_fields_size):
    _, p = uleb128(data, p)
    _, p = uleb128(data, p)

# 读实例字段
inst_fields = []
for _ in range(instance_fields_size):
    idx_diff, p = uleb128(data, p)
    acc, p = uleb128(data, p)
    inst_fields.append(idx_diff)

print()
print("=== 实例字段（找 supportType）===")
# 实例字段的 field_idx 是累加的
cur = 0
for i, d in enumerate(inst_fields):
    cur = d if i == 0 else cur + d
    try:
        cls, typ, name = get_field(cur)
        mark = "  ★" if name == "supportType" or "support" in name.lower() else ""
        print("   %-2d %-26s %-40s%s" % (i, name, typ, mark))
    except Exception as e:
        print("   %-2d <err %s>" % (i, e))

# 跳过 direct methods
for _ in range(direct_methods_size):
    _, p = uleb128(data, p)
    _, p = uleb128(data, p)
    _, p = uleb128(data, p)

print()
print("=== 虚方法里搜 iput 指令（写字段）===")
cur_m = 0
for mi in range(virtual_methods_size):
    idx_diff, p = uleb128(data, p)
    acc, p = uleb128(data, p)
    code_off, p = uleb128(data, p)
    cur_m = idx_diff if mi == 0 else cur_m + idx_diff

    try:
        mcls, mproto, mname = None, None, None
        mo = method_ids_off + cur_m * 8
        cls_idx, proto_idx, name_idx = struct.unpack_from("<HHI", data, mo)
        mname = get_string(name_idx)
    except Exception:
        continue

    if code_off == 0:
        continue

    insns_size = struct.unpack_from("<I", data, code_off + 12)[0]
    insns_start = code_off + 16
    insns = data[insns_start:insns_start + insns_size * 2]

    # 搜 iput (opcode 0x5b) / iput-object (0x5c)
    hits = []
    for k in range(0, len(insns) - 1, 2):
        op = insns[k]
        if op in (0x5B, 0x5C):
            field_idx = struct.unpack_from("<H", insns, k + 2)[0]
            try:
                fcls, ftyp, fname = get_field(field_idx)
                hits.append((op, fname, ftyp))
            except Exception:
                pass

    if hits:
        print("  %-30s -> %s" % (mname, hits[:6]))
