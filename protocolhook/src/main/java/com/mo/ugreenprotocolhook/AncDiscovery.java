package com.mo.ugreenprotocolhook;

import android.bluetooth.BluetoothDevice;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * ColorOS 降噪接口探测器（只侦察，不改变任何行为）。
 *
 * ================== 为什么需要"探路"这一步 ==================
 *
 * 我们对 ColorOS 降噪机制的认知来自 HuaweiPods 的反编译结果：
 *     com.android.bluetooth.ble.app.headset.BinderC6776v
 *         .changeAncMode(int, BluetoothDevice)
 *         .changeAncLevel(String, BluetoothDevice)
 *         .getCommonConfig(BluetoothDevice, String)
 *
 * 但那是【另一个 ColorOS 版本】。直接照着写实现，
 * 一旦这个版本签名不同，就会白改一轮 —— 属于"猜方法名"，
 * 违反本项目定下的纪律。
 *
 * 所以先只做探测：把真实存在的方法签名打印出来，确认后再写实现。
 *
 * ================== 探测手段（多种并行，避免单点失败） ==================
 *
 * 手段 1：直接按类名 + 包前缀查找（HuaweiPods 给的名字）
 * 手段 2：扫描 ClassLoader 已知的类（部分 ClassLoader 可枚举）
 * 手段 3：Hook 关键入口，从"正在发生的调用"里反推真实类与方法
 *
 * 任何一个手段成功，都能拿到我们需要的信息。
 */
final class AncDiscovery {

    private static final String TAG = "UGREEN-ANCDISCOVERY";

    /**
     * 需要探测的类名。
     *
     * 前两个来自 HuaweiPods 反编译，后两个是同包下的关联类。
     * 注意：类名可能被混淆而失效，所以下面还会按【包前缀】枚举。
     */
    private static final String[] CANDIDATE_CLASSES = {
            "com.android.bluetooth.ble.app.headset.BinderC6776v",
            "com.android.bluetooth.ble.app.headset.BluetoothHeadsetService",
            "com.android.bluetooth.ble.app.IMiuiHeadsetCallback",
            "com.android.bluetooth.ble.app.MiuiBluetoothNotificationApi",
            "com.android.bluetooth.ble.app.MiuiBluetoothNotification",
    };

    /**
     * 包前缀：用于按前缀枚举类，避免混淆类名失效。
     */
    private static final String[] PACKAGE_PREFIXES = {
            "com.android.bluetooth.ble.app",
            "com.android.bluetooth.ble",
    };

    /**
     * 我们关心的关键词：出现在方法名里就值得打印。
     */
    private static final String[] KEYWORDS = {
            "anc", "Anc", "ANC",
            "noise", "Noise",
            "common", "Common",
            "support", "Support",
    };

    private AncDiscovery() {
    }

    // ------------------------------------------------------------------

    static void install(ClassLoader cl) {

        SystemBridgeLog.event("AncDiscovery 开始探测");

        for (String name : CANDIDATE_CLASSES) {
            probeClass(cl, name);
        }

        probeByEnumeration(cl);
        probeByHookingEntries(cl);

        SystemBridgeLog.event("AncDiscovery 探测结束");
    }

    // ------------------------------------------------------------------
    // 手段 1：按类名直接探测
    // ------------------------------------------------------------------

    private static void probeClass(ClassLoader cl, String name) {

        try {
            Class<?> cls = XposedHelpers.findClass(name, cl);

            SystemBridgeLog.event("── 类存在: " + name
                    + "  接口=" + cls.isInterface()
                    + "  父类=" + (cls.getSuperclass() == null
                    ? "null" : cls.getSuperclass().getName()));

            dumpMethods(cls, name);

        } catch (Throwable t) {
            SystemBridgeLog.event("── 类不存在: " + name);
        }
    }

    /**
     * 打印一个类的全部方法（构造 + 实例 + 静态），含参数类型。
     *
     * 这是探测的核心产物：拿到真实签名后，
     * 我们就能按【方法名 + 参数类型】精确 Hook，不需要猜。
     */
    private static void dumpMethods(Class<?> cls, String name) {

        try {
            List<String> interesting = new ArrayList<>();
            List<String> all = new ArrayList<>();

            for (Method m : cls.getDeclaredMethods()) {
                String sig = (Modifier.isStatic(m.getModifiers()) ? "static " : "")
                        + shortName(m.getReturnType().getName()) + " "
                        + m.getName() + "(" + paramList(m) + ")";

                all.add(sig);

                for (String kw : KEYWORDS) {
                    if (m.getName().contains(kw)) {
                        interesting.add(sig);
                        break;
                    }
                }
            }

            // 先打印关心的，再打印全部（便于人眼快速定位）
            if (!interesting.isEmpty()) {
                SystemBridgeLog.event("  ★ 关心的方法 " + name);
                for (String s : interesting) {
                    SystemBridgeLog.event("      " + s);
                }
            }

            SystemBridgeLog.event("  · 全部方法 " + name + " 共 " + all.size() + " 个");
            for (String s : all) {
                SystemBridgeLog.event("      " + s);
            }

            // 字段也打印（可能藏常量或回调引用）
            Field[] fields = cls.getDeclaredFields();
            if (fields.length > 0) {
                SystemBridgeLog.event("  · 字段 " + name + " 共 " + fields.length + " 个");
                for (Field f : fields) {
                    SystemBridgeLog.event("      "
                            + shortName(f.getType().getName()) + " " + f.getName());
                }
            }

        } catch (Throwable t) {
            SystemBridgeLog.event("  转储方法失败 " + name + ": " + t);
        }
    }

    private static String paramList(Method m) {
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
    // 手段 2：按包前缀枚举
    // ------------------------------------------------------------------

    /**
     * 尝试枚举 ClassLoader 里已加载的类。
     *
     * 说明：这不是可靠 API —— 不同 Android 版本的
     *       ClassLoader 内部结构不同（BaseDexClassLoader.pathList
     *       → dexElements → dexFile），且部分版本禁止反射。
     *       所以整段都包在 try/catch 里，失败不影响其它手段。
     */
    private static void probeByEnumeration(ClassLoader cl) {

        SystemBridgeLog.event("── 手段2：按包前缀枚举（" + cl.getClass().getName() + "）");

        try {
            Field pathListField = findFieldInHierarchy(cl.getClass(), "pathList");
            if (pathListField == null) {
                SystemBridgeLog.event("   pathList 字段不存在，跳过枚举");
                return;
            }
            pathListField.setAccessible(true);
            Object pathList = pathListField.get(cl);
            if (pathList == null) {
                SystemBridgeLog.event("   pathList 为 null，跳过枚举");
                return;
            }

            Field dexElementsField = findFieldInHierarchy(pathList.getClass(), "dexElements");
            if (dexElementsField == null) {
                SystemBridgeLog.event("   dexElements 字段不存在，跳过枚举");
                return;
            }
            dexElementsField.setAccessible(true);
            Object[] dexElements = (Object[]) dexElementsField.get(pathList);
            if (dexElements == null) {
                SystemBridgeLog.event("   dexElements 为 null，跳过枚举");
                return;
            }

            SystemBridgeLog.event("   dexElements 数量=" + dexElements.length);

            int hit = 0;
            for (Object element : dexElements) {
                if (element == null) {
                    continue;
                }
                Field dexFileField = findFieldInHierarchy(element.getClass(), "dexFile");
                if (dexFileField == null) {
                    continue;
                }
                dexFileField.setAccessible(true);
                Object dexFile = dexFileField.get(element);
                if (dexFile == null) {
                    continue;
                }

                // DexFile.entries() 返回 Enumeration<String>
                Method entries = dexFile.getClass().getMethod("entries");
                Object enumeration = entries.invoke(dexFile);
                if (!(enumeration instanceof java.util.Enumeration)) {
                    continue;
                }

                java.util.Enumeration<?> e = (java.util.Enumeration<?>) enumeration;
                while (e.hasMoreElements()) {
                    Object next = e.nextElement();
                    if (!(next instanceof String)) {
                        continue;
                    }
                    String cn = (String) next;
                    for (String prefix : PACKAGE_PREFIXES) {
                        if (cn.startsWith(prefix)) {
                            SystemBridgeLog.event("   [枚举命中] " + cn);
                            hit++;
                            break;
                        }
                    }
                }
            }

            SystemBridgeLog.event("   枚举命中 " + hit + " 个类");

        } catch (Throwable t) {
            SystemBridgeLog.event("   枚举失败（不影响其它手段）: " + t);
        }
    }

    private static Field findFieldInHierarchy(Class<?> cls, String name) {
        Class<?> cur = cls;
        while (cur != null) {
            try {
                return cur.getDeclaredField(name);
            } catch (Throwable ignored) {
                cur = cur.getSuperclass();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 手段 3：Hook 关键入口，从真实调用里反推
    // ------------------------------------------------------------------

    /**
     * Hook 几个"一定会被调用"的入口，把真实发生的类/方法名打出来。
     *
     * 这是最可靠的探测方式：不管类名怎么混淆，
     * 只要系统真的走了降噪这条路，我们就能看到它的真实调用。
     */
    private static void probeByHookingEntries(ClassLoader cl) {

        SystemBridgeLog.event("── 手段3：Hook 关键入口");

        // 入口 A：MiuiBluetoothNotificationApi —— 系统抛通知/弹窗时必经
        hookAllMethodsOf(cl, "com.android.bluetooth.ble.app.MiuiBluetoothNotificationApi");

        // 入口 B：headset 包下的服务类
        hookAllMethodsOf(cl, "com.android.bluetooth.ble.app.headset.BluetoothHeadsetService");

        // 入口 C：HFP 客户端 —— 耳机连接状态变化时必经
        hookAllMethodsOf(cl, "com.android.bluetooth.hfp.HeadsetService");
    }

    private static void hookAllMethodsOf(ClassLoader cl, String className) {

        try {
            Class<?> cls = XposedHelpers.findClass(className, cl);
            SystemBridgeLog.event("   挂钩 " + className
                    + "（声明方法 " + cls.getDeclaredMethods().length + " 个）");

            for (final Method m : cls.getDeclaredMethods()) {

                // 跳过明显无关的（避免噪音）
                if (!isInterestingMethod(m)) {
                    continue;
                }

                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            SystemBridgeLog.event("[调用] "
                                    + param.method.getDeclaringClass().getSimpleName()
                                    + "." + param.method.getName()
                                    + "(" + argsOf(param) + ")");
                        }
                    });
                    SystemBridgeLog.event("      已挂 " + m.getName());
                } catch (Throwable ignored) {
                    // 单个方法挂不上不影响其它
                }
            }

        } catch (Throwable t) {
            SystemBridgeLog.event("   挂钩失败 " + className + ": " + t);
        }
    }

    /**
     * 判断方法是否值得挂钩。
     *
     * 目的：把噪音降下来 —— 只挂与我们目标相关的，
     *       否则日志会被无关调用淹没。
     */
    private static boolean isInterestingMethod(Method m) {

        if (m.isSynthetic()) {
            return false;
        }
        String n = m.getName();

        // 关键词命中就挂
        for (String kw : KEYWORDS) {
            if (n.contains(kw)) {
                return true;
            }
        }

        // 与设备/耳机/连接相关的也挂（这些能带出上下文）
        String[] extra = {"Device", "device", "Headset", "headset",
                "Connect", "connect", "State", "state"};
        for (String kw : extra) {
            if (n.contains(kw)) {
                return true;
            }
        }

        return false;
    }

    private static String argsOf(XC_MethodHook.MethodHookParam param) {

        Object[] args = param.args;
        if (args == null || args.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            Object a = args[i];
            if (a instanceof BluetoothDevice) {
                sb.append("BluetoothDevice(").append(a).append(")");
            } else if (a == null) {
                sb.append("null");
            } else {
                String s = String.valueOf(a);
                sb.append(a.getClass().getSimpleName()).append("=")
                        .append(s.length() > 40 ? s.substring(0, 40) + "…" : s);
            }
        }
        return sb.toString();
    }
}
