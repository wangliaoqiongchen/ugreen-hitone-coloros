# -*- coding: utf-8 -*-
"""
暴力反推校验和：对每个包，找出"哪个前缀求和能得到末字节"。

注意：前一阶段多次心算错误，本脚本一律用程序计算，不手算。
"""

TX = [
    'AABBCC0401003191',
    'AABBCC0101002190',
    'AABBCC0901A161EA',
    'AABBCC0901A0A02A',
    'AABBCC0901A221EB',
    'AABBCC0801013052',
    'AABBCC080100F192',
]

RX = [
    '858687050701020200',
    '8586870401646446',
    'DDEEFF040113646446A200000000010507080104060801000009AF',
    'DDEEFF0101060003040003045324',
    'DDEEFF0D01110101E4B880E58AA020313520FD7C6D42E01748',
    'DDEEFF090101A17DD6',
    'DDEEFF090101A05CC6',
    'DDEEFF090101A21EE6',
    'DDEEFF080101012315',
    'DDEEFF080101000205',
]


def search(pkts, name):
    P = [bytes.fromhex(h) for h in pkts]
    print('=== %s ===' % name)

    for b in P:
        hits = [k for k in range(1, len(b))
                if (sum(b[:k]) & 0xFF) == b[-1]]
        s7 = sum(b[:len(b) - 1]) & 0xFF
        print('  %-58s last=%02X  sum(0..n-2)=%02X  匹配前缀长度=%s'
              % (b.hex().upper(), b[-1], s7, hits if hits else '无'))

    common = None
    for b in P:
        ks = {k for k in range(1, len(b)) if (sum(b[:k]) & 0xFF) == b[-1]}
        common = ks if common is None else (common & ks)
    print('  对所有包都成立的 k = %s' % (sorted(common) if common else '无'))
    print()


def search_xor(pkts, name):
    P = [bytes.fromhex(h) for h in pkts]
    print('=== %s (XOR) ===' % name)
    common = None
    for b in P:
        hs = []
        for k in range(1, len(b)):
            v = 0
            for x in b[:k]:
                v ^= x
            if v == b[-1]:
                hs.append(k)
        common = set(hs) if common is None else (common & set(hs))
        print('  %-58s last=%02X  XOR 匹配前缀长度=%s'
              % (b.hex().upper(), b[-1], hs if hs else '无'))
    print('  对所有包都成立的 k = %s' % (sorted(common) if common else '无'))
    print()


if __name__ == '__main__':
    search(TX, 'TX 8字节命令 SUM')
    search(RX, 'RX 应答 SUM')
    search_xor(TX, 'TX 8字节命令')
    search_xor(RX, 'RX 应答')
