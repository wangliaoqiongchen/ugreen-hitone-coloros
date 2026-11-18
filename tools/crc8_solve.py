# -*- coding: utf-8 -*-
"""
暴力搜索 CRC-8 家族，判断这些包的末字节是不是某种 CRC-8。

搜索空间：
    多项式     : 所有奇数 0x01..0xFF（CRC-8 多项式必为奇数）
    初值 init  : 0x00 / 0xFF / 0x01 ...
    反射       : 输入反射、输出反射 各 on/off
    输出异或   : 0x00 / 0xFF

对每个候选参数，检查能否让【所有】样本的末字节都被算出来。

一律用程序计算，不手算。
"""

# (标签, 完整包 hex)
TX = [
    ('04查询', 'AABBCC0401003191'),
    ('01心跳', 'AABBCC0101002190'),
    ('09-A1', 'AABBCC0901A161EA'),
    ('09-A0', 'AABBCC0901A0A02A'),
    ('09-A2', 'AABBCC0901A221EB'),
    ('08-01', 'AABBCC0801013052'),
    ('08-00', 'AABBCC080100F192'),
]

RX = [
    ('05状态', '858687050701020200'),
    ('状态8B', '8586870401646446'),
    ('27B', 'DDEEFF040113646446A200000000010507080104060801000009AF'),
    ('14B', 'DDEEFF0101060003040003045324'),
    ('25B', 'DDEEFF0D01110101E4B880E58AA020313520FD7C6D42E01748'),
    ('09-A1', 'DDEEFF090101A17DD6'),
    ('09-A0', 'DDEEFF090101A05CC6'),
    ('09-A2', 'DDEEFF090101A21EE6'),
    ('08-on', 'DDEEFF080101012315'),
    ('08-off', 'DDEEFF080101000205'),
]


def reflect8(v):
    r = 0
    for _ in range(8):
        r = (r << 1) | (v & 1)
        v >>= 1
    return r & 0xFF


def crc8(data, poly, init, refin, refout, xorout):
    crc = init & 0xFF
    for b in data:
        if refin:
            b = reflect8(b)
        crc ^= b
        for _ in range(8):
            if crc & 0x80:
                crc = ((crc << 1) ^ poly) & 0xFF
            else:
                crc = (crc << 1) & 0xFF
    if refout:
        crc = reflect8(crc)
    return (crc ^ xorout) & 0xFF


def solve(samples, name, tail_len):
    """
    tail_len = 末尾几个字节当作校验（1 或 2）。
    样本数据 = 除校验外的全部字节。
    """
    print('=== %s （校验占末尾 %d 字节）===' % (name, tail_len))
    P = []
    for label, hx in samples:
        b = bytes.fromhex(hx)
        P.append((label, b[:-tail_len], b[-tail_len:]))

    found = []
    for poly in range(1, 256, 2):
        for init in (0x00, 0xFF):
            for refin in (False, True):
                for refout in (False, True):
                    for xorout in (0x00, 0xFF):
                        ok = True
                        for label, data, tail in P:
                            if tail_len == 1:
                                if crc8(data, poly, init, refin, refout, xorout) != tail[0]:
                                    ok = False
                                    break
                            else:
                                hi = crc8(data, poly, init, refin, refout, xorout)
                                # 两字节校验：常见是 (crc, crc^0xFF) 或 大端/小端
                                if not (tail[0] == hi or tail[1] == hi):
                                    ok = False
                                    break
                        if ok:
                            found.append((poly, init, refin, refout, xorout))

    if not found:
        print('  没有任何 CRC-8 参数组合能命中全部样本')
    else:
        for f in found[:10]:
            print('  命中: poly=0x%02X init=0x%02X refin=%s refout=%s xorout=0x%02X' % f)
    print()


def solve_simple(samples, name):
    """
    再试几种"非 CRC"的朴素变体：
      · 末字节 = 前 n-1 字节和（已试过，这里再确认）
      · 末字节 = 0x80 | (和 & 0x7F)
      · 末字节 = -和（补码）
      · 末字节 = 和 ^ 0xFF
    """
    print('=== %s （朴素变体）===' % name)
    variants = {
        'sum&0xFF': lambda b: sum(b[:-1]) & 0xFF,
        '0x80|(sum&0x7F)': lambda b: 0x80 | (sum(b[:-1]) & 0x7F),
        '(-sum)&0xFF': lambda b: (-sum(b[:-1])) & 0xFF,
        'sum^0xFF': lambda b: (sum(b[:-1]) ^ 0xFF) & 0xFF,
        'xor all': lambda b: _xor(b[:-1]),
    }
    for vname, fn in variants.items():
        hits = sum(1 for _, hx in samples if fn(bytes.fromhex(hx)) == bytes.fromhex(hx)[-1])
        print('  %-18s 命中 %d/%d' % (vname, hits, len(samples)))
    print()


def _xor(bs):
    v = 0
    for x in bs:
        v ^= x
    return v & 0xFF


if __name__ == '__main__':
    for tail in (1, 2):
        solve(TX, 'TX 命令', tail)
        solve(RX, 'RX 应答', tail)
    solve_simple(TX, 'TX 命令')
    solve_simple(RX, 'RX 应答')
