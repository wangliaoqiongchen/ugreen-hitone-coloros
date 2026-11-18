# -*- coding: utf-8 -*-
"""
把完整求和值和末字节并排打印，观察真实关系。

纪律：只认脚本输出的数字，不做任何心算。
"""

TX = [
    ('查询',   'AABBCC0401003191'),
    ('心跳',   'AABBCC0101002190'),
    ('降噪开', 'AABBCC0901A161EA'),
    ('降噪关', 'AABBCC0901A0A02A'),
    ('环境音', 'AABBCC0901A221EB'),
    ('高',     'AABBCC0901B16026'),
    ('中',     'AABBCC0901C0A002'),
    ('低',     'AABBCC0901C161C2'),
    ('通透',   'AABBCC0901C221C3'),
    ('游戏开', 'AABBCC0801013052'),
    ('游戏关', 'AABBCC080100F192'),
]

RX = [
    ('状态',   '8586870401646445'),
    ('连上',   '858687050701020200'),
    ('14B',    'DDEEFF0101060003040003045324'),
    ('09-A1',  'DDEEFF090101A17DD6'),
    ('09-B1',  'DDEEFF090101B14CC4'),
    ('09-C0',  'DDEEFF090101C0FAAA'),
    ('09-C1',  'DDEEFF090101C1DBBA'),
    ('09-C2',  'DDEEFF090101C2B88A'),
    ('08-on',  'DDEEFF080101012315'),
    ('08-off', 'DDEEFF080101000205'),
]


def dump(samples, title):
    print('=' * 96)
    print(title)
    print('=' * 96)
    print('%-8s %-24s %-8s %-10s %-10s %s'
          % ('标签', 'HEX', '末字节', '前n-1求和', '低8位', '差值(末-和)'))
    for label, hx in samples:
        b = bytes.fromhex(hx)
        total = sum(b[:-1])
        low = total & 0xFF
        diff = (b[-1] - low) & 0xFF
        print('%-8s %-24s %02X       %-10X %02X       %02X'
              % (label, hx, b[-1], total, low, diff))
    print()


dump(TX, 'TX 命令')
dump(RX, 'RX 应答')

print('=' * 96)
print('观察：末字节与 低8位 的差值是否固定？')
print('=' * 96)
for name, samples in (('TX', TX), ('RX', RX)):
    diffs = []
    for label, hx in samples:
        b = bytes.fromhex(hx)
        diffs.append((b[-1] - (sum(b[:-1]) & 0xFF)) & 0xFF)
    print('%s 差值集合: %s' % (name, sorted(set(diffs))))
    print('   是否全部相同: %s' % (len(set(diffs)) == 1))
