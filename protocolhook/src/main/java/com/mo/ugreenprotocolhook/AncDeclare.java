package com.mo.ugreenprotocolhook;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * ColorOS 三方耳机能力声明：探测 + 试探。
 *
 * ================== 目的 ==================
 *
 * 目标：让 ColorOS 为 UGREEN HiTune T6 显示"降噪"开关，
 *       并把用户的开关动作转成 T6 的私有命令。
 *
 * ColorOS 的做法（已从 oplus_bluetooth_common_ext.jar 反编译确认）：
 *   三方耳机通过 IOplusRemoteDevice 接口向系统声明自己的能力：
 *       setOplusDeviceVendorAndProduct(device, vendorId, productId)
 *       setOplusDeviceFeatures(device, features)
 *       setOplusDeviceProtocolTransport(device, transport)
 *       setOplusDeviceNoiseModeSupport(device, mask, mode)
 *       notifyNoiseModeChanged(device, mode)
 *
 * ================== 本类只做两件事 ==================
 *
 *   1. 把 IOplusRemoteDevice 实例的【真实方法签名】打印出来
 *      —— 确认这个 ColorOS 版本的实际接口（不猜）
 *
 *   2. 试探性为 T6 声明降噪能力
 *      —— 然后看系统蓝牙页会不会冒出降噪开关
 *
 * ================== 风险说明 ==================
 *
 * 只调用"声明能力"类方法，不修改系统任何持久化数据，
 * 不改耳机状态，不拦截任何系统行为。
 * 最坏情况是系统不理会，重启后恢复原状。
 */
final class AncDeclare {

    private static final String TAG = "UGREEN-ANCDECLARE";

    /** 目标耳机 MAC（与其它模块保持一致）。 */
    private static final String TARGET_MAC = T6Config.mac();

    /**
     * 能力位掩码。
     *
     * 来自 AirPodsManager 的常量定义（最权威来源）：
     *     FEATURE_EXT_BATTERY_INFO    = 1
     *     FEATURE_EXT_HEADSET         = 2
     *     FEATURE_EXT_NOISE_MODE      = 4       ★ 降噪模式
     *     FEATURE_EXT_NOISE_TRANSPARENT = 1024  ★ 通透
     *     FEATURE_EXT_NOISE_OFF       = 512     ★ 降噪关
     */
    private static final int FEATURE_EXT_BATTERY_INFO = 1;
    private static final int FEATURE_EXT_HEADSET = 2;
    private static final int FEATURE_EXT_NOISE_MODE = 4;
    private static final int FEATURE_EXT_NOISE_OFF = 512;
    private static final int FEATURE_EXT_NOISE_TRANSPARENT = 1024;

    /**
     * 给 T6 声明的能力集合。
     *
     * 注意：这里【故意不加】FEATURE_EXT_ADAPTIVE_NOISE(8)、
     *       SPATIAL_AUDIO(64)、WEAR_CHECK(32) 等 T6 没有的能力，
     *       避免系统显示我们实现不了的功能。
     */
    private static final int DECLARE_MASK =
            FEATURE_EXT_BATTERY_INFO
                    | FEATURE_EXT_HEADSET
                    | FEATURE_EXT_NOISE_MODE
                    | FEATURE_EXT_NOISE_OFF
                    | FEATURE_EXT_NOISE_TRANSPARENT;

    /**
     * 传输方式。
     *
     * AirPodsManager 用的是 2（它的 setOplusDeviceProtocolTransport(device, 2)）。
     * 我们用 2 做试探；若系统行为异常再调整。
     */
    private static final int TRANSPORT = 2;

    /** 协议版本/厂商 ID（试探值，参考 AT 路线的定义）。 */
    private static final int VENDOR_ID = 1946;
    private static final int PRODUCT_ID = 1;

    private AncDeclare() {
    }

    // ------------------------------------------------------------------

    static void install(ClassLoader cl) {

        SystemBridgeLog.event("AncDeclare 开始（探测 + 试探）");

        Object remoteDevice = getRemoteDeviceInterface(cl);
        if (remoteDevice == null) {
            SystemBridgeLog.event("AncDeclare 拿不到 IOplusRemoteDevice 实例，终止");
            return;
        }

        dumpMethods(remoteDevice.getClass());
        dumpInterfaces(remoteDevice);

        BluetoothDevice t6 = findTargetDevice();
        if (t6 == null) {
            SystemBridgeLog.event("AncDeclare 找不到目标设备 " + TARGET_MAC
                    + "（可能未配对或未连接），只做探测不试探");
            return;
        }

        SystemBridgeLog.event("AncDeclare 目标设备已找到: " + t6);
        declare(remoteDevice, t6);
    }

    // ------------------------------------------------------------------
    // 取接口实例
    // ------------------------------------------------------------------

    /**
     * 通过 OplusFeatureCache.get(IOplusRemoteDevice.DEFAULT) 取实例。
     *
     * 这段逻辑与 ColorOsBatteryBridge 一致 —— 那里已经验证可用。
     */
    private static Object getRemoteDeviceInterface(ClassLoader cl) {

        try {
            Class<?> cacheCls = Class.forName(
                    "com.oplus.bluetooth.common.OplusFeatureCache", false, cl);

            Class<?> remoteCls = Class.forName(
                    "com.oplus.bluetooth.common.interfaces.IOplusRemoteDevice",
                    false, cl);

            Field defaultField = remoteCls.getField("DEFAULT");
            Object defaultKey = defaultField.get(null);

            Method getMethod = cacheCls.getMethod("get", Class.forName(
                    "com.oplus.bluetooth.common.IOplusCommonFeature", false, cl));

            Object instance = getMethod.invoke(null, defaultKey);
            SystemBridgeLog.event("IOplusRemoteDevice 实例="
                    + (instance == null ? "null" : instance.getClass().getName()));
            return instance;

        } catch (Throwable t) {
            SystemBridgeLog.event("取 IOplusRemoteDevice 失败: " + t);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 探测：打印真实方法签名
    // ------------------------------------------------------------------

    private static void dumpMethods(Class<?> cls) {

        try {
            SystemBridgeLog.event("── IOplusRemoteDevice 实现类方法清单");

            List<String> noise = new ArrayList<>();
            List<String> all = new ArrayList<>();

            for (Method m : cls.getMethods()) {
                String sig = shortName(m.getReturnType().getName()) + " "
                        + m.getName() + "(" + params(m) + ")";
                all.add(sig);

                String n = m.getName();
                if (n.contains("Noise") || n.contains("noise")
                        || n.contains("Feature") || n.contains("Extend")
                        || n.contains("Vendor") || n.contains("Transport")
                        || n.contains("Battery") || n.contains("Protocol")) {
                    noise.add(sig);
                }
            }

            SystemBridgeLog.event("  ★ 降噪/能力/识别相关方法（" + noise.size() + " 个）");
            for (String s : noise) {
                SystemBridgeLog.event("      " + s);
            }

            SystemBridgeLog.event("  · 全部方法共 " + all.size() + " 个");
            for (String s : all) {
                SystemBridgeLog.event("      " + s);
            }

        } catch (Throwable t) {
            SystemBridgeLog.event("  方法清单打印失败: " + t);
        }
    }

    private static void dumpInterfaces(Object instance) {

        try {
            Class<?>[] ifs = instance.getClass().getInterfaces();
            SystemBridgeLog.event("── 实现类实现的接口（" + ifs.length + " 个）");
            for (Class<?> c : ifs) {
                SystemBridgeLog.event("      " + c.getName());
            }
        } catch (Throwable t) {
            SystemBridgeLog.event("  接口清单打印失败: " + t);
        }
    }

    private static String params(Method m) {
        StringBuilder sb = new StringBuilder();
        Class<?>[] ps = m.getParameterTypes();
        for (int i = 0; i < ps.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(shortName(ps[i].getName()));
        }
        return sb.toString();
    }

    private static String shortName(String n) {
        int i = n.lastIndexOf('.');
        return i >= 0 ? n.substring(i + 1) : n;
    }

    // ------------------------------------------------------------------
    // 找目标设备
    // ------------------------------------------------------------------

    private static BluetoothDevice findTargetDevice() {

        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) {
                return null;
            }

            // 优先按 MAC 取
            try {
                BluetoothDevice d = adapter.getRemoteDevice(TARGET_MAC);
                if (d != null) {
                    return d;
                }
            } catch (Throwable ignored) {
            }

            // 退路：遍历已配对设备，按名字匹配
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded != null) {
                for (BluetoothDevice d : bonded) {
                    String name = d.getName();
                    if (name != null && name.contains("HiTune")) {
                        return d;
                    }
                }
            }
        } catch (Throwable t) {
            SystemBridgeLog.event("  找设备异常: " + t);
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 试探：声明能力
    // ------------------------------------------------------------------

    private static void declare(Object remoteDevice, BluetoothDevice device) {

        SystemBridgeLog.event("── 开始为 T6 声明能力（mask=0x"
                + Integer.toHexString(DECLARE_MASK) + "）");

        // 第 1 步：声明厂商/产品（让系统识别设备类型）
        call(remoteDevice, "setOplusDeviceVendorAndProduct",
                new Class<?>[]{BluetoothDevice.class, int.class, int.class},
                new Object[]{device, VENDOR_ID, PRODUCT_ID},
                "setOplusDeviceVendorAndProduct");

        // 第 2 步：声明能力集合
        call(remoteDevice, "setOplusDeviceFeatures",
                new Class<?>[]{BluetoothDevice.class, int.class},
                new Object[]{device, DECLARE_MASK},
                "setOplusDeviceFeatures");

        // 第 3 步：声明传输方式
        call(remoteDevice, "setOplusDeviceProtocolTransport",
                new Class<?>[]{BluetoothDevice.class, int.class},
                new Object[]{device, TRANSPORT},
                "setOplusDeviceProtocolTransport");

        // 第 4 步：声明降噪模式支持（mask + 当前模式）
        // 当前模式给 0 / NOISE_MODE_UNKNOWN，先不谎报状态
        call(remoteDevice, "setOplusDeviceNoiseModeSupport",
                new Class<?>[]{BluetoothDevice.class, int.class, int.class},
                new Object[]{device, DECLARE_MASK, 0},
                "setOplusDeviceNoiseModeSupport");

        // 第 5 步：读回来验证系统是否接受了
        readBack(remoteDevice, device);

        SystemBridgeLog.event("── 能力声明结束，请查看系统蓝牙页是否出现降噪开关");
    }

    private static void call(Object target, String name,
                             Class<?>[] paramTypes, Object[] args, String label) {
        try {
            Method m = findMethod(target.getClass(), name, paramTypes);
            if (m == null) {
                SystemBridgeLog.event("  ✗ " + label + " 方法不存在，跳过");
                return;
            }
            Object r = m.invoke(target, args);
            SystemBridgeLog.event("  ✓ " + label + " 调用成功"
                    + (m.getReturnType() == void.class ? "" : " 返回=" + r));

        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            SystemBridgeLog.event("  ✗ " + label + " 调用失败: " + cause);
        }
    }

    private static void readBack(Object remoteDevice, BluetoothDevice device) {

        try {
            Method m = findMethod(remoteDevice.getClass(),
                    "getOplusDeviceNoiseModeSupportMask",
                    new Class<?>[]{BluetoothDevice.class});
            if (m != null) {
                Object r = m.invoke(remoteDevice, device);
                SystemBridgeLog.event("  [读回] getOplusDeviceNoiseModeSupportMask="
                        + r);
            }
        } catch (Throwable t) {
            SystemBridgeLog.event("  [读回] 失败: " + t);
        }

        try {
            Method m = findMethod(remoteDevice.getClass(),
                    "getOplusDeviceVendorId",
                    new Class<?>[]{BluetoothDevice.class});
            if (m != null) {
                SystemBridgeLog.event("  [读回] getOplusDeviceVendorId="
                        + m.invoke(remoteDevice, device));
            }
        } catch (Throwable t) {
            SystemBridgeLog.event("  [读回] vendorId 失败: " + t);
        }

        try {
            Method m = findMethod(remoteDevice.getClass(),
                    "is3rdEarBudsDevice",
                    new Class<?>[]{BluetoothDevice.class});
            if (m != null) {
                SystemBridgeLog.event("  [读回] is3rdEarBudsDevice="
                        + m.invoke(remoteDevice, device));
            }
        } catch (Throwable t) {
            SystemBridgeLog.event("  [读回] is3rdEarBuds 失败: " + t);
        }
    }

    /**
     * 沿父类链 + 接口链查找方法。
     *
     * 用 getMethods() 而不是 getDeclaredMethod，
     * 因为实现类可能通过接口继承这些方法。
     */
    private static Method findMethod(Class<?> cls, String name, Class<?>... paramTypes) {

        // 先直接找（含继承的 public 方法）
        try {
            Method m = cls.getMethod(name, paramTypes);
            m.setAccessible(true);
            return m;
        } catch (Throwable ignored) {
        }

        // 再沿父类链找声明方法
        Class<?> cur = cls;
        while (cur != null) {
            try {
                Method m = cur.getDeclaredMethod(name, paramTypes);
                m.setAccessible(true);
                return m;
            } catch (Throwable ignored) {
                cur = cur.getSuperclass();
            }
        }
        return null;
    }
}
