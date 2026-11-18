# 绿联耳机增强 (UGREEN for ColorOS)

> 让 **UGREEN HiTune T6** 在 **ColorOS** 上变成"系统认可的耳机"：
> 直接在**系统音量面板**和**设备空间**里切换降噪，**不需要打开任何第三方 APP**。

[![Platform](https://img.shields.io/badge/Platform-ColorOS%2016%2F17-1C8F6B)](#兼容性)
[![Framework](https://img.shields.io/badge/Framework-LSPosed-1C8F6B)](#安装)
[![License](https://img.shields.io/badge/License-GPL--3.0-1C8F6B)](LICENSE)

---

## 这是什么

绿联 HiTune T6 是一只素质不错、但**系统完全不认识**的耳机。ColorOS 不会为它显示降噪开关，音量面板里也什么都没有 —— 想切降噪只能打开绿联自己的 APP。

本项目通过 LSPosed 把这只耳机"接进"ColorOS：

```
长按音量键  →  降噪 / 通透 / 关闭        ✅
设备空间    →  点开 T6 → 切换降噪        ✅
两个入口状态实时同步                      ✅
不用打开任何 APP                          ✅
```

---

## 功能

| 功能 | 说明 |
|---|---|
| **音量面板降噪控制** | 长按音量键弹出的面板里出现降噪卡片，可切换「降噪 / 通透 / 关闭」 |
| **设备空间控制入口** | 「设备空间」里打开 T6 详情页，系统自带的「蓝牙音频设置」条目被接管为控制入口 |
| **两入口状态同步** | 在任一处切换，另一处立即同步（含列表项摘要文字） |
| **亮屏即时刷新** | 点完立刻变，不需要退出重进 |
| **独立工作** | 模块自己连接耳机发命令，**不需要任何配套 APP** |
| **自动重连** | 单耳取放导致的断链会自动恢复并重新订阅 |

### 界面效果

```
音量面板                                 设备空间
┌──────────────────────────┐            ┌──────────────────────────┐
│  UGREEN HiTune T6        │            │  UGREEN HiTune T6        │
│  降噪                     │            │  当前：降噪·点按切换降噪模式│
│  ○ 降噪  ○ 通透  ○ 关闭   │            └───────────┬──────────────┘
└──────────────────────────┘                        │ 点击
                                          ┌─────────▼──────────┐
                                          │  降噪模式           │
                                          │  ○ 降噪             │
                                          │  ○ 通透             │
                                          │  ○ 关闭             │
                                          └────────────────────┘
```

---

## 兼容性

| | 已验证 | 说明 |
|---|---|---|
| **耳机** | UGREEN HiTune T6 | 其它绿联型号协议可能不同 |
| **手机** | OnePlus 15 (PLK110) | 理论上所有 ColorOS / 一加设备通用 |
| **系统** | ColorOS 17 / Android 17 (API 37) | ColorOS 16 大概率也可用，未实测 |
| **框架** | LSPosed 2.2.0 | 需要 Root |

> ⚠️ 本项目依赖 ColorOS **内部实现细节**（ContentProvider 协议、系统界面结构）。
> 厂商更新后可能失效 —— 遇到问题请附上日志提 Issue。

---

## 安装

### 前置条件

- 已 Root，并装好 **LSPosed**
- 耳机已与手机完成**普通蓝牙配对**

### ⚠️ 第一步：填写你自己的耳机 MAC

**每只耳机的 MAC 都不同，不改这一步模块不会工作。**

打开下面这个文件，把 `DEFAULT_MAC` 换成你自己耳机的地址：

```
protocolhook/src/main/java/com/mo/ugreenprotocolhook/T6Config.java
```

```java
private static final String DEFAULT_MAC = "90:A0:BE:2D:5E:CA";   // ← 改这里
```

查看耳机 MAC 的方法：

```
设置 → 蓝牙 → 点耳机右侧的详情（i）→ 地址
或：adb shell dumpsys bluetooth_manager | grep -i hitune
```

改完用 Android Studio 编译出 APK（或 `./gradlew :protocolhook:assembleDebug`）。

### 接下来的步骤

```
1. 安装 APK

2. 在 LSPosed 里启用「绿联耳机增强」

3. ★ 勾选以下五个作用域

     ☑ com.android.bluetooth      蓝牙服务
     ☑ com.oplus.melody           「无线耳机」APP
     ☑ com.heytap.mydevices       「设备空间」APP
     ☑ com.heytap.accessory       「设备快连」APP
     ☑ com.ugreen.iot             绿联官方「声学」APP（可选）
                                   （不装官方 APP 的话，这一项自动跳过）

4. 重启手机

5. ★ 第一次装好后，手动打开一次模块 APP
     （原因：Android 会把"刚安装的应用"标记为 stopped 状态，
       打开一次即可解除，之后重启都不用手动打开了）

6. 连接耳机，长按音量键 → 应该能看到降噪选项
```

### 为什么刚装上要先打开一次

不是 Bug，是 Android 的既定行为：新安装或刚更新过的应用处于 `stopped` 状态，收不到开机广播。打开一次就永久解除。

---

## 使用

```text
切降噪：
    长按音量键 → 点降噪 / 通透 / 关闭
    或：设备空间 → 点开 T6 → 点入口 → 选模式

看当前状态：
    音量面板的标签就是当前模式
    设备空间的条目摘要也会显示当前模式
```

### 模式对应关系

| 系统显示 | 耳机实际档位 |
|---|---|
| 降噪 | 降噪**深度**（最强档） |
| 通透 | 环境音 |
| 关闭 | 降噪关 |

> 耳机本身有 5 档（关 / 深度 / 中度 / 轻度 / 环境音），但 ColorOS 只提供 3 个位置。
> 其余档位目前未接入系统 UI。

---

## 工作原理

```text
用户点降噪
    ↓
ColorOS 音量面板（SystemUI）
    ↓ 查询 ContentProvider
「无线耳机」App 的 EarphoneControlProvider
    ↓ ★ 模块在这里拦截
    │   · query() 返回伪造数据（把 T6 伪装成"支持降噪的耳机"）
    │   · call()  接收用户的切换请求
    ↓
模块自带的 BLE 客户端（连接 → 发现 → 写入 → 断开）
    ↓
UGREEN T6 耳机（私有协议，8 字节命令帧）
```

**设备空间**走另一条路：接管系统蓝牙详情页里自带的「蓝牙音频设置」条目，把它改造成控制入口。

**两个入口通过广播同步状态**，所以在哪里切换效果都一样。

### 涉及的作用域与用途

| 作用域 | 用途 |
|---|---|
| `com.oplus.melody` | 音量面板降噪卡片的数据源 |
| `com.heytap.mydevices` | 蓝牙详情页的条目接管 |
| `com.android.bluetooth` | ColorOS 三方耳机电量通道（早期工作，现保留） |
| `com.heytap.accessory` | 开盖弹窗（**未生效**，见下方"已知限制"） |
| `com.ugreen.iot` | 协议取证（可选） |

---

## 从源码构建

```bash
# 需要 Android SDK（compileSdk 37）与 JDK 17+
./gradlew :protocolhook:assembleDebug     # 模块
./gradlew :app:assembleDebug              # 调试台（可选）
```

产物路径：

```
protocolhook/build/outputs/apk/debug/protocolhook-debug.apk
app/build/outputs/apk/debug/app-debug.apk
```

> 构建前请确认已经改好 `T6Config.java` 里的 MAC（见上方安装说明）。

---

## 目录结构

```
protocolhook/                   LSPosed 模块
  src/main/java/.../
    HookEntry.java              作用域分发入口
    T6Config.java          ★  单点配置（MAC / UUID）
    T6BleSender.java       ★  模块自带 BLE 客户端
    AncCommands.java       ★  T6 私有命令表
    MelodyProviderHook.java★  音量面板数据注入
    MyDevicesHook.java     ★  「设备空间」条目接管
    AccessoryHook.java         开盖弹窗探测（未生效）
    ColorOsBatteryBridge.java  电量通道（早期工作）
    ...

app/                            调试台（可选安装）
  MainActivity.kt               扫描 / 连接 / 订阅 / 发命令 / 看日志
  UgreenT6Gatt.kt               BLE 连接管理
  T6Service.kt                  前台服务

docs/                           逆向与设计笔记（中文）
tools/                          Python 分析脚本
```

---

## 已知限制

| 限制 | 说明 |
|---|---|
| **切换有约 1.5 秒延迟** | 采用短连接（用完即断），换来的是两个系统进程不会抢耳机连接 |
| **开盖连接弹窗未实现** | ColorOS 的"设备快连"弹窗是为**未配对的新设备**设计的，已配对的 T6 不会触发；且其触发条件是系统/云端策略，无法修改 |
| **只有 3 个降噪档位** | ColorOS 只提供关/降噪/通透三个位置，耳机的"中度""轻度"未接入 |
| **分耳电量未接入** | 系统基础电量通道只有单个整数 |
| **仅测试了一款耳机** | 协议是 T6 专有的，其它型号需重新逆向 |

---

## 常见问题

**Q：装了没效果？**
```
① 确认 LSPosed 里五个作用域都勾了（尤其是 com.oplus.melody 和 com.heytap.mydevices）
② 重启手机
③ 手动打开一次模块 APP（解除 stopped 状态）
④ 确认耳机已连接
```

**Q：音量面板有降噪卡片，但点了没反应？**
```
说明数据注入成功、但 BLE 发送失败。
用 adb 看日志：logcat | grep UGREEN-BLE
常见原因：耳机已被其它设备的蓝牙连接占用
```

**Q：设备空间的条目出现了，但摘要不更新？**
```
确认模块版本较新（摘要刷新是后加的功能），然后重启「设备空间」App。
```

**Q：重启后又要手动打开一次模块 APP？**
```
第一次装好后打开一次即可，之后重启都不用。
若仍然需要，检查系统是否限制了这个 App 的后台运行。
```

**Q：能用在别的绿联耳机上吗？**
```
大概率不行。命令帧里的校验字节算法未知（采用了"抄现成值"方案），
每个型号的厂商自定义数据都可能不同。
```

---

## 反馈问题

请附上日志，否则很难定位：

```
模块 APP 界面 →「复制完整日志」

或用 adb 抓关键日志：
    logcat -d | grep -E "UGREEN-BLE|UGREEN-T6-SERVICE"
```

---

## 致谢

- **[HuaweiPods](https://github.com/artifical0/HuaweiPods)** —— 「设备空间」条目接管的思路来自这个项目，本项目照搬了它的成熟做法（包括"反射扫描 MAC 字段"和"直接改 itemView 文字"这两个关键技巧）。

---

## 免责声明

```
· 本项目仅供个人在自己的设备上使用
· 所有蓝牙协议分析均基于自己购买的设备，未绕过任何付费功能
· 使用本模块需要 Root 与 LSPosed，请自行评估风险
· 因使用本模块造成的任何后果由使用者自行承担
```

---

## 许可证

[GPL-3.0](LICENSE)
