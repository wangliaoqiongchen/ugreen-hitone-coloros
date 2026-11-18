package com.mo.ugreenprotocolhook;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 第一段：协议桥 —— 只监听，不改行为。
 *
 * 目标：拿到官方"声学"APP 发往 T6 的【原始字节】。
 *
 * ============================ 关键设计 ============================
 *
 * 陷阱：如果无条件 hook java.io.OutputStream.write，
 *       会把 APP 的网络请求、文件写入、日志输出全部记进来，
 *       日志瞬间被淹，真正的协议字节反而找不到。
 *
 * 解法：在 BluetoothSocket.getOutputStream() / getInputStream() 的
 *       返回值上做标记，之后只记录"来自蓝牙 socket 的流"。
 *
 *       java.io.OutputStream 是 bootclasspath 类，不可能被混淆，
 *       所以这个 hook 一定命中；再叠加流标记，就既可靠又干净。
 *
 * =================================================================
 *
 * 合规：
 *   · 不修改任何返回值，只读取参数副本
 *   · 只在目标 APP 进程内运行，不 hook 任何系统进程
 *   · 只在目标 APP 自己的私有目录落盘
 */
public class HookEntry implements IXposedHookLoadPackage {

    private static final String TAG = "UGREEN-HOOK";

    /** 只处理这个包名。 */
    /** 协议桥作用域：官方"声学"APP。 */
    private static final String TARGET_PACKAGE = "com.ugreen.iot";

    /**
     * 系统桥作用域：ColorOS 的蓝牙服务进程。
     *
     * 电量必须通过这个进程里的 AdapterService.setBatteryLevel()
     * 才能进入系统（ColorOS 三方耳机电量通道）。
     */
    private static final String BLUETOOTH_PACKAGE = "com.android.bluetooth";

    /**
     * 第三个作用域：「无线耳机」App（com.oplus.melody）。
     *
     * 它是【系统降噪 UI 的数据源】——
     * SystemUI 音量面板通过它的 ContentProvider 拿数据。
     * 在它这里注入伪造数据，系统原生 UI 就会出现。
     */
    private static final String MELODY_PACKAGE = "com.oplus.melody";

    /**
     * 第四个作用域：「设备空间」（com.heytap.mydevices）。
     *
     * 这是【耳机详情页】的宿主 —— 用户点开蓝牙设置里的 T6，
     * 看到的就是这个 App 的页面。
     *
     * 我们照搬 HuaweiPods 的做法，接管页面里系统自带的
     * "蓝牙音频设置"（pref_bt_audio_settings）条目，
     * 把它改造成 UGREEN 的控制入口。完全不碰系统界面。
     */
    private static final String MYDEVICES_PACKAGE = "com.heytap.mydevices";

    /**
     * 第五个作用域：「设备快连」（com.heytap.accessory）。
     *
     * 它负责【开盖连接弹窗】—— 真机日志显示它已经在扫描 T6：
     *     Filter: [ DeviceAddress=90:A0:BE:2D:5E:CA ]
     * 只是 T6 当前已连接、不广播，所以还没扫到。
     * 开盖时耳机会广播，弹窗就可能出现，那时我们接管它。
     */
    private static final String ACCESSORY_PACKAGE = "com.heytap.accessory";


    /** 目标耳机地址（真机已知值）。 */
    private static final String TARGET_MAC = T6Config.mac();

    /**
     * MAC 严格过滤。
     *
     * 第一版设为 false 的原因：
     *   流标记已经把范围缩小到"蓝牙流"，
     *   再叠加 MAC 过滤的话，万一地址读不到就会漏数据。
     *   宁可偶尔混入别的蓝牙设备，也不能漏 T6。
     */
    private static final boolean STRICT_MAC_FILTER = false;

    /** 来自蓝牙 socket 的输出流（TX 只记这些）。 */
    private static final Set<Object> BT_OUT_STREAMS =
            Collections.newSetFromMap(new ConcurrentHashMap<Object, Boolean>());

    /** 来自蓝牙 socket 的输入流（RX 只记这些）。 */
    private static final Set<Object> BT_IN_STREAMS =
            Collections.newSetFromMap(new ConcurrentHashMap<Object, Boolean>());

    /** socket 实例 -> 远端地址。 */
    private static final ConcurrentHashMap<Object, String> SOCKET_ADDR =
            new ConcurrentHashMap<>();

    /**
     * 候选日志目录（按优先级）。
     *
     * 必须声明在 static 块之前：
     *   Java 按声明顺序初始化静态成员，
     *   静态块里会用到这个数组，放在后面会读到 null。
     *
     * 顺序原则：越靠前越容易被用户和 AI 取回。
     */
    private static final String[] LOG_DIR_CANDIDATES = {
            // 用户可见、无权限要求，最容易取回
            "/sdcard/Download",
            // 应用专属外部目录
            "/sdcard/Android/data/com.ugreen.iot/files",
            // 目标 APP 私有目录（最保险，但只有 root 能读）
            "/data/data/com.ugreen.iot/files",
            // shell 可读写
            "/data/local/tmp",
    };

    private static volatile boolean protocolInstalled = false;

    /**
     * 类加载即留痕。
     *
     * 作用：如果 HookEntry 类被 LSPosed 载入了，但 handleLoadPackage
     *       因为任何原因提前 return（包名不符、异常等），
     *       我们依然能从这一行看出"类确实进了官方 APP 的进程"。
     *
     * 只做追加写，不删除、不修改任何已有文件。
     */
    static {
        try {
            String line = "CLASS_LOADED " + System.currentTimeMillis() + "\n";
            for (String dir : LOG_DIR_CANDIDATES) {
                File f = new File(dir, "ugreen_hook_beacon.txt");
                try (FileOutputStream fos = new FileOutputStream(f, true)) {
                    fos.write(line.getBytes("UTF-8"));
                } catch (Throwable ignored) {
                    // 该位置不可写，继续下一个
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            XposedBridge.log(TAG + " HookEntry class loaded");
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {

        if (lpparam == null) {
            return;
        }

        String pkg = lpparam.packageName;

        /*
         * 两个作用域，职责不同：
         *
         *   com.ugreen.iot        协议桥：抓官方 APP 与耳机的蓝牙字节
         *   com.android.bluetooth 系统桥：把电量喂给 ColorOS
         */
        if (TARGET_PACKAGE.equals(pkg)) {
            installProtocolBridge(lpparam);
        } else if (BLUETOOTH_PACKAGE.equals(pkg)) {
            installSystemBridge(lpparam);
        } else if (MELODY_PACKAGE.equals(pkg)) {
            installMelodyBridge(lpparam);
        } else if (MYDEVICES_PACKAGE.equals(pkg)) {
            installMyDevicesBridge(lpparam);
        } else if (ACCESSORY_PACKAGE.equals(pkg)) {
            installAccessoryBridge(lpparam);
        }
    }

    // ------------------------------------------------------------------
    // 协议桥：com.ugreen.iot
    // ------------------------------------------------------------------

    private void installProtocolBridge(XC_LoadPackage.LoadPackageParam lpparam) {

        if (protocolInstalled) {
            return;
        }
        protocolInstalled = true;

        /*
         * 建立一个"一定看得见"的信标文件。
         *
         * 背景：日志写在目标 APP 私有目录里时，
         *       如果 Hook 根本没加载，表现和"文件不存在"完全一样，
         *       无法区分是 Hook 没生效还是路径不对。
         *
         * 所以这里同时往几个可读位置各写一条信标，
         * 只要能读到任意一个，就证明 Hook 确实加载了。
         */
        writeBeacon();

        ProtocolLog.init(resolveLogDir());

        ProtocolLog.event("=== 协议桥已加载 ===");
        ProtocolLog.event("process=" + lpparam.processName);
        ProtocolLog.event("target=" + TARGET_PACKAGE
                + " mac=" + TARGET_MAC
                + " strict=" + STRICT_MAC_FILTER);
        ProtocolLog.event(ProtocolLog.deviceHeader());

        hookOutputStream();
        ProtocolLog.event("层1 输出流 hook 完成");

        hookInputStream();
        ProtocolLog.event("层1 输入流 hook 完成");

        hookBluetoothSocket();
        ProtocolLog.event("层2-4 BluetoothSocket hook 完成");

        hookGattBestEffort();
        ProtocolLog.event("层5 GATT hook 完成");

        ProtocolLog.event("=== 协议桥全部 hook 安装完成 ===");
    }

    // ------------------------------------------------------------------
    // 系统桥：com.android.bluetooth
    // ------------------------------------------------------------------

    private static volatile boolean systemInstalled = false;

    private void installSystemBridge(XC_LoadPackage.LoadPackageParam lpparam) {

        if (systemInstalled) {
            return;
        }
        systemInstalled = true;

        /*
         * 系统桥的日志写在 /sdcard/Download，
         * 这样不需要 root 就能取回，便于调试。
         */
        SystemBridgeLog.init();

        SystemBridgeLog.event("=== 系统桥已加载 ===");
        SystemBridgeLog.event("process=" + lpparam.processName);
        SystemBridgeLog.event("pkg=" + BLUETOOTH_PACKAGE);

        /*
         * ★ 关键：必须把【目标进程的 ClassLoader】交给电量桥。
         *
         * 否则电池桥会用模块自己的 ClassLoader 去 Class.forName，
         * 结果所有蓝牙 / Oplus 的类都报"类不存在"。
         * 这个坑已在真机上验证过（见系统桥日志）。
         */
        ColorOsBatteryBridge.setClassLoader(lpparam.classLoader);
        SystemBridgeLog.event("已注入目标 ClassLoader=" + lpparam.classLoader);

        try {
            ColorOsBatteryBridge.install();
        } catch (Throwable t) {
            XposedBridge.log(TAG + " 系统桥安装失败: " + t);
            SystemBridgeLog.event("系统桥安装失败: " + t);
        }

        /*
         * 二次触发：等蓝牙服务真正初始化时再探测一次。
         *
         * 原因：handleLoadPackage 执行在进程创建的最早期，
         *       那时 AdapterService / Oplus 的类还没被加载，
         *       Class.forName 会报"类不存在"（已在真机日志确认）。
         *
         * 做法：Hook AdapterProperties 的构造函数。
         *       它随蓝牙服务一起创建，构造时所有相关类都已就绪；
         *       同时顺手捕获它的实例，供电量桥反取 AdapterService。
         *
         * 用构造函数而不是"某个状态回调方法名"，是因为
         * 【方法名可能随版本变，构造函数不会】—— 更抗更新。
         */
        hookAdapterProperties(lpparam.classLoader);

        /*
         * 降噪接口探测（只侦察，不改行为）。
         *
         * 目的：确认这个 ColorOS 版本里降噪相关类的
         *       真实方法签名，之后再照着写实现 ——
         *       避免"照着别的版本猜方法名"而白改一轮。
         */
        try {
            AncDiscovery.install(lpparam.classLoader);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " 降噪探测失败: " + t);
            SystemBridgeLog.event("降噪探测失败: " + t);
        }

        SystemBridgeLog.event("=== 系统桥安装结束 ===");
    }

    /**
     * Hook AdapterProperties 构造函数。
     *
     * 这是"不猜方法名"的通用手法：
     * 不管 ColorOS 把状态回调改成什么名字，
     * 蓝牙服务初始化时一定会构造 AdapterProperties。
     *
     * @param cl 目标进程的 ClassLoader（★ 必须用它，
     *           用模块自己的 ClassLoader 会找不到类）
     */
    private void hookAdapterProperties(ClassLoader cl) {

        try {
            Class<?> cls = XposedHelpers.findClass(
                    "com.android.bluetooth.btservice.AdapterProperties", cl);

            XposedBridge.hookAllConstructors(cls, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {

                    SystemBridgeLog.event("AdapterProperties 已构造，触发二次探测");

                    try {
                        ColorOsBatteryBridge.setAdapterProperties(param.thisObject);
                    } catch (Throwable ignored) {
                    }

                    try {
                        ColorOsBatteryBridge.onBluetoothReady();
                    } catch (Throwable t) {
                        SystemBridgeLog.event("二次探测调用失败: " + t);
                    }

                    /*
                     * 降噪能力声明（探测 + 试探）。
                     *
                     * 必须延迟执行：AdapterProperties 构造时
                     * 蓝牙适配器还没完全就绪，拿不到已连接设备。
                     * 给系统 20 秒把设备状态准备好。
                     */
                    try {
                        /*
                         * 用本方法的参数 cl（目标进程 ClassLoader）。
                         *
                         * 注意：这里【不能】写 lpparam.classLoader ——
                         * lpparam 是 installSystemBridge 的参数，
                         * 不在本方法作用域内。
                         */
                        final ClassLoader targetCl = cl;
                        new Thread(() -> {
                            try {
                                Thread.sleep(20000L);
                                AncDeclare.install(targetCl);
                            } catch (Throwable t) {
                                SystemBridgeLog.event("降噪声明失败: " + t);
                            }
                        }, "ugreen-anc-declare").start();
                    } catch (Throwable t) {
                        SystemBridgeLog.event("启动降噪声明线程失败: " + t);
                    }
                }
            });

            SystemBridgeLog.event("已挂 AdapterProperties 构造函数钩子");

        } catch (Throwable t) {
            // 找不到也不能让系统桥整体失败
            XposedBridge.log(TAG + " AdapterProperties 钩子失败: " + t);
            SystemBridgeLog.event("AdapterProperties 钩子失败: " + t);
        }
    }

    // ------------------------------------------------------------------
    // 降噪桥：com.oplus.melody（「无线耳机」App）
    // ------------------------------------------------------------------

    private static volatile boolean melodyInstalled = false;

    /**
     * 在「无线耳机」App 里注入降噪数据。
     *
     * 目的：让 SystemUI 的音量面板能拿到 T6 的降噪能力，
     *       从而渲染出系统原生的降噪开关。
     *
     * 做法：接管 EarphoneControlProvider 的 query()/call()，
     *       对 T6 返回伪造但格式正确的数据。
     */
    private void installMelodyBridge(XC_LoadPackage.LoadPackageParam lpparam) {

        if (melodyInstalled) {
            return;
        }
        melodyInstalled = true;

        MelodyLog.init();

        MelodyLog.event("=== 降噪桥已加载 ===");
        MelodyLog.event("process=" + lpparam.processName);
        MelodyLog.event("pkg=" + MELODY_PACKAGE);

        try {
            MelodyProviderHook.install(lpparam.classLoader);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " 降噪桥安装失败: " + t);
            MelodyLog.event("降噪桥安装失败: " + t);
        }

        MelodyLog.event("=== 降噪桥安装结束 ===");
    }

    // ------------------------------------------------------------------
    // 设备空间桥：com.heytap.mydevices
    // ------------------------------------------------------------------

    private static volatile boolean myDevicesInstalled = false;

    /**
     * 在「设备空间」App 里接管耳机详情页的入口。
     *
     * 为什么选这个 App：
     *   用户点开蓝牙设置里的 T6 时，打开的就是它的详情页。
     *   页面里本来就有一个"蓝牙音频设置"条目，
     *   我们把它接管成 UGREEN 的控制入口 ——
     *   不需要新增任何 UI，也不碰系统界面。
     *
     * 这个方案来自 HuaweiPods（已反编译确认，且目标类在本机存在）。
     */
    private void installMyDevicesBridge(XC_LoadPackage.LoadPackageParam lpparam) {

        if (myDevicesInstalled) {
            return;
        }
        myDevicesInstalled = true;

        MyDevicesLog.init();

        MyDevicesLog.event("=== 设备空间桥已加载 ===");
        MyDevicesLog.event("process=" + lpparam.processName);
        MyDevicesLog.event("pkg=" + MYDEVICES_PACKAGE);

        try {
            MyDevicesHook.install(lpparam.classLoader);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " 设备空间桥安装失败: " + t);
            MyDevicesLog.event("设备空间桥安装失败: " + t);
        }

        MyDevicesLog.event("=== 设备空间桥安装结束 ===");
    }

    // ------------------------------------------------------------------
    // 设备快连桥：com.heytap.accessory
    // ------------------------------------------------------------------

    private static volatile boolean accessoryInstalled = false;

    /**
     * 在「设备快连」App 里接管开盖连接弹窗。
     *
     * 当前阶段以【探测】为主：
     *   把弹窗的 intent 数据、视图结构完整记录下来，
     *   确认它是否为 T6 弹出、内容长什么样。
     *
     * 因为照搬华为的类名曾经全部落空（它那个版本机制不同），
     * 所以这里坚持"先看清楚再动手"。
     */
    private void installAccessoryBridge(XC_LoadPackage.LoadPackageParam lpparam) {

        if (accessoryInstalled) {
            return;
        }
        accessoryInstalled = true;

        AccessoryLog.init();

        AccessoryLog.event("=== 设备快连桥已加载 ===");
        AccessoryLog.event("process=" + lpparam.processName);
        AccessoryLog.event("pkg=" + ACCESSORY_PACKAGE);

        try {
            AccessoryHook.install(lpparam.classLoader);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " 设备快连桥安装失败: " + t);
            AccessoryLog.event("设备快连桥安装失败: " + t);
        }

        AccessoryLog.event("=== 设备快连桥安装结束 ===");
    }

    /**
     * 信标：往所有可写位置各写一条，用于确认 Hook 是否加载。
     *
     * 不删任何东西，只在不存在时创建。
     */
    private void writeBeacon() {
        String line = "HOOK_LOADED " + System.currentTimeMillis()
                + " process=" + TARGET_PACKAGE + "\n";

        for (String dir : LOG_DIR_CANDIDATES) {
            try {
                File d = new File(dir);
                if (!d.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    d.mkdirs();
                }
                File f = new File(d, "ugreen_hook_beacon.txt");
                try (FileOutputStream fos = new FileOutputStream(f, true)) {
                    fos.write(line.getBytes("UTF-8"));
                }
            } catch (Throwable ignored) {
                // 这个位置不可写，继续试下一个
            }
        }
    }

    // ------------------------------------------------------------------
    // 日志目录
    // ------------------------------------------------------------------

    /**
     * 选一个确实能写的日志目录。
     *
     * 逐个候选做一次真实写入测试，第一个成功的就用它；
     * 全部失败才退回目标 APP 的私有目录。
     */
    private File resolveLogDir() {

        for (String dir : LOG_DIR_CANDIDATES) {
            try {
                File d = new File(dir);
                if (!d.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    d.mkdirs();
                }
                if (!d.isDirectory() || !d.canWrite()) {
                    continue;
                }

                // 真实写一次，确认不是"看起来能写"
                File probe = new File(d, ".ugreen_write_probe");
                try (FileOutputStream fos = new FileOutputStream(probe, true)) {
                    fos.write('x');
                }
                //noinspection ResultOfMethodCallIgnored
                probe.delete();

                return d;
            } catch (Throwable ignored) {
                // 换下一个候选
            }
        }

        return new File("/data/data/" + TARGET_PACKAGE + "/files");
    }

    // ------------------------------------------------------------------
    // 层 1：标准流（配合流标记）
    // ------------------------------------------------------------------

    private void hookOutputStream() {

        XC_MethodHook writer = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {

                if (!isBluetoothOutStream(param.thisObject)) {
                    return;
                }

                byte[] data = extractWrittenBytes(param.args);
                if (data == null || data.length == 0) {
                    return;
                }

                ProtocolLog.tx(data);
            }
        };

        // write(byte[])
        safeHook("java.io.OutputStream", "write",
                new Object[]{byte[].class, writer});

        // write(byte[], int, int)
        safeHook("java.io.OutputStream", "write",
                new Object[]{byte[].class, int.class, int.class, writer});

        // write(int)
        safeHook("java.io.OutputStream", "write",
                new Object[]{int.class, writer});
    }

    private void hookInputStream() {

        XC_MethodHook reader = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {

                if (!isBluetoothInStream(param.thisObject)) {
                    return;
                }

                byte[] data = extractReadBytes(param);
                if (data == null || data.length == 0) {
                    return;
                }

                ProtocolLog.rx(data);
            }
        };

        // read(byte[], int, int)
        safeHook("java.io.InputStream", "read",
                new Object[]{byte[].class, int.class, int.class, reader});

        // read(byte[])
        safeHook("java.io.InputStream", "read",
                new Object[]{byte[].class, reader});
    }

    /** 从 write() 的参数里取出真正被写出的字节。 */
    private byte[] extractWrittenBytes(Object[] args) {

        if (args == null || args.length == 0) {
            return null;
        }

        if (args[0] instanceof byte[]) {

            byte[] src = (byte[]) args[0];

            if (args.length >= 3
                    && args[1] instanceof Integer
                    && args[2] instanceof Integer) {

                int off = ((Integer) args[1]).intValue();
                int len = ((Integer) args[2]).intValue();

                if (off < 0 || len < 0 || off + len > src.length) {
                    return null;
                }

                byte[] out = new byte[len];
                System.arraycopy(src, off, out, 0, len);
                return out;
            }

            return src.clone();
        }

        if (args[0] instanceof Integer) {
            return new byte[]{(byte) ((Integer) args[0]).intValue()};
        }

        return null;
    }

    /** 从 read() 的返回值与参数里取出真正读到的字节。 */
    private byte[] extractReadBytes(XC_MethodHook.MethodHookParam param) {

        Object result = param.getResult();
        if (!(result instanceof Integer)) {
            return null;
        }

        int n = ((Integer) result).intValue();
        if (n <= 0) {
            return null;
        }

        Object[] args = param.args;
        if (args == null || args.length == 0 || !(args[0] instanceof byte[])) {
            return null;
        }

        byte[] buf = (byte[]) args[0];

        int off = 0;
        int capacity = buf.length;

        if (args.length >= 3
                && args[1] instanceof Integer
                && args[2] instanceof Integer) {
            off = ((Integer) args[1]).intValue();
            int want = ((Integer) args[2]).intValue();
            if (want >= 0) {
                capacity = Math.min(want, buf.length - off);
            }
        }

        if (off < 0 || off >= buf.length) {
            return null;
        }

        int len = Math.min(n, capacity);
        if (len <= 0) {
            return null;
        }

        byte[] out = new byte[len];
        System.arraycopy(buf, off, out, 0, len);
        return out;
    }

    private boolean isBluetoothOutStream(Object stream) {
        return stream != null && BT_OUT_STREAMS.contains(stream);
    }

    private boolean isBluetoothInStream(Object stream) {
        return stream != null && BT_IN_STREAMS.contains(stream);
    }

    // ------------------------------------------------------------------
    // 层 2 / 3 / 4：BluetoothSocket
    // ------------------------------------------------------------------

    private void hookBluetoothSocket() {

        Class<?> cls = findClass("android.bluetooth.BluetoothSocket");
        if (cls == null) {
            ProtocolLog.event("找不到 android.bluetooth.BluetoothSocket");
            return;
        }

        // getOutputStream() —— 标记蓝牙输出流
        hookSingle(cls, "getOutputStream", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object stream = param.getResult();
                if (stream != null) {
                    BT_OUT_STREAMS.add(stream);
                    ProtocolLog.event("标记蓝牙输出流 addr="
                            + addrOf(param.thisObject)
                            + " class=" + stream.getClass().getName());
                }
            }
        });

        // getInputStream() —— 标记蓝牙输入流
        hookSingle(cls, "getInputStream", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object stream = param.getResult();
                if (stream != null) {
                    BT_IN_STREAMS.add(stream);
                    ProtocolLog.event("标记蓝牙输入流 addr="
                            + addrOf(param.thisObject)
                            + " class=" + stream.getClass().getName());
                }
            }
        });

        // getRemoteDevice() —— 记录地址，用于打标签
        hookSingle(cls, "getRemoteDevice", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                String a = addressOf(param.getResult());
                if (a != null) {
                    SOCKET_ADDR.put(param.thisObject, a);
                }
            }
        });

        // connect()
        hookSingle(cls, "connect", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                ProtocolLog.event("socket connect 开始 addr="
                        + addrOf(param.thisObject));
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                ProtocolLog.event("socket connect 结束 addr="
                        + addrOf(param.thisObject)
                        + " throwable=" + param.getThrowable());
            }
        });

        // close()
        hookSingle(cls, "close", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                ProtocolLog.event("socket close addr=" + addrOf(param.thisObject));
            }
        });
    }

    // ------------------------------------------------------------------
    // 层 5：BLE GATT（备用）
    // ------------------------------------------------------------------

    private void hookGattBestEffort() {

        Class<?> ch = findClass("android.bluetooth.BluetoothGattCharacteristic");
        if (ch == null) {
            return;
        }

        // setValue(byte[])
        safeHook(ch.getName(), "setValue", new Object[]{
                byte[].class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object a = param.args[0];
                        if (a instanceof byte[] && ((byte[]) a).length > 0) {
                            ProtocolLog.tx((byte[]) a);
                        }
                    }
                }
        });
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 尽量取出这条 socket 的远端地址。 */
    private String addrOf(Object socket) {

        if (socket == null) {
            return "?";
        }

        String cached = SOCKET_ADDR.get(socket);
        if (cached != null) {
            return cached;
        }

        try {
            Object dev = XposedHelpers.callMethod(socket, "getRemoteDevice");
            String a = addressOf(dev);
            if (a != null) {
                SOCKET_ADDR.put(socket, a);
                return a;
            }
        } catch (Throwable ignored) {
        }

        return "?";
    }

    private String addressOf(Object device) {

        if (device == null) {
            return null;
        }

        // 优先直接读字段，彻底避开"在 hook 里再调用被 hook 的方法"的递归风险
        try {
            Object a = XposedHelpers.getObjectField(device, "mAddress");
            if (a instanceof String && !((String) a).isEmpty()) {
                return (String) a;
            }
        } catch (Throwable ignored) {
        }

        // 退路：调用 getAddress()，但用重入标记防止自己 hook 自己
        if (IN_ADDRESS_HOOK.get()) {
            return null;
        }
        IN_ADDRESS_HOOK.set(Boolean.TRUE);
        try {
            Object a = XposedHelpers.callMethod(device, "getAddress");
            if (a instanceof String) {
                return (String) a;
            }
        } catch (Throwable ignored) {
        } finally {
            IN_ADDRESS_HOOK.remove();
        }

        return null;
    }

    /** 防止 getAddress() 的 hook 里再次调用 getAddress() 造成无限递归。 */
    private static final ThreadLocal<Boolean> IN_ADDRESS_HOOK =
            new ThreadLocal<>();

    private Class<?> findClass(String name) {
        try {
            return XposedHelpers.findClass(name, HookEntry.class.getClassLoader());
        } catch (Throwable t) {
            return null;
        }
    }

    private void hookSingle(Class<?> cls, String method, XC_MethodHook cb) {
        try {
            XposedHelpers.findAndHookMethod(cls, method, cb);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hook " + cls.getSimpleName()
                    + "." + method + " 失败: " + t);
        }
    }

    private void safeHook(String className, String method, Object[] paramsAndCb) {
        try {
            XposedHelpers.findAndHookMethod(className,
                    HookEntry.class.getClassLoader(), method, paramsAndCb);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hook " + className + "." + method
                    + " 失败: " + t);
        }
    }
}
