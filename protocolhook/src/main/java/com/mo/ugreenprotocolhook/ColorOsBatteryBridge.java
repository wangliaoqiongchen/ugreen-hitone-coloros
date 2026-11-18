package com.mo.ugreenprotocolhook;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * ColorOS 电量桥。
 *
 * ================== 背景（来自对 HuaweiPods 的逆向） ==================
 *
 * ColorOS 有一套给"三方耳机"用的电量通道，入口是蓝牙服务进程里的
 * AdapterService.setBatteryLevel(BluetoothDevice, int, boolean)。
 *
 * 调用之后，ColorOS 自己会把电量显示到：
 *     · 系统蓝牙设置页
 *     · "我的设备"
 *     · 连接弹窗 / 流体云
 *
 * 还有一个 Oplus 专有接口：
 *     com.oplus.bluetooth.common.OplusFeatureCache
 *         .get(IOplusRemoteDevice.DEFAULT)
 * 用来取 TRANSPORT_HFP_AT 之类的常量。
 *
 * ================== 健壮性设计（重要） ==================
 *
 * 用户明确要求：不要只靠硬编码数值来 Hook，
 *               否则系统更新或云控下发后就会失效。
 *
 * 因此本类遵守以下原则：
 *
 *   1. 按【接口名 / 字段名】反射取值，不写死数值常量
 *        ✔ Class.forName("com.oplus.bluetooth.common.interfaces.IOplusRemoteDevice")
 *        ✔ getField("TRANSPORT_HFP_AT")   而不是写死 1
 *
 *   2. 按【方法签名】查找，不假定方法名之外的任何东西
 *        ✔ 沿父类链逐级向上查找
 *        ✔ 参数类型用 Class 描述，不用下标猜
 *
 *   3. 多级兜底 + 优雅降级
 *        ✔ 任何一级失败就换下一级
 *        ✔ 全部失败只打日志，绝不抛出
 *
 *   4. 电量来源要可替换
 *        当前从"桥文件"读，将来可换成任何来源，不影响推送逻辑
 */
public final class ColorOsBatteryBridge {

    private static final String TAG = "UGREEN-SYSBRIDGE";

    /**
     * 需要上报电量的设备地址（UGREEN HiTune T6）。
     *
     * 注意：这个地址本身不是"协议数值"，而是设备标识，
     *       换耳机时改这里即可，不存在刷固件失效的问题。
     */
    private static final String TARGET_MAC = T6Config.mac();

    /** 电量刷新周期（秒）。 */
    private static final long PUSH_INTERVAL_SEC = 3L;

    // ------------------------------------------------------------------
    // 电量来源
    // ------------------------------------------------------------------

    /**
     * 电量桥文件路径候选。
     *
     * ================== 为什么给三个候选 ==================
     *
     * 写文件的是【测试 APP】（uid=u0_aXXX），
     * 读文件的是【本类所在的 com.android.bluetooth 进程】（另一个 uid）。
     *
     * Android 的 FUSE 层会拒绝"跨应用读取对方的
     * /sdcard/Android/data/<pkg>/files"，所以不能只固定一个路径，
     * 必须准备多个候选，逐个尝试，哪个读得到就用哪个。
     *
     * 三个候选的权限特性：
     *   /sdcard/Android/data/com.mo.ugreenforcoloros/files
     *       写入最稳（对方自己的目录），但跨进程读可能被拒
     *   /sdcard/Download
     *       共享区域，双方都可能访问（取决于系统对 system uid 的策略）
     *   /data/local/tmp
     *       权限 0771，system uid 通常可访问
     *
     * 文件格式（简单文本，容错优先）：
     *     mac=90:A0:BE:2D:5E:CA
     *     left=100
     *     right=95
     *     case=80
     */
    private static final String[] LEVEL_FILE_CANDIDATES = {
            /*
             * ★ 首选（唯一实测可行）：蓝牙自己的日志目录。
             *
             * 为什么只有它可行 —— 这是 SELinux 决定的，不是权限位：
             *
             *   路径                              SELinux 标签                 蓝牙域可读
             *   /sdcard/Download/...              media_rw_data_file           ✗ FUSE 拒绝
             *   /data/local/tmp/...               shell_data_file              ✗ 跨域被拒
             *   /data/misc/bluetooth/logs/...     bluetooth_logs_data_file     ✔ 实测可读写
             *
             * 实测证据（以蓝牙 uid 1002 身份）：
             *   · 在这个目录里自己写、自己读回 → 成功
             *   · 读 root 放在这里的 0644 文件 → 成功
             *   · 读 /data/local/tmp/ugreen_bridge 下的 0666 文件 → 失败
             *
             * 因为该目录是 drwxrwx--- bluetooth:bluetooth，
             * 普通 APP 写不进去，所以由【测试 APP 用 su】把文件放进来。
             */
            "/data/misc/bluetooth/logs/ugreen_battery.txt",

            // 以下为历史候选，保留仅作兜底（实测均不可读）
            "/data/local/tmp/ugreen_bridge/ugreen_battery.txt",
            "/sdcard/Download/ugreen_battery.txt",
            "/sdcard/Android/data/com.mo.ugreenforcoloros/files/ugreen_battery.txt",
    };

    private ColorOsBatteryBridge() {
    }

    // ------------------------------------------------------------------
    // 类加载器（★ 极易踩坑的地方）
    // ------------------------------------------------------------------

    /**
     * 目标进程的类加载器。
     *
     * ================== 为什么必须有它 ==================
     *
     * 最初这里用的是 ColorOsBatteryBridge.class.getClassLoader()，
     * 结果真机日志显示：
     *     类不存在: com.android.bluetooth.btservice.AdapterService
     *     类不存在: com.oplus.bluetooth.common.OplusFeatureCache
     *
     * 原因：那个 ClassLoader 是【Xposed 模块自己的】，
     *       它看不到 com.android.bluetooth 进程里的任何应用类。
     *
     * 正确做法：用 LSPosed 在 handleLoadPackage 里给的
     *           LoadPackageParam.classLoader（即目标应用的 ClassLoader）。
     */
    private static volatile ClassLoader targetClassLoader;

    /**
     * 安装时注入目标进程的类加载器。
     *
     * @param cl handleLoadPackage 回调里拿到的 lpparam.classLoader
     */
    public static void setClassLoader(ClassLoader cl) {
        targetClassLoader = cl;
    }

    private static ClassLoader loader() {
        ClassLoader cl = targetClassLoader;
        return cl != null ? cl : ColorOsBatteryBridge.class.getClassLoader();
    }

    // ------------------------------------------------------------------
    // 安装
    // ------------------------------------------------------------------

    private static volatile boolean pushScheduled = false;

    /**
     * 安装（可重复调用，内部做幂等保护）。
     *
     * ================== 为什么要分两次触发 ==================
     *
     * LSPosed 在【应用进程创建的最早期】就调用 handleLoadPackage，
     * 那时候 com.android.bluetooth 的 AdapterService、
     * OplusFeatureCache 等类【还没被加载】，
     * 即使 ClassLoader 正确，Class.forName 也会报"类不存在"。
     *
     * 所以采用双重触发：
     *   第 1 次：进程加载时就装一遍（能装多少装多少）
     *   第 2 次：Hook 蓝牙适配器状态回调，等蓝牙真正 UP 后再装一遍
     *           —— 那时候所有类都齐了
     *
     * 这个设计同时满足"多重兜底"原则。
     */
    public static void install() {

        if (pushScheduled) {
            return;
        }
        pushScheduled = true;

        SystemBridgeLog.event("ColorOsBatteryBridge 开始安装");

        // 先探测环境，把结论写进日志，便于定位
        probeEnvironment();

        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ugreen-battery-push");
            t.setDaemon(true);
            return t;
        });

        exec.scheduleWithFixedDelay(
                ColorOsBatteryBridge::pushOnce,
                3L,
                PUSH_INTERVAL_SEC,
                TimeUnit.SECONDS
        );

        SystemBridgeLog.event("电量推送任务已启动，周期 " + PUSH_INTERVAL_SEC + " 秒");
    }

    /**
     * 蓝牙真正就绪后的"二次探测"。
     *
     * 由 HookEntry 在蓝牙适配器状态变化时调用。
     * 此时 AdapterService / Oplus 类应当都已加载。
     */
    public static void onBluetoothReady() {

        SystemBridgeLog.event("蓝牙就绪回调触发，重新探测环境");

        try {
            probeEnvironment();
        } catch (Throwable t) {
            SystemBridgeLog.event("二次探测异常: " + t);
        }

        // 顺便立刻推一次电量，不用等下一个周期
        try {
            pushOnce();
        } catch (Throwable t) {
            SystemBridgeLog.event("二次推送异常: " + t);
        }
    }

    /**
     * 探测运行环境：把关键结论写进日志。
     *
     * 这样即使后续推送失败，也能一眼看出是哪一环的问题。
     */
    private static void probeEnvironment() {

        try {
            ClassLoader cl = loader();

            SystemBridgeLog.event("使用的 ClassLoader=" + cl);

            reportClass(cl, "com.android.bluetooth.btservice.AdapterService");
            reportClass(cl, "com.oplus.bluetooth.common.OplusFeatureCache");
            reportClass(cl, "com.oplus.bluetooth.common.interfaces.IOplusRemoteDevice");
            reportClass(cl, "com.oplus.bluetooth.common.IOplusCommonFeature");

            Object adapterService = findAdapterService(null);
            SystemBridgeLog.event("AdapterService 探测结果="
                    + (adapterService == null ? "未找到" : "已找到"));

            if (adapterService != null) {
                Method m = findMethodLenient(
                        adapterService.getClass(),
                        "setBatteryLevel",
                        BluetoothDevice.class, int.class, boolean.class);
                SystemBridgeLog.event("setBatteryLevel 方法="
                        + (m == null ? "未找到" : "已找到"));
            }

            // Oplus 接口与常量（按名字取，不写死数值）
            try {
                Class<?> remoteDevice = Class.forName(
                        "com.oplus.bluetooth.common.interfaces.IOplusRemoteDevice",
                        false, cl);
                Field def = remoteDevice.getField("DEFAULT");
                Field transport = remoteDevice.getField("TRANSPORT_HFP_AT");
                SystemBridgeLog.event("IOplusRemoteDevice.DEFAULT 存在，"
                        + "TRANSPORT_HFP_AT=" + transport.getInt(null));
                SystemBridgeLog.event("DEFAULT 字段类型=" + def.getType().getName());
            } catch (Throwable t) {
                SystemBridgeLog.event("IOplusRemoteDevice 常量探测失败: " + t);
            }

        } catch (Throwable t) {
            SystemBridgeLog.event("环境探测异常: " + t);
        }
    }

    private static void reportClass(ClassLoader cl, String name) {
        try {
            Class.forName(name, false, cl);
            SystemBridgeLog.event("类存在: " + name);
        } catch (Throwable t) {
            SystemBridgeLog.event("类不存在: " + name);
        }
    }

    // ------------------------------------------------------------------
    // 推送
    // ------------------------------------------------------------------

    private static volatile int lastPushedLeft = -1;
    private static volatile int lastPushedRight = -1;

    /** 是否已经打过一次"读不到电量"的日志（避免刷屏）。 */
    private static volatile boolean loggedNoLevels = false;

    /** 是否已经打过一次"推送失败"的日志（避免刷屏）。 */
    private static volatile boolean loggedPushFail = false;

    private static void pushOnce() {

        try {
            int[] levels = readLevels();

            if (levels == null) {
                /*
                 * ★ 这里必须留痕。
                 *
                 * 之前的版本在失败时直接 return，
                 * 结果是"日志里什么都没有"，完全无法判断
                 * 是没读到文件、还是推送失败、还是任务没跑。
                 * 只打一次，避免刷屏。
                 */
                if (!loggedNoLevels) {
                    loggedNoLevels = true;
                    SystemBridgeLog.event(
                            "读不到电量文件（候选路径均不可读），"
                                    + "后续不再重复提示。候选="
                                    + java.util.Arrays.toString(LEVEL_FILE_CANDIDATES));
                }
                return;
            }

            loggedNoLevels = false;

            int left = levels[0];
            int right = levels[1];

            // 没变化就不重复推送，省电也少刷日志
            if (left == lastPushedLeft && right == lastPushedRight) {
                return;
            }

            SystemBridgeLog.event(
                    "准备推送电量 left=" + left + " right=" + right);

            boolean ok = pushBattery(right);

            if (ok) {
                lastPushedLeft = left;
                lastPushedRight = right;
                loggedPushFail = false;
                SystemBridgeLog.event("推送电量成功 left=" + left + " right=" + right);
            } else if (!loggedPushFail) {
                loggedPushFail = true;
                SystemBridgeLog.event("推送电量失败（详见上面的原因），后续不再重复提示");
            }

        } catch (Throwable t) {
            SystemBridgeLog.event("推送异常: " + t);
        }
    }

    /**
     * 从桥文件读取电量。
     *
     * @return {left, right}；读不到返回 null
     */
    private static int[] readLevels() {

        for (String path : LEVEL_FILE_CANDIDATES) {
            File f = new File(path);
            if (!f.exists()) {
                continue;
            }
            try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                int left = -1;
                int right = -1;
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    int eq = line.indexOf('=');
                    if (eq <= 0) {
                        continue;
                    }
                    String key = line.substring(0, eq).trim().toLowerCase();
                    String val = line.substring(eq + 1).trim();
                    if ("left".equals(key)) {
                        left = parseIntSafe(val);
                    } else if ("right".equals(key)) {
                        right = parseIntSafe(val);
                    }
                }
                if (right >= 0) {
                    return new int[]{left, right};
                }
            } catch (Throwable ignored) {
                // 换下一个候选
            }
        }
        return null;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 把电量推给 ColorOS。
     *
     * 走的是 AdapterService.setBatteryLevel(BluetoothDevice, int, boolean)，
     * 这是 ColorOS 三方耳机电量通道的标准入口。
     */
    private static boolean pushBattery(int level) {

        if (level < 0 || level > 100) {
            return false;
        }

        Object adapterService = findAdapterService(null);
        if (adapterService == null) {
            SystemBridgeLog.event("找不到 AdapterService，无法推送");
            return false;
        }

        BluetoothDevice device = remoteDevice(TARGET_MAC);
        if (device == null) {
            SystemBridgeLog.event("找不到蓝牙设备 " + TARGET_MAC);
            return false;
        }

        Method m = findMethodLenient(
                adapterService.getClass(),
                "setBatteryLevel",
                BluetoothDevice.class, int.class, boolean.class);

        if (m == null) {
            SystemBridgeLog.event("找不到 setBatteryLevel 方法");
            return false;
        }

        try {
            m.invoke(adapterService, device, level, Boolean.FALSE);
            return true;
        } catch (Throwable t) {
            SystemBridgeLog.event("setBatteryLevel 调用失败: " + t);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 反射工具（全部遵守"按名字/签名，不写死数值"原则）
    // ------------------------------------------------------------------

    private static BluetoothDevice remoteDevice(String mac) {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            return adapter == null ? null : adapter.getRemoteDevice(mac);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 多级兜底获取 AdapterService。
     *
     * 首选参数上下文（如果 Hook 的宿主本身就是 AdapterService），
     * 否则依次尝试静态字段、静态方法。
     */
    private static Object findAdapterService(Object context) {

        if (context != null) {
            String cn = context.getClass().getName();
            if (cn.equals("com.android.bluetooth.btservice.AdapterService")) {
                return context;
            }
            Object viaCall = invokeNoArg(context, "getAdapterService");
            if (viaCall != null) {
                return viaCall;
            }
        }

        ClassLoader cl = loader();

        /*
         * 路径 3：静态字段（名字可能有多个变体，逐个试）
         */
        try {
            Class<?> cls = Class.forName(
                    "com.android.bluetooth.btservice.AdapterService", false, cl);
            Object v = staticFieldValue(cls, "sAdapterService", "sInstance", "INSTANCE");
            if (v != null) {
                return v;
            }
        } catch (Throwable ignored) {
        }

        /*
         * 路径 4：从 AdapterProperties 实例反取。
         *
         * AdapterProperties 的字段 mAdapterService 指向 AdapterService。
         * 这个实例由 HookEntry 在它构造时捕获（见 setAdapterProperties）。
         */
        Object props = adapterPropertiesRef;
        if (props != null) {
            try {
                Field f = findFieldLenient(props.getClass(), "mAdapterService",
                        "mService", "adapterService");
                if (f != null) {
                    Object v = f.get(props);
                    if (v != null) {
                        return v;
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        return null;
    }

    /** AdapterProperties 实例引用，由 HookEntry 捕获后注入。 */
    private static volatile Object adapterPropertiesRef;

    /** 由 HookEntry 在 AdapterProperties 构造时调用。 */
    public static void setAdapterProperties(Object props) {
        adapterPropertiesRef = props;
        SystemBridgeLog.event("已捕获 AdapterProperties 实例: "
                + (props == null ? "null" : props.getClass().getName()));
    }

    /**
     * 逐级在父类链上查找字段（不假定声明位置）。
     */
    private static Field findFieldLenient(Class<?> cls, String... names) {
        for (String name : names) {
            Class<?> cur = cls;
            while (cur != null) {
                try {
                    Field f = cur.getDeclaredField(name);
                    f.setAccessible(true);
                    return f;
                } catch (Throwable ignored) {
                    cur = cur.getSuperclass();
                }
            }
        }
        return null;
    }

    /** 逐个尝试静态字段名（不假定具体名字，多给几个候选）。 */
    private static Object staticFieldValue(Class<?> cls, String... names) {
        for (String name : names) {
            try {
                Field f = cls.getDeclaredField(name);
                f.setAccessible(true);
                Object v = f.get(null);
                if (v != null) {
                    return v;
                }
            } catch (Throwable ignored) {
                // 试下一个名字
            }
        }
        return null;
    }

    private static Object invokeNoArg(Object target, String methodName) {
        try {
            Method m = findMethodLenient(target.getClass(), methodName);
            if (m != null) {
                return m.invoke(target);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 按方法名 + 参数类型在类及其父类链上查找。
     *
     * 不假定方法声明在哪个类，逐级向上找。
     */
    private static Method findMethodLenient(
            Class<?> cls, String name, Class<?>... params) {

        Class<?> cur = cls;
        while (cur != null) {
            try {
                Method m = cur.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (Throwable ignored) {
                cur = cur.getSuperclass();
            }
        }
        return null;
    }
}
