package com.mo.ugreenprotocolhook;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ContentProvider;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 「无线耳机」（com.oplus.melody）App 的降噪数据注入。
 *
 * ================== 背景（全部现场取证） ==================
 *
 * ColorOS 音量面板的降噪卡片，数据链是：
 *
 *     SystemUI 的 EarphoneController
 *         ↓ ContentResolver.query / call
 *     com.oplus.melody 的 EarphoneControlProvider
 *         ↓
 *     蓝牙栈 IOplusRemoteDevice
 *
 * 而 EarphoneControlProvider 有两道门控，T6 都过不去：
 *
 *     第 1 道：AbstractC0255b.o()（"活动耳机"）
 *              → melody 只认它 SDK 里的耳机
 *     第 2 道：WhitelistRepository.c(productId, name)（白名单）
 *              → 94 个型号全是 OPPO Enco / OnePlus Buds
 *              → 云端下发的加密白名单，无任何第三方
 *
 * 结果：query 直接 return null → SystemUI 拿不到数据 → 没有降噪 UI
 *
 * ================== 本类的做法 ==================
 *
 * 不去硬闯那两道门，而是【直接接管 query() 的返回值】：
 * 当 SystemUI 来查询时，对 T6 这个设备，
 * 我们直接返回一个内容正确的 Cursor。
 *
 * 好处：
 *   · 不修改任何文件（符合"只读手机"约束）
 *   · 不需要构造 WhitelistConfigDTO（绕开白名单）
 *   · SystemUI 完全不知道自己被骗了，UI 会正常渲染
 *
 * ================== 涉及的两个查询 ==================
 *
 *   melody_method_active_device    → 返回 {name, address}
 *   melody_method_noise_reduction  → 返回 {name, address, type, supports}
 */
final class MelodyProviderHook {

    private static final String TAG = "UGREEN-MELODY";

    /** 目标耳机 MAC。 */
    private static final String TARGET_MAC = T6Config.mac();

    /** Provider 类名（来自 AndroidManifest 现场提取）。 */
    private static final String PROVIDER_CLASS =
            "com.oplus.melody.provider.EarphoneControlProvider";

    /** 列名（来自 EarphoneControlProvider 的常量）。 */
    private static final String COLUMN_NAME = "name";
    private static final String COLUMN_ADDR = "address";
    private static final String COLUMN_TYPE = "type";
    private static final String COLUMN_SUPPORTS = "supports";

    /** 方法名常量。 */
    private static final String METHOD_ACTIVE_DEVICE = "melody_method_active_device";
    private static final String METHOD_NOISE_REDUCTION = "melody_method_noise_reduction";

    /**
     * Provider 的基础 URI。
     *
     * 与 SystemUI 的 EarphoneController.URI_EARPHONE 完全一致：
     *     content://com.oplus.melody.provider.EarphoneControlProvider
     *
     * 用于发"佩戴状态变更"通知（见 notifyWearState）。
     */
    private static final String URI_EARPHONE =
            "content://com.oplus.melody.provider.EarphoneControlProvider";

    /**
     * T6 支持的降噪模式集合。
     *
     * ★ 取值来自 SystemUI 的资源表（qs_noise_reduction_*），已确证：
     *
     *     NoiseReductionDetailTile.getNextNoiseReductionMode():
     *         int[] iArr = {1, 5, 10, 2};
     *         // 只保留 supports 里出现的值，并按这个顺序循环
     *
     *     getSecondaryLabel():
     *         mode 5  → "Noise cancellation"  降噪
     *         mode 2  → "Transparency"        通透
     *         mode 10 → "Auto"                自适应
     *         mode 1  → 默认标签              关闭
     *
     * [踩坑] 最初按直觉写成 1=降噪/5=通透/2=关闭，
     *        导致三档标签全部错位（用户实测）。
     *
     * 我们给 T6 声明：关闭(1) + 降噪(5) + 通透(2)
     * —— 不含自适应(10)，因为 T6 没有自适应档位。
     *
     * 循环顺序由 SystemUI 的 iArr 决定：1 → 5 → 2 → 1
     * 即：关闭 → 降噪 → 通透 → 关闭
     */
    private static final int[] SUPPORTED_NOISE_MODES = {1, 5, 2};

    /**
     * 当前模式。
     *
     * 这个值会被写进注入的 cursor 的 "type" 列，
     * SystemUI 直接用它来显示标签，所以初值要对。
     * 默认 5 = 降噪。
     */
    private static volatile int currentNoiseMode = 5;

    /**
     * 去重用的记录：上一次已处理的模式与时间。
     *
     * 真机日志显示一次点击会触发 3~4 次相同的 call 请求，
     * 不去重就会连发多条 BLE 命令（耳机可能因此卡顿）。
     */
    private static volatile int lastSentMode = -1;
    private static volatile long lastSentAt = 0L;

    /**
     * 状态同步广播（由测试 APP 发出）。
     *
     * 解决"两个系统入口不同步"：
     *   音量面板的状态在本进程，设备空间的状态在另一个进程，
     *   两边各记各的 → 在一边切换后另一边不更新。
     *
     * 现在统一以测试 APP 为准（它是真正发 BLE 命令的一方），
     * 每次切换后它广播新状态，本进程收到就更新自己并刷新系统 UI。
     */
    private static final String ACTION_ANC_CHANGED =
            "com.mo.ugreenforcoloros.ACTION_ANC_CHANGED";

    /** 状态同步接收器是否已注册（只需一次）。 */
    private static volatile boolean syncReceiverRegistered = false;

    /** Provider 的 Context，供同步后刷新系统 UI 用。 */
    private static volatile Context providerContext;

    private MelodyProviderHook() {
    }

    // ------------------------------------------------------------------

    static void install(ClassLoader cl) {

        MelodyLog.event("MelodyProviderHook 开始安装");

        try {
            Class<?> cls = XposedHelpers.findClass(PROVIDER_CLASS, cl);
            MelodyLog.event("找到 Provider 类: " + cls.getName());

            hookQuery(cls);
            hookCall(cls);

        } catch (Throwable t) {
            XposedBridge.log(TAG + " 安装失败: " + t);
            MelodyLog.event("安装失败: " + t);
        }

        MelodyLog.event("MelodyProviderHook 安装结束");
    }

    // ------------------------------------------------------------------
    // Hook query：接管 SystemUI 的读取
    // ------------------------------------------------------------------

    private static void hookQuery(Class<?> cls) {

        try {
            Method query = null;
            for (Method m : cls.getDeclaredMethods()) {
                if ("query".equals(m.getName())) {
                    Class<?>[] ps = m.getParameterTypes();
                    if (ps.length == 5
                            && ps[0] == Uri.class
                            && ps[1] == String[].class) {
                        query = m;
                        break;
                    }
                }
            }

            if (query == null) {
                MelodyLog.event("找不到 query(Uri, String[], String, String[], String)");
                return;
            }

            MelodyLog.event("已定位 query 方法: " + query);

            XposedBridge.hookMethod(query, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {

                    try {
                        Object result = param.getResult();

                        // 原本有结果就不干预（说明是真耳机或已支持）
                        if (result != null) {
                            return;
                        }

                        Uri uri = (Uri) param.args[0];
                        String selection = (String) param.args[2];
                        String[] selectionArgs = (String[]) param.args[3];

                        String path = uri == null ? null : uri.getLastPathSegment();
                        MelodyLog.event("[query] uri=" + path
                                + " selection=" + selection
                                + " args=" + java.util.Arrays.toString(selectionArgs)
                                + " 原结果=null");

                        Cursor fake = buildFakeCursor(path, selection, selectionArgs);
                        if (fake != null) {
                            param.setResult(fake);
                            MelodyLog.event("[query] ★ 已注入伪造结果 uri=" + path);

                            /*
                             * 注入成功后，主动"通知一次佩戴状态"。
                             *
                             * ================== 为什么需要这一步 ==================
                             *
                             * SystemUI 音量面板的降噪卡片显示条件是：
                             *
                             *   NoiseReductionDetailTile.isAvailable():
                             *       supportType["noise"].size() > 1   ← 我们已满足
                             *       && earState != 0                  ← ★ 卡在这里
                             *
                             * earState 只在 EarphoneController 的
                             * ContentObserver 收到【特定 flags】的变更通知时才更新：
                             *
                             *   if ((flags & 65280) == 1280) {
                             *       int wear = ((flags & 1) == 1) ? 1 : 0;
                             *       earState.setValue(0 | wear);
                             *   }
                             *
                             * 所以这里用 flags = 1281（0x501）通知一次：
                             *   1281 & 65280 = 1280  → 进入分支
                             *   1281 & 1     = 1     → 判定为"已佩戴"
                             *   → earState 变成 1（非 0）→ 卡片显示
                             *
                             * 注意：这不是 hook 系统界面，
                             *       只是 ContentProvider 正常的变更通知，
                             *       任何 App 对自己 provider 都能这么做。
                             */
                            notifyWearState(param.thisObject);

                            /*
                             * 顺便注册"状态同步接收器"。
                             *
                             * 这样在【设备空间】里切换降噪后，
                             * 测试 APP 广播新状态过来，
                             * 音量面板的标签也会同步更新。
                             */
                            if (param.thisObject instanceof ContentProvider) {
                                ensureSyncReceiver(
                                        ((ContentProvider) param.thisObject).getContext());
                            }
                        }

                    } catch (Throwable t) {
                        MelodyLog.event("[query] 注入异常: " + t);
                    }
                }
            });

        } catch (Throwable t) {
            MelodyLog.event("hookQuery 失败: " + t);
        }
    }

    /**
     * 构造伪造的 Cursor。
     *
     * 格式完全对照 EarphoneControlProvider 的真实实现：
     *
     *   active_device:
     *       new MatrixCursor({name, address})
     *       row: name, address
     *
     *   noise_reduction:
     *       new MatrixCursor({name, address, "type", supports})
     *       row: name, address, 当前模式, supports 的 JSON
     */
    private static Cursor buildFakeCursor(String path, String selection,
                                          String[] selectionArgs) {

        if (path == null) {
            return null;
        }

        // 目标设备名（取系统里的真实名字，没有就用固定值）
        String deviceName = resolveDeviceName();

        if (METHOD_ACTIVE_DEVICE.equals(path)) {

            MatrixCursor c = new MatrixCursor(new String[]{COLUMN_NAME, COLUMN_ADDR});
            c.addRow(new Object[]{deviceName, TARGET_MAC});
            MelodyLog.event("[注入] active_device 行=["
                    + deviceName + ", " + TARGET_MAC + "]");
            return c;
        }

        if (METHOD_NOISE_REDUCTION.equals(path)) {

            // 真实实现要求 selection == "address" 且 selectionArgs[0] == 设备 MAC
            if (!COLUMN_ADDR.equals(selection)) {
                MelodyLog.event("[注入] noise_reduction 跳过：selection=" + selection);
                return null;
            }
            if (selectionArgs == null || selectionArgs.length == 0) {
                MelodyLog.event("[注入] noise_reduction 跳过：selectionArgs 为空");
                return null;
            }
            if (!TARGET_MAC.equalsIgnoreCase(selectionArgs[0])) {
                MelodyLog.event("[注入] noise_reduction 跳过：MAC 不匹配 "
                        + selectionArgs[0]);
                return null;
            }

            String json = supportsJson();
            MatrixCursor c = new MatrixCursor(
                    new String[]{COLUMN_NAME, COLUMN_ADDR, COLUMN_TYPE, COLUMN_SUPPORTS});
            c.addRow(new Object[]{
                    deviceName,
                    TARGET_MAC,
                    currentNoiseMode,
                    json
            });
            MelodyLog.event("[注入] noise_reduction 行=["
                    + deviceName + ", " + TARGET_MAC + ", "
                    + currentNoiseMode + ", " + json + "]  ← SystemUI 需要 size>1");
            return c;
        }

        return null;
    }

    /**
     * 主动通知一次"佩戴状态变更"。
     *
     * 目的：让 SystemUI 的 EarphoneController 把 earState 从 0 变成 1，
     *       从而满足 NoiseReductionDetailTile.isAvailable() 的第二个条件。
     *
     * flags = 1281 (0x501)：
     *     1281 & 65280 = 1280  → SystemUI 进入"佩戴状态"分支
     *     1281 & 1     = 1     → 判定为已佩戴
     *
     * 用子线程延迟执行，避免在 query() 里同步触发
     * 内容变更造成重入。
     */
    private static void notifyWearState(final Object providerObj) {

        try {
            if (!(providerObj instanceof ContentProvider)) {
                return;
            }
            final Context ctx = ((ContentProvider) providerObj).getContext();
            if (ctx == null) {
                return;
            }

            new Thread(() -> {
                try {
                    Thread.sleep(500L);

                    Uri uri = Uri.parse(URI_EARPHONE);
                    ctx.getContentResolver().notifyChange(uri, null, 1281);

                    MelodyLog.event("已通知佩戴状态（flags=1281）"
                            + " → SystemUI earState 应为 1");

                } catch (Throwable t) {
                    MelodyLog.event("通知佩戴状态失败: " + t);
                }
            }, "ugreen-wear-notify").start();

        } catch (Throwable t) {
            MelodyLog.event("notifyWearState 异常: " + t);
        }
    }

    /**
     * 执行降噪切换（优先模块直连，失败回退广播给 APP）。
     *
     * 与设备空间侧完全一致的策略 ——
     * 这样卸载测试 APP 后，音量面板的降噪依然可用。
     */
    private static void sendAnc(Object providerObj, int mode) {

        if (!(providerObj instanceof ContentProvider)) {
            return;
        }
        final Context ctx = ((ContentProvider) providerObj).getContext();
        if (ctx == null) {
            return;
        }

        final byte[] cmd = AncCommands.forSystemMode(mode);
        if (cmd == null) {
            MelodyLog.event("未知模式 " + mode + "，忽略");
            return;
        }

        final String label = AncCommands.labelOf(mode);

        T6BleSender.send(ctx, cmd, label, (success, detail) -> {

            if (success) {
                MelodyLog.event("★ 模块直连发送成功：" + label);
                broadcastState(ctx, mode);
                return;
            }

            MelodyLog.event("模块直连失败（" + detail + "），回退为广播给测试 APP");
            sendAncBroadcast(providerObj, mode);
        });
    }

    /**
     * 广播状态变化，让另一个系统入口（设备空间）同步。
     */
    private static void broadcastState(Context ctx, int mode) {
        try {
            Intent i = new Intent(ACTION_ANC_CHANGED);
            i.putExtra("mode", mode);
            ctx.sendBroadcast(i);
            MelodyLog.event("已广播状态 mode=" + mode);
        } catch (Throwable t) {
            MelodyLog.event("广播状态失败: " + t);
        }
    }

    /**
     * 把降噪切换请求发给测试 APP。
     *
     * 为什么必须跨进程：
     *   本进程（无线耳机）没有耳机的 BLE 连接，
     *   连接在测试 APP（或官方 APP 的协议桥）手里。
     *
     * 走广播而不是文件：
     *   文件方案需要有人轮询，容易漏；
     *   广播是即时的，且测试 APP 已注册了接收器。
     *
     * @param providerObj ContentProvider 实例（用来取 Context）
     * @param mode        ColorOS 的降噪模式（1=降噪 5=通透 2=关闭）
     */
    private static void sendAncBroadcast(Object providerObj, int mode) {

        try {
            if (!(providerObj instanceof ContentProvider)) {
                MelodyLog.event("sendAncBroadcast: 拿不到 Provider，无法发广播");
                return;
            }
            Context ctx = ((ContentProvider) providerObj).getContext();
            if (ctx == null) {
                MelodyLog.event("sendAncBroadcast: Context 为 null");
                return;
            }

            Intent i = new Intent("com.mo.ugreenforcoloros.ACTION_SET_ANC");
            i.putExtra("mode", mode);
            i.putExtra("address", TARGET_MAC);
            i.setPackage("com.mo.ugreenforcoloros");
            ctx.sendBroadcast(i);

            MelodyLog.event("已发送降噪广播 mode=" + mode
                    + "（由测试 APP 执行 BLE 命令）");

        } catch (Throwable t) {
            MelodyLog.event("sendAncBroadcast 失败: " + t);
        }
    }

    /**
     * 注册状态同步接收器（只需一次）。
     *
     * 收到测试 APP 广播的新状态后：
     *   1. 更新本进程的 currentNoiseMode（这样下次注入的 type 就是新值）
     *   2. 通知 SystemUI 重新读取 → 音量面板标签立即同步
     *
     * 这样在【设备空间】里切换后，【音量面板】也会跟着变。
     */
    private static void ensureSyncReceiver(final Context ctx) {

        if (syncReceiverRegistered || ctx == null) {
            return;
        }
        providerContext = ctx;

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

                                currentNoiseMode = mode;
                                lastSentMode = mode;
                                lastSentAt = System.currentTimeMillis();

                                MelodyLog.event("收到状态同步广播 → mode=" + mode);

                                // 让 SystemUI 立即刷新音量面板的标签
                                notifyNoiseChanged(providerContext);

                            } catch (Throwable t) {
                                MelodyLog.event("处理状态同步广播失败: " + t);
                            }
                        }
                    };

            ctx.registerReceiver(
                    receiver,
                    new android.content.IntentFilter(ACTION_ANC_CHANGED),
                    Context.RECEIVER_EXPORTED);

            syncReceiverRegistered = true;
            MelodyLog.event("已注册状态同步接收器（两侧入口将保持一致）");

        } catch (Throwable t) {
            MelodyLog.event("注册状态同步接收器失败: " + t);
        }
    }

    /**
     * 通知 SystemUI 降噪状态已变化（触发它重新查询）。
     *
     * SystemUI 的 EarphoneController.ContentObserver：
     *     if ((flags & 65280) == 512) → onNoiseReductionTypeChanged()
     *
     * 所以这里用 flags = 512。
     * 不通知的话，用户必须退出音量面板再进来才会看到新标签。
     */
    private static void notifyNoiseChanged(final Object providerObj) {

        try {
            if (!(providerObj instanceof ContentProvider)) {
                return;
            }
            final Context ctx = ((ContentProvider) providerObj).getContext();
            if (ctx == null) {
                return;
            }

            new Thread(() -> {
                try {
                    Thread.sleep(300L);
                    ctx.getContentResolver().notifyChange(
                            Uri.parse(URI_EARPHONE), null, 512);
                    MelodyLog.event("已通知降噪状态变更（flags=512）→ 标签应即时刷新");
                } catch (Throwable t) {
                    MelodyLog.event("通知降噪状态失败: " + t);
                }
            }, "ugreen-noise-notify").start();

        } catch (Throwable t) {
            MelodyLog.event("notifyNoiseChanged 异常: " + t);
        }
    }

    /**
     * 把支持的模式拼成 JSON 数组字符串。
     *
     * 手写而不是用 Gson，原因：模块里不引入额外依赖，
     * 而且这个格式极其简单（就是一个整数数组）。
     */
    private static String supportsJson() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < SUPPORTED_NOISE_MODES.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(SUPPORTED_NOISE_MODES[i]);
        }
        return sb.append(']').toString();
    }

    private static String resolveDeviceName() {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null) {
                BluetoothDevice d = adapter.getRemoteDevice(TARGET_MAC);
                if (d != null) {
                    String n = d.getName();
                    if (n != null && n.length() > 0) {
                        return n;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "UGREEN HiTune T6";
    }

    // ------------------------------------------------------------------
    // Hook call：接管 SystemUI 的写入（用户点击降噪）
    // ------------------------------------------------------------------

    private static void hookCall(Class<?> cls) {

        try {
            Method call = null;
            for (Method m : cls.getDeclaredMethods()) {
                if ("call".equals(m.getName())) {
                    Class<?>[] ps = m.getParameterTypes();
                    if (ps.length == 3 && ps[0] == String.class && ps[1] == String.class) {
                        call = m;
                        break;
                    }
                }
            }

            if (call == null) {
                MelodyLog.event("找不到 call(String, String, Bundle)");
                return;
            }

            MelodyLog.event("已定位 call 方法: " + call);

            XposedBridge.hookMethod(call, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {

                    try {
                        String method = (String) param.args[0];
                        android.os.Bundle extras = (android.os.Bundle) param.args[2];

                        if (extras == null) {
                            return;
                        }

                        String addr = extras.getString(COLUMN_ADDR);
                        int type = extras.getInt(COLUMN_TYPE, -1);

                        MelodyLog.event("[call] method=" + method
                                + " addr=" + addr + " type=" + type);

                        if (METHOD_NOISE_REDUCTION.equals(method)
                                && addr != null
                                && TARGET_MAC.equalsIgnoreCase(addr)) {

                            /*
                             * 去重：同一次点击会被 SystemUI 触发多次
                             * （日志实测一次点击来了 3~4 次相同请求），
                             * 不去重就会连发多条 BLE 命令。
                             */
                            long now = System.currentTimeMillis();
                            if (type == lastSentMode && now - lastSentAt < 1000L) {
                                MelodyLog.event("[call] 重复请求 mode=" + type
                                        + "，已忽略（" + (now - lastSentAt) + "ms 内）");
                                return;
                            }
                            lastSentMode = type;
                            lastSentAt = now;

                            currentNoiseMode = type;
                            MelodyLog.event("★ 音量面板切换降噪到 " + type);

                            /*
                             * 记一份文件，便于事后排查。
                             */
                            AncRequest.write(type);

                            /*
                             * ★ 优先让模块自己连耳机发命令；
                             *   失败才回退到"广播给测试 APP 代发"。
                             */
                            sendAnc(param.thisObject, type);

                            /*
                             * ★ 通知 SystemUI 重新读取降噪状态。
                             *
                             * 不通知的后果（用户实测）：
                             *   点完之后标签不刷新，必须退出音量面板
                             *   再重新长按进来，才会显示新状态。
                             *
                             * flags = 512 对应 SystemUI 的
                             * EarphoneController.ContentObserver：
                             *   if ((flags & 65280) == 512) onNoiseReductionTypeChanged()
                             * → 触发回调 → 磁贴重新查询 → 标签立即更新
                             */
                            notifyNoiseChanged(param.thisObject);
                        }

                    } catch (Throwable t) {
                        MelodyLog.event("[call] 异常: " + t);
                    }
                }
            });

        } catch (Throwable t) {
            MelodyLog.event("hookCall 失败: " + t);
        }
    }
}
