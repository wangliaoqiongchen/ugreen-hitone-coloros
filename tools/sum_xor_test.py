# -*- coding: utf-8 -*-
"""
测试新假设：末字节 = (前n-1字节求和的低8位) XOR (前n-1字节异或) XOR 0x80

纪律：全部程序计算，不手算，不提前下结论。
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


def xor_all(bs):
    v = 0
    for x in bs:
        v ^= x
    return v & 0xFF


def test(samples, title):
    print('=' * 100)
    print(title)
    print('=' * 100)
    print('%-8s %-26s %-6s %-8s %-8s %-8s %s'
          % ('标签', 'HEX', '末字节', 'sum低8', 'xor', '预测', '结果'))
    ok = 0
    for label, hx in samples:
        b = bytes.fromhex(hx)
        head = b[:-1]
        s = sum(head) & 0xFF
        x = xor_all(head)
        for name, pred in (
                ('sum^xor^0x80', (s ^ x ^ 0x80) & 0xFF),
                ('sum^xor', (s ^ x) & 0xFF),
                ('sum^xor^0xFF', (s ^ x ^ 0xFF) & 0xFF),
        ):
            if pred == b[-1]:
                print('%-8s %-26s %02X     %02X       %02X       %02X     OK  [%s]'
                      % (label, hx, b[-1], s, x, pred, name))
                ok += 1
                break
        else:
            print('%-8s %-26s %02X     %02X       %02X       --      XX'
                  % (label, hx, b[-1], s, x))
    print('通过: %d/%d' % (ok, len(samples)))
    print()


test(TX, 'TX 命令')
test(RX, 'RX 应答')
