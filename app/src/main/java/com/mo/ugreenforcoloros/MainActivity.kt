package com.mo.ugreenforcoloros

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var buttonScan: Button
    private lateinit var buttonConnect: Button
    private lateinit var buttonNotify: Button
    private lateinit var buttonRead: Button
    private lateinit var buttonSppConnect: Button
    private lateinit var buttonSppChannel: Button
    private lateinit var buttonClearLog: Button
    private lateinit var buttonCopyGatt: Button
    private lateinit var buttonCopyLog: Button

    private lateinit var radioBle: RadioButton
    private lateinit var radioSpp: RadioButton
    private lateinit var commandContainer: LinearLayout

    private lateinit var textStatus: TextView
    private lateinit var textLog: TextView

    private lateinit var t6Scanner: UgreenT6Scanner
    private lateinit var t6Gatt: UgreenT6Gatt
    private lateinit var t6Spp: UgreenT6Spp

    private var t6Address: String? = null
    private var t6Connected = false

    /**
     * 用户意图：是否希望保持连接。
     *
     * 与 t6Connected（真实链路状态）分开管理。
     * 因为 autoConnect 会让链路自己反复重连，
     * 链路状态不能代表用户想连还是想断。
     */
    private var connectionIntent = false
    private var servicesReady = false

    /**
     * 事件锚点。
     *
     * 每次用户点一个按钮，就往日志里插一条
     * "===== 用户操作：xxx ====="。
     *
     * 这样在 77777777 的周期包海洋里，
     * 才能一眼看出"这个包是我点按钮之前还是之后来的"。
     */
    private var userEventIndex = 0

    companion object {

        /*
         * 日志字符上限。
         *
         * 约 40 万字符 ≈ 几千行，
         * 足够一次完整实验，又不会拖慢 UI。
         */
        private const val maxLogCharacters = 400_000

        /**
         * 命令发送目标特征。
         *
         * 77777777 同时具备 WRITE / WRITE_NOTIFY，
         * 是 BLE 侧最可能的命令通道。
         * 注意：耳机是否真的接受 BLE 写入尚未验证，
         *       这正是本次实验要回答的问题。
         */
        private const val BLE_CMD_CHARACTERISTIC =
            "77777777-7777-7777-7777-777777777777"

        /**
         * 来自「设备空间」的降噪切换请求。
         *
         * 链路：
         *   设备空间（系统耳机详情页）里点选降噪
         *       → MyDevicesHook 发这个广播（带 mode）
         *       → 本 APP 收到后发 BLE 命令给耳机
         *
         * 之所以要绕这一圈：
         *   设备空间进程没有耳机的 BLE 连接，
         *   连接在本 APP（或官方 APP 的协议桥）手里。
         */
        const val ACTION_SET_ANC =
            "com.mo.ugreenforcoloros.ACTION_SET_ANC"

        /**
         * 降噪状态已变化（由本 APP 发出，用于【同步两个系统入口】）。
         *
         * 背景：
         *   音量面板的状态记在「无线耳机」进程，
         *   设备空间的状态记在「设备空间」进程，
         *   两者是独立变量，天然不同步。
         *
         * 现在统一以本 APP 为准（它才是真正发 BLE 命令的一方）：
         *   每次发完命令 → 广播本 action → 两个钩子进程各自更新自己的状态。
         */
        const val ACTION_ANC_CHANGED =
            "com.mo.ugreenforcoloros.ACTION_ANC_CHANGED"

        /**
         * 记住上次连接的设备，用于【启动时自动重连】。
         *
         * 动机：每次测试都要"扫描 → 连接 → 订阅"三步太烦，
         *       地址不会变，所以存下来直接连。
         *       订阅已经在连接成功且服务发现后自动恢复，不用手动点。
         */
        private const val PREFS_NAME = "ugreen_t6"
        private const val KEY_LAST_ADDRESS = "last_address"
    }

    private val bluetoothManager by lazy {
        getSystemService(
            BluetoothManager::class.java
        )
    }

    private val handler =
        Handler(Looper.getMainLooper())

    private val stopScanRunnable =
        Runnable {
            t6Scanner.stopScan()
        }

    /**
     * 接收来自「设备空间」的降噪切换请求。
     *
     * 系统耳机详情页里的那个入口，点到降噪档位后
     * 会发 ACTION_SET_ANC 广播过来，由本 APP 执行 BLE 命令。
     */
    private val ancReceiver =
        object : android.content.BroadcastReceiver() {
            override fun onReceive(
                context: android.content.Context?,
                intent: android.content.Intent?
            ) {
                val mode =
                    intent?.getIntExtra("mode", -1) ?: -1

                setAncFromSystem(mode)
            }
        }

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts
                .RequestMultiplePermissions()
        ) { result ->

            val allGranted =
                result.values.all { it }

            if (allGranted) {

                appendLog(
                    "蓝牙权限已获得"
                )

                startBleScan()

            } else {

                appendLog(
                    "蓝牙权限未全部获得"
                )

                textStatus.text =
                    "请允许附近设备权限"
            }
        }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        /*
         * 与后台服务互斥。
         *
         * 原因：两者都用 UgreenT6Gatt 连同一个耳机，
         *       同时连接会互相抢（BLE 外设的连接数有限）。
         *
         * 规则：
         *   打开本界面 → 停掉服务，由界面接管（方便手动调试、看日志）
         *   关闭本界面 → 重新拉起服务（系统降噪控制继续可用）
         */
        T6Service.stop(this)

        setContentView(
            R.layout.activity_main
        )

        buttonScan =
            findViewById(
                R.id.buttonScan
            )

        buttonConnect =
            findViewById(
                R.id.buttonConnect
            )

        buttonNotify =
            findViewById(
                R.id.buttonNotify
            )

        buttonRead =
            findViewById(
                R.id.buttonRead
            )

        buttonSppConnect =
            findViewById(
                R.id.buttonSppConnect
            )

        buttonSppChannel =
            findViewById(
                R.id.buttonSppChannel
            )

        buttonClearLog =
            findViewById(
                R.id.buttonClearLog
            )

        radioBle =
            findViewById(
                R.id.radioBle
            )

        radioSpp =
            findViewById(
                R.id.radioSpp
            )

        commandContainer =
            findViewById(
                R.id.commandContainer
            )

        buttonCopyGatt =
            findViewById(
                R.id.buttonCopyGatt
            )

        buttonCopyLog =
            findViewById(
                R.id.buttonCopyLog
            )

        textStatus =
            findViewById(
                R.id.textStatus
            )

        textLog =
            findViewById(
                R.id.textLog
            )

        /*
         * 注册来自「设备空间」的降噪请求接收器。
         *
         * ★ 必须放在所有 findViewById 之后：
         *   下面的 appendLog 会写入 textLog，
         *   放在绑定前会抛 UninitializedPropertyAccessException 导致闪退
         *   （这个坑已经踩过一次，日志里明确写了 textLog has not been initialized）。
         *
         * 必须 EXPORTED：发送方是另一个应用（设备空间），
         * 非导出接收器收不到跨应用广播。
         */
        try {
            registerReceiver(
                ancReceiver,
                android.content.IntentFilter(ACTION_SET_ANC),
                android.content.Context.RECEIVER_EXPORTED
            )
            appendLog("已注册系统降噪请求接收器")
        } catch (t: Throwable) {
            appendLog("注册降噪接收器失败：$t")
        }

        /*
         * 启动时自动重连上次的设备。
         *
         * 目的：不用每次都"扫描 → 连接 → 订阅"。
         *   · 地址从 SharedPreferences 读（连接成功时存下）
         *   · 订阅会在服务发现后自动恢复（见 UgreenT6Gatt）
         *
         * 需要 BLUETOOTH_CONNECT 权限，没有就跳过（用户可手动扫描）。
         */
        try {
            val saved =
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getString(KEY_LAST_ADDRESS, null)

            val hasConnect =
                ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED

            if (saved != null && hasConnect) {

                t6Address = saved
                buttonConnect.isEnabled = true

                appendLog("")
                appendLog("检测到上次的设备，自动重连：$saved")

                connectToT6()

            } else if (saved != null) {
                appendLog("有上次的设备记录，但缺少蓝牙权限，需手动授权并扫描")
            }
        } catch (t: Throwable) {
            appendLog("自动重连失败：$t")
        }

        t6Scanner =
            UgreenT6Scanner(
                context = this,

                onDeviceFound = {
                        name,
                        address,
                        rssi ->

                    runOnUiThread {

                        appendDevice(
                            name,
                            address,
                            rssi
                        )

                        checkForT6(
                            name,
                            address
                        )
                    }
                },

                onScanFinished = {

                    runOnUiThread {

                        textStatus.text =
                            if (
                                t6Address != null
                            ) {
                                "扫描结束，已找到 T6"
                            } else {
                                "扫描结束，未找到 T6"
                            }
                    }
                }
            )

        t6Gatt =
            UgreenT6Gatt(
                context = this,

                onLog = { message ->

                    runOnUiThread {
                        appendLog(message)
                    }
                },

                onConnectionStateChanged = {
                        connected ->

                    runOnUiThread {

                        t6Connected =
                            connected

                        updateConnectButton()

                        if (connected) {

                            textStatus.text =
                                "T6 已连接"

                        } else {

                            textStatus.text =
                                "T6 未连接（自动重连中）"

                            buttonNotify
                                .isEnabled = false

                            buttonRead
                                .isEnabled = false
                        }
                    }
                },

                onServicesReady = {
                        ready ->

                    runOnUiThread {

                        servicesReady =
                            ready

                        buttonNotify
                            .isEnabled =
                            ready

                        buttonRead
                            .isEnabled =
                            ready
                    }
                },

                /*
                 * 原始 Notify 数据 -> 解析电量 -> 写桥文件。
                 *
                 * 系统桥（com.android.bluetooth 进程）会读这个文件
                 * 并调用 ColorOS 的电量通道。
                 */
                onNotifyData = { data ->

                    val r = UgreenT6BatteryBridge
                        .parseStatusPacket(data)

                    if (r != null && r.third) {

                        UgreenT6BatteryBridge.write(
                            context = this,
                            mac = t6Address ?: "unknown",
                            left = r.first,
                            right = r.second
                        )
                    }
                }
            )

        t6Spp =
            UgreenT6Spp(
                context = this,

                onLog = { message ->

                    runOnUiThread {
                        appendLog(message)
                    }
                }
            )

        buildCommandPanel()

        buttonScan.setOnClickListener {
            markUserEvent("点按【扫描附近 BLE 设备】")
            checkPermissionAndScan()
        }

        buttonConnect.setOnClickListener {

            /*
             * 三态按钮：
             *   用户想要连接（connectionIntent = true）  → 点击 = 连接
             *   用户想要断开（connectionIntent = false） → 点击 = 断开
             *
             * 之所以不看 t6Connected，是因为 autoConnect 让链路自己
             * 反复重连，链路状态无法代表用户意图，
             * 会导致"点两次都执行同一个分支"。
             */
            if (connectionIntent) {

                markUserEvent("点按【断开 T6】")

                connectionIntent = false
                updateConnectButton()

                t6Gatt.disconnect()

            } else {

                markUserEvent("点按【连接 T6】")

                connectionIntent = true
                updateConnectButton()

                connectToT6()
            }
        }

        buttonNotify.setOnClickListener {

            if (servicesReady) {

                markUserEvent("点按【订阅 T6 Notify】")

                appendLog("")
                appendLog(
                    "开始订阅 T6 Notify..."
                )

                t6Gatt
                    .enableAllCustomNotifications()
            }
        }

        buttonRead.setOnClickListener {

            if (servicesReady) {

                markUserEvent("点按【读取自定义特征】")

                appendLog("")
                appendLog(
                    "开始读取自定义 READ 特征..."
                )

                t6Gatt
                    .readAllCustomCharacteristics()
            }
        }

        buttonSppConnect.setOnClickListener {

            markUserEvent("点按【连接 T6（SPP / 官方通道）】")

            val addr = t6Address
            if (addr == null) {
                appendLog("尚未找到 T6，请先扫描")
                return@setOnClickListener
            }

            // BLE 与 SPP 会互相抢连接，先断开 BLE
            if (t6Connected) {
                appendLog("提示：先断开 BLE 连接，避免两个通道互相抢占")
                t6Gatt.disconnect()
            }

            appendLog("开始建立 SPP 连接（SDP 解析 UUID）...")
            t6Spp.connect(addr, useRawChannel = false)
        }

        buttonSppChannel.setOnClickListener {

            markUserEvent("点按【连接 T6（SPP 直连信道 13）】")

            val addr = t6Address
            if (addr == null) {
                appendLog("尚未找到 T6，请先扫描")
                return@setOnClickListener
            }

            if (t6Connected) {
                appendLog("提示：先断开 BLE 连接，避免两个通道互相抢占")
                t6Gatt.disconnect()
            }

            appendLog("开始建立 SPP 连接（直接信道 13）...")
            t6Spp.connect(addr, useRawChannel = true)
        }

        buttonClearLog.setOnClickListener {
            clearLog()
        }

        buttonCopyGatt.setOnClickListener {
            copyGattSummaryToClipboard()
        }

        buttonCopyLog.setOnClickListener {
            copyLogToClipboard()
        }

        appendLog(
            "UGREEN for ColorOS"
        )

        appendLog(
            "T6 GATT 测试程序"
        )

        appendLog(
            "构建：步骤1 日志时间轴版"
        )

        appendLog(
            "日志格式：#序号  分:秒.毫秒  +累计秒  正文"
        )

        appendLog(
            "准备就绪"
        )
    }

    /**
     * 根据"用户意图"刷新连接按钮的文字。
     *
     * 三态：
     *   connectionIntent = true  → 显示"断开 T6"（即使此刻正在自动重连）
     *   connectionIntent = false → 显示"连接 T6"
     */
    private fun updateConnectButton() {

        buttonConnect.text =
            if (connectionIntent) {
                "断开 T6"
            } else {
                "连接 T6"
            }
    }

    /**
     * 插入一条用户操作锚点。
     *
     * 目的是在 77777777 周期包的海洋里
     * 标出"人什么时候动了手"。
     */
    private fun markUserEvent(action: String) {

        userEventIndex += 1

        appendLog("")
        appendLog(
            "===== 用户操作[$userEventIndex] " +
                    "$action ====="
        )
    }

    /*
     * 清空日志。
     *
     * 只清 TextView，不动 GATT 状态，
     * 也不取消 Notify 订阅。
     * 这样"清空日志"之后接着抓的包才是干净的。
     */
    private fun clearLog() {

        textLog.text = ""

        appendLog(
            "日志已清空（GATT 连接与 Notify 订阅保持不变）"
        )

        textStatus.text =
            "日志已清空"
    }

    // ------------------------------------------------------------------
    // 命令面板
    // ------------------------------------------------------------------

    /**
     * 动态生成命令按钮。
     *
     * 命令全部来自官方 APP 的真机捕获值（见 UgreenT6Commands），
     * 我们【不自行计算校验】，直接原样回放。
     */
    private fun buildCommandPanel() {

        commandContainer.removeAllViews()

        for ((label, param, data) in UgreenT6Commands.menu()) {

            val button = Button(this)

            button.text = "$label   [$param]"
            button.textSize = 13f

            button.setOnClickListener {
                sendCommand(label, param, data)
            }

            commandContainer.addView(button)
        }
    }

    /**
     * 发送一条命令。
     *
     * 通道由界面上的单选按钮决定：
     *   · 走 BLE 写入 —— 尚未验证耳机是否接受，本次实验的重点
     *   · 走 SPP      —— 官方 APP 使用的通道，已知可行
     */
    /**
     * 处理来自系统（设备空间 / 音量面板）的降噪切换。
     *
     * ★ ColorOS 的模式取值 —— 从 SystemUI 资源表反查确认：
     *
     *     5  = 降噪      (qs_noise_reduction_open        = "Noise cancellation")
     *     2  = 通透      (qs_noise_reduction_transparent = "Transparency")
     *     1  = 关闭
     *     10 = 自适应    (qs_noise_reduction_adaptive    = "Auto"，T6 不支持)
     *
     * [踩坑] 最初按直觉写成 1=降噪/5=通透/2=关闭，
     *        导致系统显示的标签全部错位。
     *
     * 映射到 T6 的私有命令（全部真机验证过）。
     * 按产品决定："降噪"对应 T6 的【深度降噪】档。
     */
    private fun setAncFromSystem(mode: Int) {

        /*
         * 映射逻辑放在 UgreenT6Commands 里共用 ——
         * 后台服务（T6Service）也走同一个函数，
         * 避免两处各写一份而慢慢走偏。
         */
        val mapped =
            UgreenT6Commands.forSystemMode(mode)

        if (mapped == null) {
            appendLog("收到未知降噪模式：$mode（已忽略）")
            return
        }

        val data = mapped.first
        val label = mapped.second

        markUserEvent("【系统设备空间】切换降噪 mode=$mode → $label")

        if (!servicesReady) {
            appendLog("系统请求降噪 $label，但 BLE 未就绪，无法执行")
            textStatus.text = "BLE 未就绪"
            return
        }

        val uuid =
            java.util.UUID.fromString(BLE_CMD_CHARACTERISTIC)

        t6Gatt.writeHex(uuid, data, noResponse = false)

        textStatus.text = "系统请求：$label"
        appendLog("已响应系统降噪请求：$label")

        /*
         * 广播新状态，让两个系统入口（音量面板 / 设备空间）同步。
         *
         * 不做这一步的后果（用户实测）：
         *   在音量面板切了降噪，再去设备空间看，
         *   那边显示的还是旧状态 —— 两边各记各的。
         */
        try {
            val sync = Intent(ACTION_ANC_CHANGED)
            sync.putExtra("mode", mode)
            sendBroadcast(sync)
            appendLog("已广播降噪状态 mode=$mode（用于两个入口同步）")
        } catch (t: Throwable) {
            appendLog("广播降噪状态失败：$t")
        }
    }

    private fun sendCommand(
        label: String,
        param: String,
        data: ByteArray
    ) {

        val viaSpp = radioSpp.isChecked

        markUserEvent(
            "点按命令【$label】[$param]  通道=" +
                    if (viaSpp) "SPP" else "BLE"
        )

        if (viaSpp) {

            if (!t6Spp.isConnected()) {
                appendLog("SPP 未连接：请先点【③ 连接 T6（SPP）】")
                textStatus.text = "SPP 未连接"
                return
            }

            t6Spp.send(data)

        } else {

            if (!servicesReady) {
                appendLog("BLE 服务未就绪：请先连接并完成服务发现")
                textStatus.text = "BLE 未就绪"
                return
            }

            val uuid =
                java.util.UUID.fromString(BLE_CMD_CHARACTERISTIC)

            /*
             * writeHex 内部会先校验该特征是否支持 WRITE /
             * WRITE_NO_RESPONSE，不支持会直接报错，不会静默失败。
             */
            t6Gatt.writeHex(uuid, data, noResponse = false)
        }

        textStatus.text = "已发送：$label"
    }

    private fun checkPermissionAndScan() {

        val permissions =
            getRequiredPermissions()

        val allGranted =
            permissions.all {

                ContextCompat.checkSelfPermission(
                    this,
                    it
                ) ==
                        PackageManager.PERMISSION_GRANTED
            }

        if (!allGranted) {

            permissionLauncher.launch(
                permissions
            )

            return
        }

        val bluetoothAdapter =
            bluetoothManager.adapter

        if (bluetoothAdapter == null) {

            appendLog(
                "当前设备不支持蓝牙"
            )

            textStatus.text =
                "蓝牙不可用"

            return
        }

        if (!bluetoothAdapter.isEnabled) {

            appendLog(
                "请先打开手机蓝牙"
            )

            textStatus.text =
                "蓝牙未开启"

            return
        }

        startBleScan()
    }

    private fun startBleScan() {

        t6Address = null
        t6Connected = false
        servicesReady = false

        buttonConnect.isEnabled = false
        buttonNotify.isEnabled = false
        buttonRead.isEnabled = false

        textLog.text = ""

        textStatus.text =
            "扫描中..."

        appendLog(
            "开始扫描 BLE 设备"
        )

        appendLog(
            "扫描时间：10 秒"
        )

        appendLog("")

        t6Scanner.startScan()

        handler.removeCallbacks(
            stopScanRunnable
        )

        handler.postDelayed(
            stopScanRunnable,
            10_000L
        )
    }

    private fun checkForT6(
        name: String,
        address: String
    ) {

        if (
            name.contains(
                "UGREEN HiTune T6",
                ignoreCase = true
            )
        ) {

            if (
                t6Address != address
            ) {

                t6Address =
                    address

                buttonConnect.isEnabled =
                    true

                textStatus.text =
                    "已发现 UGREEN HiTune T6"

                appendLog(
                    ">>> 已锁定 T6"
                )

                appendLog(
                    "T6 地址：$address"
                )
            }
        }
    }

    private fun connectToT6() {

        val address =
            t6Address

        if (address == null) {

            appendLog(
                "尚未找到 T6，请先扫描"
            )

            return
        }

        appendLog("")
        appendLog(
            "开始连接 T6..."
        )

        /*
         * 记住地址，下次启动直接自动重连，
         * 省掉"扫描 → 连接 → 订阅"三步。
         */
        try {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_ADDRESS, address)
                .apply()
        } catch (_: Throwable) {
        }

        // 记录用户意图：希望保持连接
        connectionIntent = true
        updateConnectButton()

        textStatus.text =
            "连接中..."

        val started =
            t6Gatt.connect(address)

        if (!started) {

            textStatus.text =
                "连接启动失败"

            appendLog(
                "无法发起 GATT 连接"
            )
        }
    }

    private fun getRequiredPermissions():
            Array<String> {

        return if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.S
        ) {

            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )

        } else {

            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }

    private fun appendDevice(
        name: String,
        address: String,
        rssi: Int
    ) {

        appendLog(
            "$name\n" +
                    "地址：$address\n" +
                    "RSSI：$rssi dBm\n"
        )
    }

    private fun appendLog(
        message: String
    ) {

        textLog.append(
            "$message\n"
        )

        trimLogIfTooLong()
    }

    /*
     * 防止 77777777 周期包把日志撑爆。
     *
     * TextView 过长会拖慢 UI，
     * 这里的上限足够覆盖一次完整实验。
     */
    private fun trimLogIfTooLong() {

        val current =
            textLog.text
                ?: return

        if (current.length <= maxLogCharacters) {
            return
        }

        val text = current.toString()

        /*
         * 在保留区的起点附近找一个换行，
         * 避免把中间某一行切成两半。
         */
        val keepLength =
            text.length / 2

        var cutIndex =
            text.indexOf('\n', keepLength)

        if (cutIndex <= 0 || cutIndex >= text.length - 1) {
            cutIndex = keepLength
        } else {
            cutIndex += 1
        }

        textLog.text =
            "(日志过长，已丢弃最早部分)\n" +
                    text.substring(cutIndex)
    }

    private fun copyGattSummaryToClipboard() {

        val summary =
            t6Gatt.getGattSummary()

        if (summary.isBlank()) {

            textStatus.text =
                "还没有 GATT 数据"

            return
        }

        val clipboard =
            getSystemService(
                CLIPBOARD_SERVICE
            ) as ClipboardManager

        val clip =
            ClipData.newPlainText(
                "UGREEN T6 GATT",
                summary
            )

        clipboard.setPrimaryClip(
            clip
        )

        textStatus.text =
            "GATT 摘要已复制"
    }

    private fun copyLogToClipboard() {

        val clipboard =
            getSystemService(
                CLIPBOARD_SERVICE
            ) as ClipboardManager

        val clip =
            ClipData.newPlainText(
                "UGREEN for ColorOS 日志",
                textLog.text
            )

        clipboard.setPrimaryClip(
            clip
        )

        textStatus.text =
            "完整日志已复制"
    }

    override fun onDestroy() {

        /*
         * 交还给后台服务。
         *
         * 界面关闭后，服务会重新连接耳机，
         * 这样系统降噪控制（音量面板 / 设备空间）依然可用，
         * 用户不需要再打开本 APP。
         *
         * 注意：只在真正退出时启动（配置变更导致的重建不启，
         *       否则会来回切换连接）。
         */
        if (isFinishing) {
            T6Service.start(this)
        }

        handler.removeCallbacks(
            stopScanRunnable
        )

        // 注销系统降噪请求接收器
        try {
            unregisterReceiver(ancReceiver)
        } catch (_: Throwable) {
        }

        if (
            ::t6Scanner.isInitialized
        ) {

            t6Scanner.stopScan()
        }

        if (
            ::t6Gatt.isInitialized
        ) {

            t6Gatt.close()
        }

        if (
            ::t6Spp.isInitialized
        ) {

            t6Spp.close()
        }

        super.onDestroy()
    }
}