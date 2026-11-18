# LSPosed Hook 方案设计｜抓取官方"声学"APP 的蓝牙命令原文

> 版本：v1（2026-10-03）
> 目标：拿到 `com.ugreen.iot` 发往 UGREEN HiTune T6 的 **8 字节命令原文**
> 约束：**只读手机，不用 root 修改任何东西**

---

## 1. 为什么必须走这条路

被动取证已经做到极限，三个死结：

```text
死结 1：ColorOS 的 bt_stack 文本日志【只记录 RX 方向】
        OPPO 只加了一个 send_data_to_app_dump 埋点，
        APP -> 耳机 方向没有任何 data_hex 记录。

死结 2：btsnoop 在这台机器上【不落盘】
        用户已开启"启用蓝牙HCI数据窥探日志套接字"，
        但全盘搜索无新建 *btsnoop* 文件，
        8872 端口也没有监听 socket，
        cached_hci/*.cfa 停在旧大小不增长。

死结 3：校验和无法从被动观察反推
        对 9 个真机包暴力枚举"求和"与"异或"的所有字节子集，
        命中 6/9 以上的公式【一个都没有】。
        而且存在三个"只有末字节不同、其余 7 字节全同"的包，
        直接排除简单线性校验。
```

```text
结论：必须进入 APP 进程内部，直接读它的 write() 参数。
```

---

## 2. 已经掌握的全部前置事实（Hook 实施所需）

```text
目标 APP      com.ugreen.iot        版本 2.5.2.924
APP uid       10564
目标设备      UGREEN HiTune T6      90:A0:BE:2D:5E:CA
传输方式      RFCOMM / SPP（不是 BLE GATT）
服务信道      SCN = 13
DLCI          27
L2CAP CID     0x0069（会随连接变化）
RFCOMM 头     5 字节
命令长度      恒为 8 字节（一次会话内 28 次写入全部为 8）
```

命令发出后的确定行为：

```text
APP 写 8 字节  →  31~63 ms 后
耳机回 9 字节  DD EE FF 08 01 01 [状态] [校验低] [校验高]
                 状态：01 = 游戏模式开  00 = 游戏模式关
```

---

## 3. Hook 点选择

### 主方案：Hook BluetoothSocket 的流

```kotlin
// 1) 拿到 socket 的输出流
android.bluetooth.BluetoothSocket.getOutputStream()
    → afterHookedMethod: 保存返回的 OutputStream 实例

// 2) 拦截实际写出
java.io.OutputStream.write(byte[])
java.io.OutputStream.write(byte[], int, int)
java.io.OutputStream.write(int)
    → beforeHookedMethod: 读取参数 byte[]，落盘
```

```text
优点：与 APP 内部实现解耦，不依赖任何混淆后的类名。
      只要它用标准 SPP socket，就一定能抓到。
```

### 辅助方案：Hook 输入流（用于交叉验证）

```kotlin
android.bluetooth.BluetoothSocket.getInputStream()
java.io.InputStream.read(byte[]) / read(byte[], int, int) / read()
    → afterHookedMethod: 读取填充后的 byte[]
```

```text
用途：验证 Hook 到的 RX 是否与我们在 BLE 侧收到的
      85 86 87 / DD EE FF 完全一致。
      一致 → 证明 Hook 覆盖到了正确的通道。
```

### 备用方案：Hook 私有封装类

如果主方案抓不到（例如 APP 用了 `BluetoothSocket` 之外的通道、
或走了 `ParcelFileDescriptor` 直写）：

```text
绿联 APK 里有这些线索（第一任静态分析结论）：
    UGREENEarConnectionController
    UGREENBleConnect
    UGREENDeviceConroller
    writeChar / writeCharacteristic / sendSppData / writeDataToSppDevice
    IBluetoothSpp / BluetoothSpp

→ 直接 hook 上述方法名，抓它们的入参 byte[]。
```

---

## 4. 过滤策略（避免日志被污染）

```text
只在同时满足以下条件时记录：
  1. 当前进程包名 == com.ugreen.iot
  2. socket 的远端地址 == 90:A0:BE:2D:5E:CA
     （通过 hook BluetoothSocket.getRemoteDevice() 获取）

过滤原因：手机同时连着 4 条蓝牙链路（handle 22/5/10/11），
          不过滤会混入 A2DP / HFP / GATT 等无关数据。
```

---

## 5. 落盘与导出

```text
落盘位置：模块自己的私有目录
          /data/data/<模块包名>/files/ugreen_protocol.log
          → 不需要任何存储权限，不碰系统分区

记录格式（每行一条，便于复制）：
    2026-10-03 15:00:20.399  TX  8B  85 86 87 04 01 ?? ?? ??
    2026-10-03 15:00:20.462  RX  9B  DD EE FF 08 01 01 01 23 15

导出方式：
  方式 A：模块自带 Activity，一个"复制全部"按钮（与现有测试 APP 一致）
  方式 B：直接 adb pull 模块私有目录（需 root，但只读）
  方式 C：模块把日志写到自己 ExternalFilesDir，用文件管理器分享
```

```text
推荐 A + B：
  A 让用户随时能复制；
  B 让我能直接用 adb 只读拉取，不占用用户时间。
```

---

## 6. 合规检查（对照用户的硬约束）

```text
★ 用户约束：不许用 root 修改手机任何东西，只能查看 ★

本方案逐条对照：
  ✅ 不修改任何系统分区 / 系统设置 / 系统属性
  ✅ 不 hook 系统进程（system_server / com.android.bluetooth 都不碰）
  ✅ 只在 com.ugreen.iot 这一个 APP 进程内运行
  ✅ 日志只写入模块自己的私有目录
  ✅ 不注入、不篡改 APP 的任何行为（只读参数副本，不改返回值）
  ⚠️ 唯一"写入"动作 = 在手机上安装一个新的 LSPosed 模块 APK
     → 这一点需要用户明确点头（属于安装应用，不是修改系统）
  ⚠️ 需要在 LSPosed 里把作用域勾选到 com.ugreen.iot
```

---

## 7. 实施步骤

```text
步骤 1  新建一个独立 Android 模块工程（或在现有工程里加一个 :xposed 模块）
        - 依赖 libxposed API（LSPosed 2.2.0 兼容）
        - 声明 xposedmodule 元数据

步骤 2  实现 Hook：
        - getOutputStream / OutputStream.write 系列
        - getInputStream  / InputStream.read 系列
        - getRemoteDevice 用于地址过滤

步骤 3  写一个极简 Activity：显示日志 + "复制全部" + "清空"

步骤 4  用户在 LSPosed 里启用模块、勾选 com.ugreen.iot、重启该 APP

步骤 5  用户操作：开游戏模式 → 关 → 开 → 关（各停 5 秒）

步骤 6  我读取日志，拿到 8 字节命令原文
        → 与已有的 9 字节应答做配对
        → 校验和公式立即可以标定（有已知明文了）
```

---

## 8. 预期收益

```text
一旦拿到 TX 原文，可以一次性解决：
  ✅ 命令的确切格式（8 字节里每一位是什么）
  ✅ 校验和算法（有明文+密文对，可直接反推）
  ✅ 游戏模式命令的完整字节
  ✅ 设备信息查询命令的完整字节
  ✅ 为 ANC 命令铺路（结构相同，只差操作码）
  ✅ 甚至可能直接看到 ANC 命令（如果 hook 期间用户在官方 APP 里切了 ANC）
```

```text
这一步做完，协议层就从"猜测"进入"完全确定"，
之后写 UgreenT6Protocol / UgreenT6Controller 才有意义。
```

---

## 9. 风险与回退

```text
风险 1：Hook 不到（APP 不用标准 BluetoothSocket）
  回退：改用备用方案，hook 绿联自己的 Spp 封装类方法名

风险 2：LSPosed 作用域没生效
  回退：确认模块元数据、重启 APP、查看 LSPosed 日志

风险 3：日志刷太多
  回退：只保留长度 8 和 9 的包（其余丢弃），并做时间窗口控制

风险 4：用户不愿装模块
  回退：退回"纯被动"，但那就只能停在当前进度，
        无法拿到 TX 原文 → 项目会卡住
```
