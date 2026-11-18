package com.mo.ugreenprotocolhook;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 「设备空间」（com.heytap.mydevices）——蓝牙详情页的入口注入。
 *
 * ================== 设计来源 ==================
 *
 * 完全照搬 HuaweiPods 的成熟方案（已反编译确认）：
 *
 *     1. Hook BtDetailPageFragment.onViewCreated
 *     2. 反射遍历 Fragment 的 String 字段，找到设备 MAC
 *     3. findPreference("pref_bt_audio_settings")   ← 系统自带的条目
 *     4. Hook 该 preference 的绑定方法，
 *        在绑定时【直接改 itemView 的 TextView】+ 挂 OnClickListener
 *
 * ================== 真机验证记录（2026-10-03） ==================
 *
 * 探测全部成功：
 *     ✅ com.heytap.mydevices.plugin.bluetooth.fragment.BtDetailPageFragment
 *     ✅ 反射扫描到 MAC = 90:A0:BE:2D:5E:CA
 *     ✅ findPreference("pref_bt_audio_settings")
 *        → com.coui.appcompat.preference.COUIJumpPreference
 *
 * 但最初用 Preference API 失败：
 *     ❌ setTitle(String)   → NoSuchMethodError
 *     ❌ setSummary(String) → NoSuchMethodError
 *     ❌ androidx.preference.Preference$OnPreferenceClickListener
 *        → ClassNotFoundException
 *
 * 原因：这个 preference 是 OPPO 自己的 COUI 库实现，
 *       不是标准 androidx 的 Preference，API 不同。
 *
 * ★ 所以改成"直接操作 View"——
 *   这正是 HuaweiPods 的做法（它的 ap.s() 就是这么写的），
 *   好处是【完全不依赖具体 Preference 实现】，抗混淆、抗升级。
 */
final class MyDevicesHook {

    private static final String TAG = "UGREEN-MYDEVICES";

    /** 目标耳机 MAC。 */
    private static final String TARGET_MAC = T6Config.mac();

    /** 17.x 的蓝牙详情页 Fragment（本机验证存在）。 */
    private static final String FRAGMENT_CLASS =
            "com.heytap.mydevices.plugin.bluetooth.fragment.BtDetailPageFragment";

    /** 要接管的系统条目：蓝牙音频设置。 */
    private static final String PREF_KEY = "pref_bt_audio_settings";

    /** 显示标题。 */
    private static final String DEVICE_TITLE = "UGREEN HiTune T6";

    /** MAC 正则。 */
    private static final Pattern MAC_PATTERN =
            Pattern.compile("^(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$");

    /** 已被我们接管、正在等待绑定的 preference。 */
    private static final Set<Object> pendingPrefs =
            Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());

    /** 已改过的 itemView（避免重复挂监听）。 */
    private static final Set<View> patchedViews =
            Collections.newSetFromMap(new IdentityHashMap<View, Boolean>());

    /**
     * 已经挂过 onBindViewHolder 的类。
     *
     * 必要性：详情页每打开一次就会走到 attachOurEntry，
     * 如果不记，就会对同一个方法反复挂 hook，
     * 导致回调被触发多次（改文字重复执行、日志刷屏）。
     */
    private static final Set<Class<?>> hookedClasses =
            Collections.newSetFromMap(new IdentityHashMap<Class<?>, Boolean>());

    // ------------------------------------------------------------------
    // 降噪模式常量
    // ------------------------------------------------------------------

    /**
     * ColorOS 的降噪模式取值。
     *
     * ★ 这组值是【从 SystemUI 的资源表反查出来的】，不是猜的：
     *
     *     qs_noise_reduction             = "Noise control"        卡片标题
     *     qs_noise_reduction_open        = "Noise cancellation"   → mode 5   降噪
     *     qs_noise_reduction_transparent = "Transparency"         → mode 2   通透
     *     qs_noise_reduction_adaptive    = "Auto"                 → mode 10  自适应
     *     mode 1                                                   → 关闭
     *
     * [踩坑记录] 最初凭直觉写成 1=降噪 / 5=通透 / 2=关闭，
     *            结果三档标签全部错位（用户实测：
     *            选关显示通透、选开显示关闭、选环境音显示降噪）。
     *
     * 注意：这几个常量必须声明在 currentMode 之前 ——
     *       Java 的字段初始化不允许前向引用
     *       （否则报"非法前向引用"编译错误）。
     */
    private static final int MODE_NOISE = 5;
    private static final int MODE_TRANSPARENT = 2;
    private static final int MODE_OFF = 1;

    private static final String[] MODE_NAMES = {"降噪", "通透", "关闭"};
    private static final int[] MODE_IDS = {MODE_NOISE, MODE_TRANSPARENT, MODE_OFF};

    /**
     * 状态同步广播（由测试 APP 发出）。
     *
     * 解决"两个系统入口不同步"：
     *   本进程（设备空间）和「无线耳机」进程各记一份状态，
     *   在音量面板切换后，本进程的对话框还显示旧状态。
     *
     * 现在测试 APP 每次切换后广播新状态，
     * 本进程收到就更新 currentMode，下次弹窗/刷新摘要就是新值。
     */
    private static final String ACTION_ANC_CHANGED =
            "com.mo.ugreenforcoloros.ACTION_ANC_CHANGED";

    /** 状态同步接收器是否已注册（只需一次）。 */
    private static volatile boolean syncReceiverRegistered = false;

    /** 当前降噪模式。默认按"降噪"显示（5 = 降噪）。 */
    private static volatile int currentMode = MODE_NOISE;

    /** 主线程 Handler（刷新已显示列表项时用，UI 操作必须在主线程）。 */
    private static volatile android.os.Handler mainHandler;

    private MyDevicesHook() {
    }

    // ------------------------------------------------------------------

    static void install(ClassLoader cl) {

        MyDevicesLog.event("MyDevicesHook 开始安装");

        try {
            hookDetailPage(cl);
        } catch (Throwable t) {
            MyDevicesLog.event("hook 详情页失败: " + t);
        }

        MyDevicesLog.event("MyDevicesHook 安装结束");
    }

    // ------------------------------------------------------------------
    // 第一层：详情页打开时，找到 preference 并准备接管
    // ------------------------------------------------------------------

    private static void hookDetailPage(final ClassLoader cl) {

        Class<?> cls;
        try {
            cls = XposedHelpers.findClass(FRAGMENT_CLASS, cl);
        } catch (Throwable t) {
            MyDevicesLog.event("找不到 Fragment 类: " + FRAGMENT_CLASS);
            return;
        }

        MyDevicesLog.event("找到 Fragment: " + cls.getName());

        XposedBridge.hookAllMethods(cls, "onViewCreated", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {

                try {
                    Object fragment = param.thisObject;
                    String mac = findMacInFragment(fragment);

                    MyDevicesLog.event("[onViewCreated] 扫描到 MAC=" + mac);

                    if (mac == null || !TARGET_MAC.equalsIgnoreCase(mac)) {
                        MyDevicesLog.event("[onViewCreated] 非目标设备，跳过");
                        return;
                    }

                    MyDevicesLog.event("★ 检测到 T6 的详情页，开始接管入口");
                    attachOurEntry(fragment);

                } catch (Throwable t) {
                    MyDevicesLog.event("[onViewCreated] 处理异常: " + t);
                }
            }
        });

        MyDevicesLog.event("已挂 BtDetailPageFragment.onViewCreated");
    }

    /**
     * 反射遍历 Fragment 及父类的 String 字段，找 MAC。
     *
     * 不写死字段名，因为字段名可能随版本变化，
     * 但"有个字段存着本设备 MAC"是稳定事实。
     */
    private static String findMacInFragment(Object fragment) {

        if (fragment == null) {
            return null;
        }

        Class<?> cls = fragment.getClass();
        while (cls != null && !cls.equals(Object.class)) {
            for (Field f : cls.getDeclaredFields()) {
                if (f.getType() != String.class
                        || Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object v = f.get(fragment);
                    if (v instanceof String
                            && MAC_PATTERN.matcher((String) v).matches()) {
                        return (String) v;
                    }
                } catch (Throwable ignored) {
                }
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    /**
     * 找到系统条目，登记它，并 Hook 它的绑定时机。
     */
    private static void attachOurEntry(Object fragment) {

        Object pref = findPreference(fragment, PREF_KEY);
        if (pref == null) {
            MyDevicesLog.event("findPreference(" + PREF_KEY + ") 返回 null");
            return;
        }

        Class<?> prefCls = pref.getClass();
        MyDevicesLog.event("找到 preference: " + prefCls.getName());

        // 打印类链与方法名 —— 便于诊断不同机型/版本
        dumpPreferenceInfo(prefCls);

        // 登记，等绑定
        pendingPrefs.add(pref);

        // 【关键】Hook 这个 preference 类层次上的 onBindViewHolder
        hookPreferenceBinding(prefCls);
    }

    /** 诊断用：打印 preference 的继承链与方法签名。 */
    private static void dumpPreferenceInfo(Class<?> prefCls) {
        try {
            StringBuilder chain = new StringBuilder();
            Class<?> cur = prefCls;
            while (cur != null && !cur.equals(Object.class)) {
                chain.append(cur.getName()).append(" <- ");
                cur = cur.getSuperclass();
            }
            MyDevicesLog.event("  类链: " + chain);

            /*
             * 打印【完整签名】而不是只打印方法名。
             *
             * 教训：上一版只打了方法名，结果看到一堆
             *       A/A0/B/B0 这样的混淆名，完全无法判断
             *       哪个是绑定方法。带上参数类型才能识别。
             */
            cur = prefCls;
            while (cur != null && !cur.equals(Object.class)) {
                for (Method m : cur.getDeclaredMethods()) {
                    if (m.isSynthetic() || Modifier.isStatic(m.getModifiers())) {
                        continue;
                    }
                    Class<?>[] ps = m.getParameterTypes();
                    // 只看"可能带 ViewHolder"的方法，减少噪音
                    boolean interesting = false;
                    for (Class<?> p : ps) {
                        if (!p.isPrimitive()
                                && p != String.class
                                && p != CharSequence.class) {
                            interesting = true;
                            break;
                        }
                    }
                    if (interesting) {
                        MyDevicesLog.event("    "
                                + cur.getSimpleName() + "." + m.getName()
                                + "(" + paramTypes(m) + ")");
                    }
                }
                cur = cur.getSuperclass();
            }

        } catch (Throwable t) {
            MyDevicesLog.event("  dump 失败: " + t);
        }
    }

    private static String paramTypes(Method m) {
        StringBuilder sb = new StringBuilder();
        Class<?>[] ps = m.getParameterTypes();
        for (int i = 0; i < ps.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(ps[i].getSimpleName());
        }
        return sb.toString();
    }

    private static Object findPreference(Object fragment, String key) {

        Class<?> cls = fragment.getClass();
        while (cls != null && !cls.equals(Object.class)) {
            for (Method m : cls.getDeclaredMethods()) {
                if (!"findPreference".equals(m.getName())
                        || m.getParameterTypes().length != 1) {
                    continue;
                }
                try {
                    m.setAccessible(true);
                    return m.invoke(fragment, key);
                } catch (Throwable ignored) {
                }
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 第二层：preference 绑定时改 View
    // ------------------------------------------------------------------

    /**
     * Hook preference 的"绑定"方法 —— 【按签名识别，不看方法名】。
     *
     * ================== 为什么不能用方法名 ==================
     *
     * 真机日志显示 COUI 库被 R8 混淆：
     *
     *     com.coui.appcompat.preference.COUIJumpPreference
     *     方法: [A, A0, B, B0, C, C0, ... z, z0]
     *
     * 完全没有 onBindViewHolder 这个名字。
     *
     * ================== 识别策略 ==================
     *
     * 沿类层次，把【参数里可能带 ViewHolder 的方法】全部挂上，
     * 然后在回调里判断：某个参数是不是一个"持有 View 的对象"。
     *
     * 判据是 —— 该对象的字段里有 View 类型（那就是 itemView），
     * 这正是 HuaweiPods 的 `kf.K(holder, "itemView")` 想做的事，
     * 我们做得更宽：不写死字段名，只要字段类型是 View 就认。
     *
     * 这样即使方法名、字段名都被混淆，也能准确定位。
     */
    private static void hookPreferenceBinding(Class<?> prefCls) {

        int hooked = 0;
        Class<?> cur = prefCls;

        while (cur != null && !cur.equals(Object.class)) {

            if (hookedClasses.contains(cur)) {
                cur = cur.getSuperclass();
                continue;
            }
            hookedClasses.add(cur);

            for (final Method m : cur.getDeclaredMethods()) {

                if (!isCandidateBindMethod(m)) {
                    continue;
                }
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            tryPatchFromArgs(param);
                        }
                    });
                    hooked++;
                } catch (Throwable ignored) {
                    // 单个方法挂不上不影响其它
                }
            }
            cur = cur.getSuperclass();
        }

        /*
         * 注意日志的含义：
         *   本轮新挂 0 个【并不代表没生效】——
         *   详情页每打开一次都会走到这里，
         *   而 hook 只需要装一次，
         *   所以第二次之后这里必然显示 0。
         *   （之前这个措辞让我自己都误判过一轮。）
         */
        MyDevicesLog.event("本轮新挂 " + hooked + " 个候选绑定方法"
                + (hooked == 0
                ? "（该类 hook 之前已装好，属正常）"
                : ""));
    }

    /**
     * 判断一个方法是否是"可能的绑定方法"。
     *
     * 条件：
     *   · 非静态、非合成
     *   · 参数 1~2 个（绑定方法通常是 (ViewHolder) 或 (ViewHolder, int)）
     *   · 至少有一个参数是【非基础类型、非字符串】——
     *     因为 ViewHolder 一定是个对象
     */
    private static boolean isCandidateBindMethod(Method m) {

        if (m.isSynthetic() || Modifier.isStatic(m.getModifiers())) {
            return false;
        }
        Class<?>[] ps = m.getParameterTypes();
        if (ps.length < 1 || ps.length > 2) {
            return false;
        }
        for (Class<?> p : ps) {
            if (!p.isPrimitive()
                    && p != String.class
                    && p != CharSequence.class) {
                return true;
            }
        }
        return false;
    }

    /**
     * 绑定回调：如果这是我们的 preference，就从参数里找出 View 并改造。
     */
    private static void tryPatchFromArgs(XC_MethodHook.MethodHookParam param) {

        try {
            Object pref = param.thisObject;
            if (!pendingPrefs.contains(pref)) {
                return;
            }

            for (Object arg : param.args) {
                View view = extractView(arg);
                if (view != null) {
                    patchItemView(view);
                    return;
                }
            }

        } catch (Throwable t) {
            MyDevicesLog.event("tryPatchFromArgs 异常: " + t);
        }
    }

    /**
     * 从一个对象里找出它持有的 View（即 ViewHolder.itemView）。
     *
     * 不看字段名 —— 只看字段【类型】是不是 View。
     * 这样即使 itemView 被混淆改名也能找到。
     */
    private static View extractView(Object holder) {

        if (holder == null || holder instanceof View) {
            return null;
        }

        Class<?> c = holder.getClass();
        while (c != null && !c.equals(Object.class)) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                if (!View.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object v = f.get(holder);
                    if (v instanceof View) {
                        return (View) v;
                    }
                } catch (Throwable ignored) {
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    /**
     * 改造列表项的 View：改文字 + 挂点击。
     *
     * ================== 为什么每次绑定都要重挂监听 ==================
     *
     * 踩过的坑：最初只在第一次绑定时挂监听（用 patchedViews 去重），
     * 结果用户点击时跳到了系统蓝牙设置页。
     *
     * 原因：RecyclerView 会【复用】列表项 View。
     *   绑定 1：系统设自己的监听 → 我们设我们的      → 我们赢
     *   绑定 2（同一 View 复用）：系统重设自己的监听
     *                              → 我们被去重跳过   → 系统赢
     *
     * 所以监听必须【每次绑定都重设】，才能保证永远是我们最后设的。
     */
    private static void patchItemView(final View itemView) {

        MyDevicesLog.event("★ 命中我们的 preference，改 UI: "
                + itemView.getClass().getName());

        applyOurText(itemView);

        // 注册状态同步接收器（顺带拿到 Context）
        ensureSyncReceiver(itemView.getContext());

        // 每次都重挂 —— 覆盖系统在 bind 时设的点击处理
        itemView.setClickable(true);
        itemView.setOnClickListener(v -> {
            MyDevicesLog.event("用户点击了我们的入口");
            showNoiseDialog(v.getContext());
        });

        // 安全网：部分 COUI 布局会在子 View 上消费点击，
        // 那样 itemView 的监听就收不到事件。
        // 这里递归把子 View 的点击也接管过来。
        takeOverChildClicks(itemView);

        if (!patchedViews.contains(itemView)) {
            patchedViews.add(itemView);
            MyDevicesLog.event("已挂 itemView 点击监听（含子 View）");
        }
    }

    /**
     * 递归把子 View 的点击也导向我们的对话框。
     *
     * 只处理"本身可点击"或"是布局容器"的 View，
     * 避免破坏开关之类的交互控件（这里不需要，因为这个条目是跳转项）。
     */
    private static void takeOverChildClicks(View view) {
        try {
            if (!(view instanceof ViewGroup)) {
                return;
            }
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) {
                View child = g.getChildAt(i);
                child.setOnClickListener(v -> showNoiseDialog(v.getContext()));
                takeOverChildClicks(child);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 改 itemView 的标题与摘要 —— 直接操作 TextView。
     *
     * 这是 HuaweiPods 的核心手法（它的 ap.s()）：
     *   优先用 Android 标准 id（android.R.id.title / summary），
     *   这样不依赖任何自定义布局；
     *   找不到就退化为"递归收集 TextView 再按顺序改"。
     *
     * 【重要】不能用 Preference.setTitle/setSummary ——
     *   真机日志证明 COUIJumpPreference 没有这两个方法：
     *   NoSuchMethodError: COUIJumpPreference#setTitle[class java.lang.String]
     *   所以只能改 View，这也正是华为选择改 View 的原因。
     */
    private static void applyOurText(View view) {

        try {
            view.setEnabled(true);
            view.setAlpha(1.0f);
            view.setContentDescription(DEVICE_TITLE);

            String summary = buildSummary();

            TextView titleView = view.findViewById(android.R.id.title);
            TextView summaryView = view.findViewById(android.R.id.summary);

            if (titleView != null) {
                titleView.setText(DEVICE_TITLE);
                if (summaryView != null) {
                    summaryView.setText(summary);
                    summaryView.setVisibility(View.VISIBLE);
                }
                MyDevicesLog.event("  已用标准 id 改文字");
                return;
            }

            // 退路：递归收集 TextView，按顺序改
            List<TextView> tvs = collectTextViews(view);
            MyDevicesLog.event("  标准 id 未命中，收集到 TextView "
                    + tvs.size() + " 个");

            if (tvs.size() == 1) {
                tvs.get(0).setText(DEVICE_TITLE + " · " + summary);
            } else if (tvs.size() >= 2) {
                tvs.get(0).setText(DEVICE_TITLE);
                tvs.get(1).setText(summary);
                tvs.get(1).setVisibility(View.VISIBLE);
            }

        } catch (Throwable t) {
            MyDevicesLog.event("applyOurText 失败: " + t);
        }
    }

    /** 递归收集一个 View 树里的所有 TextView。 */
    private static List<TextView> collectTextViews(View view) {
        List<TextView> out = new ArrayList<>();
        collect(view, out);
        return out;
    }

    private static void collect(View view, List<TextView> out) {
        if (view instanceof TextView) {
            out.add((TextView) view);
        }
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) {
                collect(g.getChildAt(i), out);
            }
        }
    }

    // ------------------------------------------------------------------
    // 降噪控制对话框
    // ------------------------------------------------------------------

    /**
     * 刷新所有【已经显示出来】的列表项。
     *
     * ================== 为什么需要它 ==================
     *
     * 摘要文字（"当前：降噪 · 点按切换"）是在
     * Preference 绑定时写进 View 的。
     *
     * 状态变了之后，列表项并不会自动重新绑定，
     * 所以外面看到的还是旧文字 ——
     * 用户实测就是"点开后（对话框）是同步的，
     * 但外面的摘要不变，得退出再进来才对"。
     *
     * 这里主动把之前改造过的 View 重新刷一遍。
     *
     * 注意必须切到主线程 —— 广播可能在别的线程送达，
     * 而 View 操作只能在主线程。
     */
    private static void refreshVisibleItems() {

        try {
            android.os.Handler h = mainHandler;
            if (h == null) {
                h = new android.os.Handler(android.os.Looper.getMainLooper());
                mainHandler = h;
            }
            h.post(() -> {
                int ok = 0;
                for (View v : patchedViews) {
                    try {
                        applyOurText(v);
                        ok++;
                    } catch (Throwable ignored) {
                    }
                }
                MyDevicesLog.event("已刷新 " + ok + " 个列表项摘要 → "
                        + buildSummary());
            });
        } catch (Throwable t) {
            MyDevicesLog.event("刷新列表项失败: " + t);
        }
    }

    /**
     * 注册状态同步接收器（只需一次）。
     *
     * 收到测试 APP 广播的新状态后更新 currentMode，
     * 这样在【音量面板】切换后，【设备空间】的对话框和摘要
     * 也会显示正确的当前状态。
     */
    private static void ensureSyncReceiver(final Context ctx) {

        if (syncReceiverRegistered || ctx == null) {
            return;
        }
        syncReceiverRegistered = true;

        try {
            android.content.BroadcastReceiver receiver =
                    new android.content.BroadcastReceiver() {
                        @Override
                        public void onReceive(Context c, Intent intent) {
                            try {
                                if (intent == null) {
                                    return;
                                }
                                int mode = intent.getIntExtra("mode", -1);
                                if (mode <= 0) {
                                    return;
                                }
                                currentMode = mode;
                                MyDevicesLog.event("收到状态同步广播 → mode="
                                        + mode + "（" + modeName(mode) + "）");

                                // ★ 立即刷新外面列表项的摘要文字
                                refreshVisibleItems();
                            } catch (Throwable t) {
                                MyDevicesLog.event("处理状态同步广播失败: " + t);
                            }
                        }
                    };

            ctx.registerReceiver(
                    receiver,
                    new android.content.IntentFilter(ACTION_ANC_CHANGED),
                    Context.RECEIVER_EXPORTED);

            MyDevicesLog.event("已注册状态同步接收器（两侧入口将保持一致）");

        } catch (Throwable t) {
            MyDevicesLog.event("注册状态同步接收器失败: " + t);
        }
    }

    /** 模式值 → 中文名。 */
    private static String modeName(int mode) {
        if (mode == MODE_NOISE) {
            return "降噪";
        }
        if (mode == MODE_TRANSPARENT) {
            return "通透";
        }
        if (mode == MODE_OFF) {
            return "关闭";
        }
        return "未知(" + mode + ")";
    }

    private static void showNoiseDialog(Context ctx) {

        if (ctx == null) {
            return;
        }

        try {
            android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(ctx);
            b.setTitle("降噪模式");

            b.setSingleChoiceItems(MODE_NAMES, indexOfMode(currentMode),
                    (dialog, which) -> {
                        int mode = MODE_IDS[which];
                        currentMode = mode;
                        MyDevicesLog.event("用户选择模式 " + mode
                                + " (" + MODE_NAMES[which] + ")");
                        sendAnc(ctx, mode);

                        // 立即刷新外面列表项的摘要（不等重新绑定）
                        refreshVisibleItems();

                        dialog.dismiss();
                    });

            b.setNegativeButton("取消", null);
            b.show();

            MyDevicesLog.event("已弹出降噪选择对话框");

        } catch (Throwable t) {
            MyDevicesLog.event("构造对话框失败: " + t);
        }
    }

    private static int indexOfMode(int mode) {
        for (int i = 0; i < MODE_IDS.length; i++) {
            if (MODE_IDS[i] == mode) {
                return i;
            }
        }
        return 0;
    }

    /**
     * 执行降噪切换。
     *
     * ================== 优先直连，失败才回退 ==================
     *
     * 第一选择：模块自己连耳机发命令（T6BleSender）
     *   → 不依赖测试 APP，卸载 APP 也能用
     *
     * 回退：广播给测试 APP 代发
     *   → 万一模块直连失败（权限、被占用等），还有 APP 兜底
     *
     * 无论哪条路成功，最后都广播一次状态，保证两个系统入口同步。
     */
    private static void sendAnc(final Context ctx, final int mode) {

        final byte[] cmd = AncCommands.forSystemMode(mode);
        if (cmd == null) {
            MyDevicesLog.event("未知模式 " + mode + "，忽略");
            return;
        }

        final String label = AncCommands.labelOf(mode);

        T6BleSender.send(ctx, cmd, label, (success, detail) -> {

            if (success) {
                MyDevicesLog.event("★ 模块直连发送成功：" + label);
                broadcastState(ctx, mode);
                return;
            }

            MyDevicesLog.event("模块直连失败（" + detail + "），回退为广播给测试 APP");
            sendAncBroadcast(ctx, mode);
        });
    }

    /**
     * 广播状态变化，让另一个系统入口（音量面板）同步。
     *
     * 与测试 APP 发出的广播是同一个 action，
     * 所以无论命令由谁执行，两边都会保持一致。
     */
    private static void broadcastState(Context ctx, int mode) {
        try {
            Intent i = new Intent(ACTION_ANC_CHANGED);
            i.putExtra("mode", mode);
            ctx.sendBroadcast(i);
            MyDevicesLog.event("已广播状态 mode=" + mode);
        } catch (Throwable t) {
            MyDevicesLog.event("广播状态失败: " + t);
        }
    }

    /**
     * 回退路径：广播让测试 APP 执行 BLE 命令。
     *
     * 只在模块自己连不上耳机时才会走到这里。
     */
    private static void sendAncBroadcast(Context ctx, int mode) {

        try {
            Intent i = new Intent("com.mo.ugreenforcoloros.ACTION_SET_ANC");
            i.putExtra("mode", mode);
            i.putExtra("address", TARGET_MAC);
            i.setPackage("com.mo.ugreenforcoloros");
            ctx.sendBroadcast(i);

            MyDevicesLog.event("已发送降噪广播 mode=" + mode);

        } catch (Throwable t) {
            MyDevicesLog.event("发送广播失败: " + t);
        }
    }

    private static String buildSummary() {
        String state;
        if (currentMode == MODE_NOISE) {
            state = "降噪";
        } else if (currentMode == MODE_TRANSPARENT) {
            state = "通透";
        } else {
            state = "关闭";
        }
        return "当前：" + state + " · 点按切换降噪模式";
    }
}
