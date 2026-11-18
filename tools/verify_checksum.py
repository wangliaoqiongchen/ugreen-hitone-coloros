# -*- coding: utf-8 -*-
"""
验证校验和规律：末字节 = 前面所有字节求和 & 0xFF

用用户提供的全部真机样本交叉验证。
一律程序计算，不手算。
"""

TX = [
    ('查询',        'AABBCC0401003191'),
    ('心跳',        'AABBCC0101002190'),
    ('降噪开(旧)',  'AABBCC0901A161EA'),
    ('降噪关(旧)',  'AABBCC0901A0A02A'),
    ('环境音(旧)',  'AABBCC0901A221EB'),
    ('游戏开',      'AABBCC0801013052'),
    ('游戏关',      'AABBCC080100F192'),
    ('降噪开',      'AABBCC0901A161EA'),
    ('降噪高',      'AABBCC0901B16026'),
    ('降噪低',      'AABBCC0901C161C2'),
    ('降噪中',      'AABBCC0901C0A002'),
    ('通透',        'AABBCC0901C221C3'),
]

RX = [
    ('状态8B',   '8586870401646445'),
    ('连上9B',   '858687050701020200'),
    ('14B',      'DDEEFF0101060003040003045324'),
    ('09-A1',    'DDEEFF090101A17DD6'),
    ('09-A0',    'DDEEFF090101A05CC6'),
    ('09-B1',    'DDEEFF090101B14CC4'),
    ('09-C1',    'DDEEFF090101C1DBBA'),
    ('09-C0',    'DDEEFF090101C0FAAA'),
    ('09-C2',    'DDEEFF090101C2B88A'),
    ('08-on',    'DDEEFF080101012315'),
    ('08-off',   'DDEEFF080101000205'),
]


def check(label, hx):
    b = bytes.fromhex(hx)
    calc = sum(b[:-1]) & 0xFF
    ok = (calc == b[-1])
    print('  %-12s %-24s last=%02X  calc=%02X  %s'
          % (label, hx, b[-1], calc, 'OK' if ok else '<<< XX'))
    return ok


print('=' * 74)
print('TX 命令（8 字节）')
print('=' * 74)
tx_ok = sum(1 for l, h in TX if check(l, h))

print()
print('=' * 74)
print('RX 应答（取末字节为校验，前面全部参与求和）')
print('=' * 74)
rx_ok = sum(1 for l, h in RX if check(l, h))

print()
print('TX: %d/%d 通过' % (tx_ok, len(TX)))
print('RX: %d/%d 通过' % (rx_ok, len(RX)))

print()
print('=' * 74)
print('长帧内部还嵌了一个子校验（以 27 字节帧为例）')
print('=' * 74)
long_frames = [
    'DDEEFF040113646445A000000000010507080104060801000046DE',
    'DDEEFF040113646445A10000000001050708010406080100006276',
    'DDEEFF040113646445C20000000001050708010406080100001354',
]
for hx in long_frames:
    b = bytes.fromhex(hx)
    inner = sum(b[0:8]) & 0xFF
    outer = sum(b[0:len(b) - 1]) & 0xFF
    print('  %s' % hx)
    print('     b[8] =%02X  前8字节和低8位 =%02X  %s'
          % (b[8], inner, 'OK' if inner == b[8] else 'XX'))
    print('     b[26]=%02X  前26字节和低8位=%02X  %s'
          % (b[26], outer, 'OK' if outer == b[26] else 'XX'))
    print()
