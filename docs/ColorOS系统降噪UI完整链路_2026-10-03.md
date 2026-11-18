# ColorOS 系统降噪 UI 的完整实现链路（重大突破）

> 日期：2026-10-03 夜
> 来源：SystemUI.apk + com.oplus.melody.apk 现场提取，非推测
> **本文推翻了之前两个错误结论，是理解系统降噪 UI 的关键文档**

---

## 0. 一句话结论

```text
"长按音量弹出的降噪卡片"的实现链路：

    SystemUI 音量详情面板
        ↓ ContentResolver.query / call
    com.oplus.melody 的 EarphoneControlProvider
        ↓
    com.android.bluetooth 的 IOplusRemoteDevice

关键：SystemUI【不直接读蓝牙栈】，
      而是通过 com.oplus.melody 的 ContentProvider 中转。
```

---

## 1. ★ 先纠正之前的两个错误结论

### 错误 1：以为系统没有降噪 UI

```text
❌ 我的错误做法：在 APK 的 dex 里搜 Java 常量名
                  "NOISE_CANCELLATION_MODE" → 0 命中
   → 误判"系统没有降噪 UI"

✅ 正确做法：用 aapt2 dump strings 查【资源字符串】
             （R8 混淆类名/常量名，但资源字符串不混淆）
```

**证据（MyDevices.apk 的资源字符串）：**

```text
String #16924 : 智能降噪模式
String #16926 : 通透
String #16927 : 弱降噪
String #16928 : 強降噪
String #16929 : 降噪
String #16930 : 人聲通透
String #16931 : 智能降噪
String #16932 : 均衡降噪
String #17308 : 需佩戴耳機使用降噪

英文对照：
  Noise cancellation mode     降噪模式
  Noise cancellation          降噪
  Max noise cancellation      强降噪
  Balanced                    均衡
  Noise control               降噪控制
```

### 错误 2：以为 setBatteryLevel 没生效

```text
用户指出："电量的显示，在一开始没有修改时也是显示电量的"

真相：
  · 系统【本来就在显示】T6 电量（原生 HFP 上报，那个 90/100 是真值）
  · 我们的 setBatteryLevel 确实改了数值（日志证明 OplusRemoteDevice 收到 55）
  · 但 UI 本来就存在 → "看起来没变化" ≠ 失败

[教训] 判断"有没有效果"时，必须先确认基线状态，
       否则会把"本来就有"误判成"改动无效"。
```

---

## 2. SystemUI 侧：EarphoneController（音量面板的降噪控制器）

```text
类：com.oplus.systemui.qs.slider.detail.data.EarphoneController
位置：SystemUI.apk / classes4.dex
源码：tools/jadx-sui4/sources/.../EarphoneController.java（407 行）
```

### 2.1 常量定义（★ 核心协议）

```java
public static final String URI_EARPHONE      = "content://com.oplus.melody.provider.EarphoneControlProvider";
public static final String URI_ACTIVE_DEVICE = ".../melody_method_active_device";
public static final String URI_NOISE         = ".../melody_method_noise_reduction";
public static final String URI_SPATIAL       = ".../melody_method_spatial";
public static final String URI_WEAR          = ".../melody_method_control_wear";

public static final String METHOD_NOISE = "melody_method_noise_reduction";
public static final String METHOD_SPATIAL = "melody_method_spatial";
public static final String METHOD_WEAR = "melody_method_control_wear";

public static final String ACTION = "com.oplus.melody.ACTION_FUN_PAGE";
public static final String ACTIVITY_ID_NOISE = "ear_detail";

// 数据列名
public static final String COLUMN_NAME     = "name";
public static final String COLUMN_ADDRESS  = "address";
public static final String COLUMN_TYPE     = "type";
public static final String COLUMN_SUPPORTS = "supports";      // ★ JSON 字符串
public static final String COLUMN_EAR_LEFT  = "ear_left";
public static final String COLUMN_EAR_RIGHT = "ear_right";
```

### 2.2 完整控制流程

```java
// ① 取当前活动设备
refreshActiveDevice() {
    Cursor c = query(URI_ACTIVE_DEVICE);
    if (c.moveToFirst()) {
        name    = c.getString(COLUMN_NAME);
        address = c.getString(COLUMN_ADDRESS);
        activeDevice = new ActiveDevice(name, address, new LinkedHashMap());
    }
}

// ② 读当前降噪模式
getNoiseReductionMode(address) {
    return getType(1, address, URI_NOISE, "noise");
}

getType(defaultVal, address, uri, key) {
    Cursor c = query(uri, COLUMN_ADDRESS = address);
    if (c.moveToFirst()) {
        // supports 是 JSON，反序列化成 Map<String, List<Integer>>
        String json = c.getString(COLUMN_SUPPORTS);
        List list = new Gson().fromJson(json, ...);
        // type 是当前模式值
        return c.getInt("type");
    }
}

// ③ 用户点击切换
setNoiseReductionMode(activeDevice, mode) {
    Bundle b = new Bundle();
    b.putString(COLUMN_NAME, activeDevice.getName());
    b.putString(COLUMN_ADDRESS, activeDevice.getAddress());
    b.putInt("type", mode);
    contentResolver.call(URI_EARPHONE, METHOD_NOISE, null, b);
}

// ④ 注册变化监听
registerCallback(cb) {
    contentResolver.registerContentObserver(URI_EARPHONE, false, observer);
    // onChange flags: 256 = spatial, 512 = noise, 1280 = active device
}
```

### 2.3 活动设备的数据结构

```java
public final class EarphoneController$ActiveDevice {
    private final String name;                            // 设备名
    private final String address;                         // MAC
    private final Map<String, List<Integer>> supportType; // ★ 支持的类型/档位

    public final Map<String, List<Integer>> getSupportType()
}
```

```text
[解读] supportType 的结构是 Map<模式名, 支持的档位列表>，
       比如 {"noise": [0,1,2,3], "spatial": [0,1]}
       SystemUI 用它来决定渲染哪些降噪选项。
```

---

## 3. 数据源侧：com.oplus.melody

```text
包名：com.oplus.melody
版本：17.6.3
路径：/data/app/~~ccc8Sy9OZiXYox0PCgrjng==/com.oplus.melody-.../base.apk （29 MB）
已知：EarphoneControlProvider 在 classes2.dex 里（出现 10 次）
```

### 3.1 ★ 实测结果（2026-10-03 19:5x）

```bash
content query --uri content://com.oplus.melody.provider.EarphoneControlProvider/melody_method_active_device
→ No result found.

content query --uri .../melody_method_noise_reduction --where "address='90:A0:BE:2D:5E:CA'"
→ No result found.
```

```text
[结论] com.oplus.melody 认为【当前没有支持的活动耳机设备】。
       T6 没有进入这个 Provider → 音量面板里没有降噪 UI。
```

### 3.2 尚未确认的关键点

```text
❓ com.oplus.melody 用什么条件判定"这个耳机支持降噪"？

   候选可能：
     a) 查询蓝牙栈的 getDeviceExtendFeatureMask（我们需要声明）
     b) 直接查 IOplusRemoteDevice（但 dex 里没搜到，可能用反射）
     c) 内部白名单（机型/厂商 ID）
     d) 检查设备是否 ActiveDevice（A2DP 活动设备）

   注意：搜 melody 的 dex，没找到 IOplusRemoteDevice /
        setOplusDeviceNoiseModeSupport / getDeviceExtendFeatureMask 字符串，
        说明它可能用反射，或走别的 API。
```

---

## 4. 重新梳理的完整技术路线

```text
第 1 层：蓝牙栈能力声明                       ✅ 已完成
    com.android.bluetooth 的 IOplusRemoteDevice
    setOplusDeviceNoiseModeSupport(device, mask=0x607, mode)
    真机验证：[读回] mask=1543(0x607) → 系统已接受

第 2 层：com.oplus.melody 的 EarphoneControlProvider   ← ★ 当前卡在这里
    需要让它把 T6 列为"支持降噪的活动设备"
    实测：现在返回 No result found

第 3 层：SystemUI 音量面板渲染                 自动
    只要第 2 层有数据，EarphoneController 就会读到
    并渲染降噪/通透选项

第 4 层：用户点击 → 控制回流                   待做
    call(URI_EARPHONE, "melody_method_noise_reduction", {name, address, type})
    → melody → 蓝牙栈 setExtendFeatureStatus(transport=2)
    → OplusFeatureCache.get(IOplusExtendFeature.DEFAULT)（我们的管理器）
    → 发 T6 命令
```

---

## 5. 下一步的建议做法

```text
优先：搞清 com.oplus.melody 的判定条件

做法 A（推荐，零风险）：反编译 com.oplus.melody 的 classes2.dex，
    找到 EarphoneControlProvider 的 query() 实现，
    看它从哪个 API 取数据、判定条件是什么

做法 B：在 com.oplus.melody 进程里挂探测钩子，
    看它启动时/设备连接时调用了什么（★ 更直接）

做法 C：Hook SystemUI 的 EarphoneController，
    直接把 T6 塞进 ActiveDevice / 伪造 query 结果
    （★ 见效最快，但绕过 melody，控制回流链路会断）
```

```text
[权衡]
  做法 A/B 是"顺着系统设计走"，做完控制回流是通的
  做法 C 是"绕过系统"，UI 能出来但点击后没人处理，
         需要我们再自己接住（也可以，因为我们本来就要自己发 T6 命令）
```

**结论：做法 C 其实可行性很高** —— 因为最终发命令的是我们，
所以即使绕过 melody，只要我们在 SystemUI 侧接住点击事件即可。

---

## 6. 关键文件与命令备忘

```text
已保存的反编译产物：
  tools/report/SystemUI.apk                      （92 MB，现场提取）
  tools/report/MyDevices.apk                     （46 MB）
  tools/report/melody.apk                        （29 MB）
  tools/report/AccessoryFramework.apk            （53 MB）
  tools/report/DeviceHub.apk                     （35 MB）
  tools/report/oplus_bluetooth_common_ext.jar    （2.8 MB）
  tools/report/suidex/classes3,4,9.dex           提取出的 SystemUI dex
  tools/jadx-sui4/                               ★ EarphoneController 在这里
  tools/jadx-sui9/                               ActiveDevice 在这里
  tools/jadx-oplus/                              oplus 蓝牙接口
```

```text
★ 重要工具备忘：查系统 UI 特征要用 aapt2，不要搜 Java 常量名！

  D:\AndroidStudio\Sdk\build-tools\36.0.0\aapt2.exe dump strings <apk> > out.txt
  然后 grep 中文文案（"降噪" "通透"）或资源名

  原因：R8 混淆类名与常量名，但资源字符串不会混淆。
```

```text
★ 反编译命令备忘：
  JADX_CONFIG_DIR / JADX_CACHE_DIR 必须设置
  JAVA_OPTS='-Xmx1500m'，--threads-count 4
  SystemUI 的 dex 较大（9~10 MB），约 1-2 分钟
```

---

## 7. 相关文档

```text
docs/ColorOS三方耳机降噪接口_2026-10-03.md     蓝牙栈侧 API（第 1 层）
docs/ColorOS电量桥_打通验证_2026-10-03.md      电量链路（已打通）
docs/HuaweiPods系统桥机制拆解_2026-10-03.md    HuaweiPods 的三作用域设计
本文                                             系统 UI 侧完整链路（第 2~4 层）
```
