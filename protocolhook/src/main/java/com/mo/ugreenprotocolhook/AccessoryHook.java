package com.mo.ugreenprotocolhook;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 「设备快连」（com.heytap.accessory）—— 开盖连接弹窗。
 *
 * ================== 真机背景（先查证过） ==================
 *
 * com.heytap.accessory 已经在扫描 T6：
 *
 *     PTC.DISC.BleAdvertiserAdapter: client=DiscoveryClient(
 *         id=accept-senseless, pkg=com.heytap.accessory)
 *     Filter: [ DeviceAddress=90:A0:BE:2D:5E:CA ]     ← T6 在监听列表里
 *     Results: (0 / 0 / 0)                             ← 但当前扫不到
 *
 * 扫不到的原因：T6 正连着手机，不广播。
 * 而【开盖时耳机会广播】，系统就能扫到 → 弹窗出现。
 * 所以这个 Hook 是有意义的。
 *
 * ================== 华为的做法（已反编译） ==================
 *
 * HuaweiPods 的 to.java 是这样处理设备快连的：
 *
 *     hook Resources.getDrawable(int)          → 替换弹窗图标
 *     hook View.performClick()                 → 拦截点击
 *     hook DialogActivity.onCreate(Bundle)     → 识别弹窗
 *     hook DialogActivity.dispatchTouchEvent   → 追踪触摸
 *
 * 注意：它是【改造已经出现的弹窗】，而不是自己造弹窗。
 *
 * ================== 本类的做法 ==================
 *
 * 第一步先做【探测】，因为这决定了后面怎么做：
 *
 *   ① 弹窗到底会不会为 T6 出现？
 *   ② 弹窗的数据长什么样（base_view_data 里有什么）？
 *   ③ 弹窗的视图结构是什么（有哪些 TextView）？
 *
 * 这三件事靠猜没用 —— 之前降噪那边就是靠"先探测"才
 * 避免了一轮白改（当时照搬华为的类名全都不存在）。
 *
 * 拿到这些信息后，就能像改造设备空间条目那样，
 * 直接操作弹窗里的 View 加我们的降噪入口。
 */
final class AccessoryHook {

    private static final String TAG = "UGREEN-ACCESSORY";

    /** 目标耳机 MAC。 */
    private static final String TARGET_MAC = T6Config.mac();

    /** 弹窗宿主类（本机验证存在）。 */
    private static final String DIALOG_CLASS =
            "com.oplus.pantaconnect.appcore.devicediscovery.ui.DialogActivity";

    private AccessoryHook() {
    }

    // ------------------------------------------------------------------

    static void install(ClassLoader cl) {

        AccessoryLog.event("AccessoryHook 开始安装");

        Class<?> cls;
        try {
            cls = XposedHelpers.findClass(DIALOG_CLASS, cl);
            AccessoryLog.event("找到弹窗类: " + cls.getName());
        } catch (Throwable t) {
            AccessoryLog.event("找不到弹窗类: " + DIALOG_CLASS);
            return;
        }

        hookOnCreate(cls);
        hookTouch(cls);
        hookDialogClient(cl);

        AccessoryLog.event("AccessoryHook 安装结束");
    }

    // ------------------------------------------------------------------
    // DialogClientImpl：弹窗的"入口方法"
    // ------------------------------------------------------------------

    /**
     * Hook 弹窗客户端的全部方法，观察系统是【怎么决定弹窗、用什么数据弹】的。
     *
     * ================== 为什么挂这个类 ==================
     *
     * 从 dex 里挖到的方法名（都是这个类的）：
     *
     *     showThirdPartyHeadsetDialog     ← 弹「三方耳机」弹窗
     *     startThirdHeadSetConnect        ← 启动三方耳机连接
     *     startThirdHeadSetBound, data=   ← 三方耳机绑定（带数据）
     *     showPinDialog / silentConnect / startSharingConnect ...
     *
     * 说明 ColorOS 内置了三方耳机弹窗路径，只是 T6 没触发。
     *
     * 挂上它之后，只要系统尝试为某个设备弹窗，
     * 日志里就会有完整的方法名 + 参数，
     * 从而知道：
     *   · 触发条件是什么（哪些设备才走这条路）
     *   · 数据长什么样（我们能否构造一份给 T6）
     */
    private static void hookDialogClient(final ClassLoader cl) {

        /*
         * 注意：这个类在【进程刚启动时】可能还找不到 ——
         * 真机实测：
         *     ClassNotFoundException:
         *     com.oplus.pantaconnect.appcore.devicediscovery.controller.DialogClientImpl
         * 但类确实在 APK 的 classes.dex 里（已核实）。
         *
         * 原因推测：设备快连是插件式框架，
         *           这个类由子 ClassLoader 在初始化后才加载。
         *
         * 所以做成"延迟重试"：先立即试一次，
         * 失败就每隔几秒再试，直到成功为止（最多 10 次）。
         */
        new Thread(() -> {
            for (int attempt = 1; attempt <= 10; attempt++) {
                if (tryHookDialogClient(cl, attempt)) {
                    return;
                }
                try {
                    Thread.sleep(4000L);
                } catch (InterruptedException e) {
                    return;
                }
            }
            AccessoryLog.event("DialogClientImpl 重试 10 次仍未找到，放弃");
        }, "ugreen-acc-dialogclient").start();
    }

    /**
     * 尝试挂 DialogClientImpl。
     *
     * @return true 表示已成功挂上
     */
    private static boolean tryHookDialogClient(ClassLoader cl, int attempt) {

        final String clsName = "com.oplus.pantaconnect.appcore"
                + ".devicediscovery.controller.DialogClientImpl";

        Class<?> cls;
        try {
            cls = XposedHelpers.findClass(clsName, cl);
        } catch (Throwable t) {
            AccessoryLog.event("第 " + attempt + " 次找 DialogClientImpl 未果，稍后重试");
            return false;
        }

        AccessoryLog.event("找到弹窗客户端（第 " + attempt + " 次）: " + cls.getName());

        int hooked = 0;
        for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
            if (m.isSynthetic() || java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            StringBuilder sb = new StringBuilder();
                            sb.append("[DialogClient] ").append(param.method.getName());
                            Object[] args = param.args;
                            if (args != null && args.length > 0) {
                                sb.append(" 参数:");
                                for (int i = 0; i < args.length; i++) {
                                    sb.append(" [").append(i).append("]=");
                                    sb.append(brief(args[i]));
                                }
                            }
                            AccessoryLog.event(sb.toString());
                        } catch (Throwable ignored) {
                        }
                    }
                });
                hooked++;
            } catch (Throwable ignored) {
            }
        }
        AccessoryLog.event("已挂 DialogClientImpl 的 " + hooked + " 个方法");
        return true;
    }

    /** 把参数压成简短、可读的一行。 */
    private static String brief(Object o) {
        if (o == null) {
            return "null";
        }
        if (o instanceof byte[]) {
            return "byte[" + ((byte[]) o).length + "]";
        }
        if (o instanceof String) {
            String s = (String) o;
            return "\"" + (s.length() > 60 ? s.substring(0, 60) + "…" : s) + "\"";
        }
        String s = String.valueOf(o);
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }

    // ------------------------------------------------------------------
    // onCreate：弹窗出现时，把它的身份和数据记下来
    // ------------------------------------------------------------------

    private static void hookOnCreate(Class<?> cls) {

        try {
            XposedBridge.hookAllMethods(cls, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {

                    AccessoryLog.event("========== 设备快连弹窗出现了 ==========");

                    try {
                        Activity activity = (Activity) param.thisObject;
                        Intent intent = activity.getIntent();
                        dumpIntent(intent);

                        /*
                         * 延迟 1.2 秒再 dump 视图树 ——
                         * 弹窗内容是异步加载的，立刻 dump 会拿到空布局。
                         */
                        final Activity act = activity;
                        act.getWindow().getDecorView().postDelayed(() -> {
                            try {
                                dumpViewTree(act.getWindow().getDecorView(), 0);
                            } catch (Throwable t) {
                                AccessoryLog.event("dump 视图树失败: " + t);
                            }
                        }, 1200L);

                    } catch (Throwable t) {
                        AccessoryLog.event("onCreate 处理异常: " + t);
                    }
                }
            });

            AccessoryLog.event("已挂 DialogActivity.onCreate");

        } catch (Throwable t) {
            AccessoryLog.event("挂 onCreate 失败: " + t);
        }
    }

    /** 把 intent 里的所有额外数据打出来（重点是 base_view_data）。 */
    private static void dumpIntent(Intent intent) {

        if (intent == null) {
            AccessoryLog.event("  intent = null");
            return;
        }

        AccessoryLog.event("  action = " + intent.getAction());

        Bundle extras = intent.getExtras();
        if (extras == null) {
            AccessoryLog.event("  extras = null");
            return;
        }

        Set<String> keys = extras.keySet();
        AccessoryLog.event("  extras 键数量 = " + keys.size());

        for (String key : keys) {
            Object v = extras.get(key);
            AccessoryLog.event("    " + key + " = " + describe(v));

            // 字节数组里可能嵌着设备 MAC（protobuf 里是字符串）
            if (v instanceof byte[]) {
                checkForTargetMac(key, (byte[]) v);
            }
        }
    }

    private static String describe(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof byte[]) {
            byte[] b = (byte[]) v;
            return "byte[" + b.length + "] " + hex(b, 64);
        }
        String s = String.valueOf(v);
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    /**
     * 检查一段字节里是否包含 T6 的 MAC。
     *
     * 这是判断"这次弹窗是不是给 T6 弹的"最直接的办法 ——
     * 不需要理解 protobuf 结构。
     */
    private static void checkForTargetMac(String key, byte[] data) {

        try {
            String text = new String(data, Charset.forName("UTF-8"));

            // 直接匹配，以及大小写变体
            String upper = TARGET_MAC.toUpperCase();
            String lower = TARGET_MAC.toLowerCase();

            boolean hit = text.contains(upper) || text.contains(lower)
                    || text.contains(upper.replace(":", ""));

            if (hit) {
                AccessoryLog.event("  ★★★ 弹窗数据里发现 T6 的 MAC！（键=" + key + "）");
            }

            /*
             * 顺带捞出所有可读字符串，便于看清 protobuf 里装了什么。
             * 只打印长度 >= 4 的连续可读片段。
             */
            StringBuilder cur = new StringBuilder();
            List<String> found = new ArrayList<>();
            for (byte b : data) {
                char c = (char) (b & 0xFF);
                if (c >= 0x20 && c < 0x7F) {
                    cur.append(c);
                } else {
                    if (cur.length() >= 4) {
                        found.add(cur.toString());
                    }
                    cur.setLength(0);
                }
            }
            if (cur.length() >= 4) {
                found.add(cur.toString());
            }

            if (!found.isEmpty()) {
                AccessoryLog.event("  可读串(" + found.size() + "): "
                        + (found.size() > 25 ? found.subList(0, 25) + "…" : found));
            }

        } catch (Throwable t) {
            AccessoryLog.event("  解析 " + key + " 失败: " + t);
        }
    }

    // ------------------------------------------------------------------
    // dispatchTouchEvent：确认弹窗是否被用户操作
    // ------------------------------------------------------------------

    private static void hookTouch(Class<?> cls) {

        try {
            XposedBridge.hookAllMethods(cls, "dispatchTouchEvent", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        MotionEvent e = (MotionEvent) param.args[0];
                        // 只记按下，避免刷屏
                        if (e != null && e.getAction() == MotionEvent.ACTION_DOWN) {
                            AccessoryLog.event("  弹窗被触摸 @("
                                    + (int) e.getX() + "," + (int) e.getY() + ")");
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });

            AccessoryLog.event("已挂 DialogActivity.dispatchTouchEvent");

        } catch (Throwable t) {
            AccessoryLog.event("挂 dispatchTouchEvent 失败: " + t);
        }
    }

    // ------------------------------------------------------------------
    // 视图树 dump
    // ------------------------------------------------------------------

    /**
     * 递归打印视图树里所有 TextView 的文字。
     *
     * 目的：搞清弹窗的布局结构，之后就能像改造设备空间条目那样
     *       直接找到目标 View 并塞入我们的入口。
     */
    private static void dumpViewTree(View view, int depth) {

        if (view == null || depth > 12) {
            return;
        }

        StringBuilder indent = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            indent.append("  ");
        }

        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            String id = "";
            try {
                if (view.getId() != View.NO_ID) {
                    id = " id=" + view.getResources()
                            .getResourceEntryName(view.getId());
                }
            } catch (Throwable ignored) {
            }
            AccessoryLog.event("    " + indent + "TextView"
                    + id + " = \"" + text + "\"");
        }

        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) {
                dumpViewTree(g.getChildAt(i), depth + 1);
            }
        }
    }

    // ------------------------------------------------------------------

    private static String hex(byte[] b, int max) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(b.length, max);
        for (int i = 0; i < n; i++) {
            sb.append(String.format("%02X ", b[i]));
        }
        if (b.length > max) {
            sb.append("…(共 ").append(b.length).append(" 字节)");
        }
        return sb.toString().trim();
    }
}
