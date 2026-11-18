package com.mo.ugreenforcoloros

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.UUID

/**
 * 后台常驻服务：持有耳机连接，处理系统 UI 发来的降噪请求。
 *
 * ================== 为什么需要它 ==================
 *
 * 降噪控制的完整链路是：
 *
 *     系统 UI（音量面板 / 设备空间）
 *         ↓ 广播 ACTION_SET_ANC
 *     【必须有人持有耳机 BLE 连接并发出命令】
 *         ↓
 *     T6 耳机
 *
 * 之前"持有连接"这件事由 MainActivity 负责，导致：
 *   · 每次都得手动打开 APP
 *   · 还要点"扫描 → 连接 → 订阅"
 *   · 退到后台久了连接就断
 *
 * 现在改成前台服务负责：
 *   · 开机自启（BootReceiver）
 *   · 自动连接上次的设备（地址存在 SharedPreferences）
 *   · 连接后自动订阅 Notify
 *   · 监听系统请求并直接发命令
 *
 * 用户从此完全不用打开 APP。
 *
 * ================== 与 MainActivity 的关系 ==================
 *
 * 两者互斥，避免两个 GATT 客户端抢同一个耳机：
 *   · 打开 MainActivity  → 先停掉本服务，由 Activity 接管（方便手动调试）
 *   · 关闭 MainActivity  → 重新启动本服务
 */
class T6Service : Service() {

    companion object {

        private const val TAG = "UGREEN-T6-SERVICE"

        private const val CHANNEL_ID = "ugreen_t6_background"
        private const val NOTIFICATION_ID = 1001

        private const val PREFS_NAME = "ugreen_t6"
        private const val KEY_LAST_ADDRESS = "last_address"

        private const val BLE_CMD_CHARACTERISTIC =
            "77777777-7777-7777-7777-777777777777"

        /** 服务是否在运行（供 MainActivity 判断是否需要接管）。 */
        @Volatile
        var running: Boolean = false
            private set

        /** 启动服务（内部处理前台服务要求）。 */
        fun start(context: Context) {
            try {
                val i = Intent(context, T6Service::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "启动服务失败", t)
            }
        }

        /** 停止服务。 */
        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, T6Service::class.java))
            } catch (t: Throwable) {
                Log.w(TAG, "停止服务失败", t)
            }
        }
    }

    private var t6Gatt: UgreenT6Gatt? = null

    @Volatile
    private var servicesReady = false

    @Volatile
    private var connected = false

    /** 接收系统 UI（音量面板 / 设备空间）发来的降噪请求。 */
    private val ancReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {
                val mode =
                    intent?.getIntExtra("mode", -1) ?: -1

                handleAncRequest(mode)
            }
        }

    // ------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()

        running = true

        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("正在连接耳机…"))

        registerAncReceiver()

        if (!hasConnectPermission()) {
            log("缺少 BLUETOOTH_CONNECT 权限，无法连接耳机")
            updateNotification("缺少蓝牙权限")
            return
        }

        connectSavedDevice()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        /*
         * START_STICKY：被系统回收后自动重启，
         * 保证降噪控制随时可用。
         */
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false

        try {
            unregisterReceiver(ancReceiver)
        } catch (_: Throwable) {
        }

        try {
            t6Gatt?.close()
        } catch (_: Throwable) {
        }
        t6Gatt = null

        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // 连接
    // ------------------------------------------------------------------

    private fun connectSavedDevice() {

        val address =
            try {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getString(KEY_LAST_ADDRESS, null)
            } catch (_: Throwable) {
                null
            }

        if (address == null) {
            /*
             * 还没连过任何设备 —— 没有目标可连。
             * 此时停掉自己，等用户手动连一次（地址会被保存）后
             * 开机自启/模块调用时就能自动恢复了。
             */
            log("没有保存的设备地址，服务退出（先手动连一次即可）")
            stopSelf()
            return
        }

        log("自动连接 $address")
        updateNotification("正在连接…")

        val gatt =
            UgreenT6Gatt(
                context = this,

                onLog = { line ->
                    // 服务里不刷屏，只把关键行写 logcat
                    if (line.contains("断开") ||
                        line.contains("连接成功") ||
                        line.contains("订阅")
                    ) {
                        Log.i(TAG, line)
                    }
                },

                onConnectionStateChanged = { isConnected ->
                    connected = isConnected
                    servicesReady = false

                    Log.i(TAG, "连接状态: $isConnected")

                    updateNotification(
                        if (isConnected) "已连接，正在就绪…" else "已断开，自动重连中…"
                    )
                },

                onServicesReady = { ready ->
                    servicesReady = ready

                    if (ready) {
                        /*
                         * 自动订阅 Notify。
                         *
                         * 这一步很关键：订阅之后
                         *   · 耳机状态才会通过 Notify 上报
                         *   · 断开重连后 GATT 类内部会自动重订阅
                         */
                        log("服务就绪，自动订阅 Notify")
                        try {
                            /*
                             * 必须用字段 t6Gatt，不能引用上面的局部变量 gatt ——
                             * 那个变量这时候还在构造中，Kotlin 会报
                             * "Unresolved reference"（自引用）。
                             * 这个回调是异步触发的，那时字段已经赋值好了。
                             */
                            t6Gatt?.enableAllCustomNotifications()
                        } catch (t: Throwable) {
                            Log.w(TAG, "订阅失败", t)
                        }
                        updateNotification("已就绪，可控制系统降噪")
                    }
                },

                onNotifyData = { data ->
                    // 电量桥：把状态包解析后写出去（与手动模式共用同一套逻辑）
                    try {
                        val r = UgreenT6BatteryBridge.parseStatusPacket(data)
                        if (r != null && r.third) {
                            UgreenT6BatteryBridge.write(
                                context = this,
                                mac = address,
                                left = r.first,
                                right = r.second
                            )
                        }
                    } catch (_: Throwable) {
                    }
                }
            )

        t6Gatt = gatt

        val ok = gatt.connect(address)
        if (!ok) {
            log("连接启动失败")
            updateNotification("连接启动失败")
        }
    }

    // ------------------------------------------------------------------
    // 系统降噪请求
    // ------------------------------------------------------------------

    private fun registerAncReceiver() {
        try {
            registerReceiver(
                ancReceiver,
                IntentFilter(MainActivity.ACTION_SET_ANC),
                Context.RECEIVER_EXPORTED
            )
            log("已注册系统降噪请求接收器")
        } catch (t: Throwable) {
            Log.w(TAG, "注册接收器失败", t)
        }
    }

    /**
     * 处理系统发来的降噪切换。
     *
     * @param mode ColorOS 模式值（5=降噪 2=通透 1=关闭）
     */
    private fun handleAncRequest(mode: Int) {

        log("收到系统降噪请求 mode=$mode")

        val mapped = UgreenT6Commands.forSystemMode(mode)
        if (mapped == null) {
            log("未知模式 $mode，忽略")
            return
        }

        val (data, label) = mapped

        val gatt = t6Gatt
        if (gatt == null || !servicesReady) {
            log("BLE 未就绪，无法执行 $label")
            updateNotification("未就绪，无法执行 $label")
            return
        }

        try {
            gatt.writeHex(
                UUID.fromString(BLE_CMD_CHARACTERISTIC),
                data,
                false
            )
            log("已发送 $label")
            updateNotification("当前：$label")
            broadcastState(mode)

        } catch (t: Throwable) {
            Log.w(TAG, "发送失败", t)
            log("发送 $label 失败：$t")
        }
    }

    /**
     * 广播新状态，让两个系统入口（音量面板 / 设备空间）保持同步。
     *
     * 与手动模式（MainActivity）行为一致 ——
     * 无论谁执行的命令，都广播同一件事。
     */
    private fun broadcastState(mode: Int) {
        try {
            val i = Intent(MainActivity.ACTION_ANC_CHANGED)
            i.putExtra("mode", mode)
            sendBroadcast(i)
            log("已广播降噪状态 mode=$mode")
        } catch (t: Throwable) {
            Log.w(TAG, "广播状态失败", t)
        }
    }

    // ------------------------------------------------------------------
    // 通知
    // ------------------------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        try {
            val nm = getSystemService(NotificationManager::class.java)
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "耳机后台连接",
                    NotificationManager.IMPORTANCE_MIN
                )
            channel.description = "保持与 UGREEN HiTune T6 的连接，以支持系统降噪控制"
            channel.setShowBadge(false)
            nm?.createNotificationChannel(channel)
        } catch (t: Throwable) {
            Log.w(TAG, "创建通知渠道失败", t)
        }
    }

    private fun buildNotification(text: String): Notification {

        val open =
            Intent(this, MainActivity::class.java)

        val pi =
            android.app.PendingIntent.getActivity(
                this,
                0,
                open,
                android.app.PendingIntent.FLAG_IMMUTABLE or
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )

        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setContentTitle("UGREEN HiTune T6")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            /* 低优先级：不打扰用户，只保证服务常驻 */
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------

    private fun hasConnectPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED

    private fun log(msg: String) {
        Log.i(TAG, msg)
    }
}
