package com.mo.ugreenprotocolhook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 模块自身 APP 的开机自启接收器。
 *
 * ================== 为什么需要它 ==================
 *
 * 用户观察到的现象：
 *     每次重启后，要先手动打开一次本模块 APP，系统里的降噪控制才生效。
 *
 * 原因（Android 的既定行为）：
 *     新安装或刚更新过的应用会被系统标记为 【stopped 状态】。
 *     stopped 应用：
 *       · 收不到 BOOT_COMPLETED
 *       · 后台组件不能被拉起
 *     而每次重新构建安装模块，它就又回到 stopped。
 *
 * 有了这个接收器 + 一次手动启动之后：
 *     后续重启会自动收到 BOOT_COMPLETED，
 *     stopped 状态被清除，不再需要手动打开。
 *
 * ================== 重要说明（避免误解） ==================
 *
 * 本模块的 Hook【并不依赖这个 APP 是否运行】——
 * Hook 代码运行在被注入的目标进程（设备空间 / 无线耳机 / 蓝牙）里。
 *
 * 所以这个接收器解决的是"应用被系统冻结"的问题，
 * 而不是"Hook 需要 APP 存活"。
 * 之所以要做，是因为用户实测"打开一次就正常"，
 * 而打开一次恰好就是解除 stopped 状态的动作。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "UGREEN-BOOT";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();

        boolean isBoot =
                Intent.ACTION_BOOT_COMPLETED.equals(action)
                        || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                        || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action);

        if (!isBoot) {
            return;
        }

        Log.i(TAG, "开机完成，模块 APP 已被系统唤醒（stopped 状态已解除）");

        /*
         * 这里刻意不做任何事。
         *
         * 目的仅仅是"让系统认为本应用已被启动过"，
         * 从而解除 stopped 状态、保证后续开机也能收到广播。
         *
         * 真正的功能（Hook）跑在目标进程里，与此无关。
         */
    }
}
