package com.mo.ugreenforcoloros

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机自启：拉起后台服务，恢复与耳机的连接。
 *
 * 这样重启手机后，系统降噪控制（音量面板 / 设备空间）
 * 依然可以直接使用，用户不需要先打开 APP。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(
        context: Context?,
        intent: Intent?
    ) {
        val action = intent?.action ?: return

        val isBoot =
            action == Intent.ACTION_BOOT_COMPLETED ||
                    action == "android.intent.action.QUICKBOOT_POWERON" ||
                    action == "android.intent.action.LOCKED_BOOT_COMPLETED"

        if (!isBoot) {
            return
        }

        if (context == null) {
            return
        }

        Log.i("UGREEN-BOOT", "开机完成，启动 T6 后台服务")

        try {
            T6Service.start(context)
        } catch (t: Throwable) {
            Log.w("UGREEN-BOOT", "启动服务失败", t)
        }
    }
}
