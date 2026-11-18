# HuaweiPods → ColorOS 系统桥：机制拆解与我们复用的方案

> 目的：搞清楚 HuaweiPods 是怎么让耳机出现在 ColorOS 系统界面里的，
> 以及电量、控制入口分别走哪条路。
> 来源：反编译 `HuaweiPods-ColorOS-1.8.3-coloros.1.apk`（jest 2327 个类）

---

## 1. 总体结论（一句话）

```text
耳机【不需要】被"注册"成系统设备。
它靠普通的蓝牙配对出现在系统里，
HuaweiPods 做的事只有三件：

  1. 电量 —— 把电量值推进 ColorOS 的蓝牙服务（com.android.bluetooth）
  2. 显示 —— 在"我的设备"等列表里改文字、加图标
  3. 入口 —— 点条目时打开自己的界面
```

```text
[重要] 这意味着我们的工作量和复杂度都比预想低：
       不需要造设备、不需要改系统蓝牙栈的数据结构，
       只需要"喂电量 + 改显示 + 放入口"。
```

---

## 2. 电量桥（最关键的一条）

### 2.1 代码位置

`defpackage/cp.java` —— 类名被混淆，但内部字符串暴露了它的身份：

```text
"HuaweiPods-ColorOsBattery"                 ← 它自己的日志 TAG
"ColorOS earphone battery channel unavailable; using Android battery only"
"Android battery level update failed level="
```

### 2.2 获取 ColorOS 的三方耳机接口

```java
// cp.b(context)
ClassLoader cl = context.getClassLoader();

Class<?> remoteDevice = Class.forName(
    "com.oplus.bluetooth.common.interfaces.IOplusRemoteDevice", false, cl);

Object feature =
    Class.forName("com.oplus.bluetooth.common.OplusFeatureCache", false, cl)
        .getMethod("get",
            Class.forName("com.oplus.bluetooth.common.IOplusCommonFeature", false, cl))
        .invoke(null, remoteDevice.getField("DEFAULT").get(null));

int transport = remoteDevice.getField("TRANSPORT_HFP_AT").getInt(null);
```

```text
[健壮性写法，值得学习]
  · 按【接口全名】Class.forName 取，不是硬编码 class 引用
  · 按【字段名】getField("DEFAULT") / getField("TRANSPORT_HFP_AT") 取常量，
    不是写死数值
  → 系统升级时如果常量值变了，这里依然正确
[容错] 整段包在 try/catch 里；
       失败就降级为"只用 Android 原生电量"，并打日志，不崩溃。
```

### 2.3 真正推送电量的调用

```java
// cp.c(context, bluetoothDevice, level)
Method m = a(adapterService.getClass(),
             "setBatteryLevel",
             BluetoothDevice.class, int.class, boolean.class);

m.invoke(adapterService, bluetoothDevice, level, Boolean.FALSE);
```

```text
[关键] 是调用系统蓝牙服务 AdapterService 的 setBatteryLevel(device, level, false)。
       也就是说模块必须【运行在 com.android.bluetooth 进程里】才拿得到它。
```

### 2.4 怎么拿到 AdapterService（三级兜底）

```text
第 1 级：如果当前 context 本身就是 AdapterService，直接用
第 2 级：反射调用 context.getAdapterService()
第 3 级：读静态字段 AdapterService.sAdapterService
第 4 级：从 IOplusRemoteDevice 实例里取 mAdapterService 字段
```

```text
[健壮性写法] 逐级降级，任意一级成功即可；
             方法查找是【沿父类向上找】(cls.getSuperclass())，
             不假定方法声明在哪个类。
```

---

## 3. "我的设备"显示（`defpackage/ap.java`）

### 3.1 机制：改 UI 文字，不动数据

```java
// ap.s(view, str, str2)
view.setEnabled(true);
view.setAlpha(1.0f);
view.setContentDescription("HuaweiPods, " + str);

TextView title   = view.findViewById(android.R.id.title);
TextView summary = view.findViewById(android.R.id.summary);

title.setText("HuaweiPods");
summary.setText(str2);      // 中文环境："电量与设置"
```

```text
[要点] 用的是 Android 标准 id（android.R.id.title / summary），
       所以不依赖 ColorOS 自定义布局的具体 id。
       找不到标准 id 时，还退化为"递归收集子 TextView 再按顺序改"。
```

### 3.2 入口：点条目打开自己的界面

```java
// ap.u(context, device)
Intent i = new Intent(ACTION_SHOW_PODS_UI);
i.setPackage("io.github.artifical0.huaweipods.coloros");
i.putExtra("android.bluetooth.device.extra.DEVICE", device);
context.startActivity(i);
// 失败则退化为直接启动自己的 MainActivity
```

---

## 4. Quick Connect（`defpackage/to.java`，作用域 com.heytap.accessory）

### 4.1 识别"这张卡片是不是我们的"

```java
Intent intent = activity.getIntent();
Bundle b = intent.getBundleExtra("intent_bundle");
byte[] raw = b.getByteArray("base_view_data");

// 用 protobuf 解析（类名列表里有 com.oplus.pantaconnect.data.BaseViewData）
Object viewData = cls.getMethod("parseFrom", byte[].class).invoke(null, raw);
Object plugin   = viewData.getClass().getMethod("getPluginViewData").invoke(viewData);

String pkg   = (String) plugin.getClass().getMethod("getPackageName").invoke(plugin);
String title = (String) plugin.getClass().getMethod("getDialogTitle").invoke(plugin);
// 然后判断 pkg 是不是自己
```

```text
[健壮性写法] 全程反射 + 方法名（parseFrom / getPluginViewData /
             getPackageName / getDialogTitle），
             不依赖具体类名，也不硬编码数值。
```

### 4.2 按签名找方法（最值得学的一招）

```java
// to.o(Class cls)
for (Method m : cls.getDeclaredMethods()) {
    if (Modifier.isStatic(m.getModifiers())
            && m.getReturnType() == void.class
            && Arrays.equals(m.getParameterTypes(), new Class[]{cls})) {
        // 命中：按【签名特征】定位，不看方法名
    }
}
```

```text
[为什么好] 官方 APP 或系统更新后，方法名可能被 R8 改掉，
           但"静态 + 返回 void + 参数是自己的类"这种签名特征通常稳定。
```

### 4.3 其它 Hook 点

```text
android.view.View.performClick       → 通用点击检测
com.oplus.pantaconnect.appcore.devicediscovery.ui.DialogActivity
    · onCreate(Bundle)               → 弹窗出现时
    · dispatchTouchEvent(MotionEvent) → 触摸跟踪
```

---

## 5. 我们要复用的方案（针对 UGREEN T6）

### 5.1 电量桥

```text
沿用 cp.java 的同一条路：
    1. 模块 Hook com.android.bluetooth 进程
    2. 拿 AdapterService（三级兜底）
    3. 反射调用 setBatteryLevel(device, level, false)
    → ColorOS 自己会把电量显示到系统蓝牙页 / 我的设备 / 弹窗

电量来源：我们自己的 BLE 通道（已确认可用）
    85 86 87 04 01 [左] [右] [校验]     周期 9.5 秒
```

### 5.2 显示与入口

```text
沿用 ap.java 的思路：
    · 用 android.R.id.title / summary 改文字（标准 id，稳）
    · 点条目时 Intent 打开我们自己的界面
```

### 5.3 对"数值 hook 易失效"的回应（用户关切）

用户明确要求：**不要只靠查数值来 Hook，否则云下发或更新后失效。**

后续实现统一遵守以下原则：

```text
1. 按【接口/字段名】反射取，不硬编码数值常量
     ✔ Class.forName("com.oplus.bluetooth.common.interfaces.IOplusRemoteDevice")
     ✔ getField("TRANSPORT_HFP_AT")   而非 写死 1
     ✘ getField 拿到的值写死在代码里

2. 按【方法签名特征】定位，不按方法名
     ✔ 静态 + void 返回 + 参数为自身类型
     ✔ 沿父类链逐级查找
     ✘ 假定方法名固定

3. 多重兜底 + 优雅降级
     ✔ 任一路径失败就换下一条，全部失败则降级并打日志
     ✘ 单点依赖、失败即崩

4. 协议常量（AA BB CC 那套）虽然目前是固定值，
   但要留一条"从官方 APP 实时取值"的退路
   （已经有 hook 官方 APP 的能力，必要时可动态取）
```

---

## 6. 下一步（待用户确认）

```text
目标：先让它跑起来（当前优先级）

最小可行路径：
  1. 新建/扩展一个 Xposed 作用域到 com.android.bluetooth 的能力
  2. 实现 setBatteryLevel 推送（电量从我们 BLE 通道读）
  3. 验证：系统蓝牙页 / 我的设备 能否显示 T6 电量

风险与未知：
  · setBatteryLevel 是否接受"非 HFP AT 通道"的设备（T6 是 BLE 上报）
  · ColorOS 是否要求先注册 deviceFeature / noiseMode 支持位
  · 需要真机验证，可能需要参考 IOplusRemoteDevice 的其它方法
```

---

## 7. 附：HuaweiPods 的作用域与 Hook 清单（备查）

```text
com.android.bluetooth    → bluetooth-headset-state (fl0)
                           bluetooth-upstream     (si)
                           bluetooth-notification (rb1)
com.android.settings     → settings-headset       (z92)
com.heytap.mydevices     → coloros-mydevices      (ap)
com.heytap.accessory     → coloros-accessory      (to)
com.milink.service       → milink-service         (uc1)
com.xiaomi.bluetooth     → mi-bluetooth-toast (rb1) + bluetooth-upstream (si)
com.huawei.smartaudio    → smart-audio-freeclip2-bridge (qc2)

核心实现文件：
  hook/HookEntry.java           417 行   模块入口、作用域分发
  hook/Api102HookEntry.java     321 行   API 102 兼容入口
  cp.java                       153 行   ★ ColorOS 电量桥
  ap.java                       522 行   ★ 我的设备显示
  to.java                       273 行   ★ Quick Connect
  fl0.java                      942 行   蓝牙状态 + 重连策略
  rb1.java                     1483 行   通知 / 弹窗
```

```text
[整体印象] 项目很小（核心 27 个类），
           绝大部分工作量在"找 Hook 点 + 多重兜底"，
           而不是写业务逻辑。这对我们是好消息。
```

---

# 8. 我们的系统桥实施（2026-10-03 晚）

## 8.1 本次实现范围

```text
目标：先让它跑起来 —— 电量从我们的 BLE 通道进入 ColorOS

已实现：
  ✅ 模块扩展到第二个作用域 com.android.bluetooth
  ✅ ColorOS 电量通道调用（setBatteryLevel）
  ✅ 环境自探测（把关键结论写进日志，便于定位失败环节）
  ✅ 电量桥文件双向打通（测试 APP 写、系统桥读）
  ✅ 严格按"不写死数值"的健壮性原则实现
```

## 8.2 三个进程与数据流

```text
┌──────────────────────┐
│ com.mo.ugreenforcoloros │  测试 APP
│  · BLE 读写耳机        │
│  · 解析 85 86 87 状态包 │
│  · 写电量桥文件         │
└───────────┬──────────┘
            │ ugreen_battery.txt（三个候选路径都写）
            ▼
┌──────────────────────┐
│ com.android.bluetooth   │  系统桥（Xposed 作用域）
│  · 每 3 秒读桥文件      │
│  · 反射调用             │
│    AdapterService       │
│    .setBatteryLevel()   │
└───────────┬──────────┘
            ▼
     ColorOS 系统显示
     （蓝牙页 / 我的设备 / 弹窗）
```

```text
[为什么用文件当桥]
  两个进程 uid 不同，无法共享内存。
  文本文件的好处：出问题能直接打开看，调试成本最低，
  且不依赖 binder / socket / 任何权限。
[已知难点] Android 的 FUSE 会拒绝"跨应用读对方专属目录"。
           两端都无法提前确定哪条路径能通，
           所以两端都采用【多候选】策略：
             写侧：三个位置各写一份
             读侧：逐个尝试，哪个读得到用哪个
```

## 8.3 新增/修改的文件

```text
protocolhook（模块侧）
  新增 ColorOsBatteryBridge.java   400 行  ★ 电量桥核心
  新增 SystemBridgeLog.java        117 行     系统桥专用日志
  修改 HookEntry.java                      双作用域分发
  修改 res/values/strings.xml              作用域加 com.android.bluetooth

app（测试 APP 侧）
  新增 UgreenT6BatteryBridge.kt    150 行  ★ 解析状态包 + 写桥文件
  修改 UgreenT6Gatt.kt                     新增 onNotifyData 回调
  修改 MainActivity.kt                     接上电量解析
```

## 8.4 健壮性实现对照（回应用户关切）

用户要求：**不要只靠数值 Hook，否则云控下发或更新后失效。**

| 原则 | 本项目的做法 |
|---|---|
| 按名字取，不写死数值 | `Class.forName("com.oplus.bluetooth.common.interfaces.IOplusRemoteDevice")`、`getField("TRANSPORT_HFP_AT")` |
| 按签名找方法 | `findMethodLenient(cls, "setBatteryLevel", BluetoothDevice.class, int.class, boolean.class)` |
| 沿父类链查找 | `cur = cur.getSuperclass()` 逐级向上，不假定声明在哪个类 |
| 多级兜底 | `findAdapterService()` 依次尝试：宿主自身 → `getAdapterService()` → 静态字段 |
| 优雅降级 | 任何一环失败只写日志，绝不抛出，不影响系统进程 |
| 电量来源可替换 | 推送逻辑与数据来源解耦（`readLevels()` 单独一层） |

```text
[特别说明] 只有设备 MAC（90:A0:BE:2D:5E:CA）是硬编码的，
           但它是【设备标识】而非协议数值，换耳机时改一行，
           不存在"刷固件后失效"的问题。
```

## 8.5 环境自探测（便于定位失败环节）

`probeEnvironment()` 在安装时就把结论写进日志：

```text
类存在: com.android.bluetooth.btservice.AdapterService
类存在: com.oplus.bluetooth.common.OplusFeatureCache
类存在: com.oplus.bluetooth.common.interfaces.IOplusRemoteDevice
AdapterService 探测结果=已找到 / 未找到
setBatteryLevel 方法=已找到 / 未找到
IOplusRemoteDevice.DEFAULT 存在，TRANSPORT_HFP_AT=?
```

```text
[价值] 即使推送失败，也能一眼看出卡在哪一环：
       类不存在？→ 系统结构变了
       AdapterService 找不到？→ 兜底路径要调整
       setBatteryLevel 找不到？→ ColorOS 改了方法名
```

## 8.6 日志位置

```text
系统桥日志（系统桥进程写）：
    /sdcard/Android/data/com.android.bluetooth/files/ugreen_system_bridge.log
    或 /sdcard/Download/ugreen_system_bridge.log
    或 /data/local/tmp/ugreen_system_bridge.log
    （按可写性自动选，取回时三个都看看）

协议桥日志（官方 APP 进程写）：
    仍是原来的 ugreen_protocol.log
```

## 8.7 待真机验证的点

```text
1. 模块能否成功 Hook com.android.bluetooth 进程
   （LSPosed 作用域要新勾这一项，并重启蓝牙或重启手机）

2. AdapterService.setBatteryLevel 是否真的能改系统显示
   ★ 这是最大未知数

3. 已知风险：T6 的电量走 BLE 上报，
   而 ColorOS 的 TRANSPORT_HFP_AT 是 AT 通道语义。
   如果 setBatteryLevel 对通道有要求，
   可能还需要配合 IOplusRemoteDevice 的其它方法
   （例如 setOplusDeviceProtocolTransport 之类）来声明传输方式。
   → 真机日志里会体现，届时针对性处理。

4. 桥文件路径最终哪条能通（看日志即可确定）
```

## 8.8 下一步（真机反馈后）

```text
· 看 ugreen_system_bridge.log 判断卡在哪一环
· 若 setBatteryLevel 不生效 → 试 IOplusRemoteDevice 的其它方法
· 若电量能进系统 → 再做"我的设备"显示 hook（参照 ap.java）
```

