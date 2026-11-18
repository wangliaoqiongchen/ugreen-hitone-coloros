package com.mo.ugreenforcoloros

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.ArrayDeque
import java.util.Locale

class UgreenT6Gatt(
    context: Context,
    onLog: (String) -> Unit,
    private val onConnectionStateChanged: (Boolean) -> Unit,
    private val onServicesReady: (Boolean) -> Unit,

    /**
     * 原始 Notify 数据回调。
     *
     * 用于把状态包交给上层解析（例如电量桥）。
     * 默认空实现，不影响原有行为。
     */
    private val onNotifyData: (ByteArray) -> Unit = {}
) {

    private val context = context.applicationContext

    /*
     * ============================================================
     * 日志时间轴
     *
     * 目的：让日志可以当证据用。
     *
     * 77777777 是周期性上报，没有时间戳就无法判断
     * "这个包是操作之前来的，还是操作之后来的"，
     * 也无法判断周期是多少毫秒。
     * ============================================================
     */

    /*
     * 时间轴原点。
     *
     * 在 UgreenT6Gatt 构造时确定，
     * 此时 MainActivity 刚刚创建，用户还没有做任何操作。
     */
    private val logStartedAtNanos =
        System.nanoTime()

    /*
     * 每行日志的全局序号。
     */
    private var logLineIndex = 0

    /*
     * 每个 UUID 独立计数：
     *
     * notifyCountByUuid  → 该 UUID 收到的第 N 个 RX 包
     * writeCountByUuid   → 该 UUID 发出的第 N 个 TX 包
     * cccdCountByUuid    → 该 UUID 第 N 次 CCCD 写入
     *
     * 分开计数是因为 77777777 是周期包，
     * 一个统一的序号会被刷得毫无意义。
     */
    private val notifyCountByUuid =
        mutableMapOf<String, Int>()

    private val writeCountByUuid =
        mutableMapOf<String, Int>()

    private val cccdCountByUuid =
        mutableMapOf<String, Int>()

    /*
     * 统一给每条日志加时间轴前缀。
     *
     * 格式：
     * #0007  00:12.345  +12.3s  正文
     *
     * 第一列：本条日志的全局序号
     * 第二列：从测试程序启动到现在的 分:秒.毫秒
     * 第三列：从测试程序启动到现在的 秒数（方便一眼看周期）
     */
    private val log: (String) -> Unit = { message ->

        logLineIndex += 1

        val elapsedNanos =
            System.nanoTime() - logStartedAtNanos

        val elapsedMillis =
            elapsedNanos / 1_000_000L

        val minutes =
            elapsedMillis / 60_000L

        val seconds =
            (elapsedMillis % 60_000L) / 1_000L

        val millis =
            elapsedMillis % 1_000L

        val elapsedSeconds =
            elapsedMillis / 1_000.0

        val prefix = String.format(
            Locale.US,
            "#%04d  %02d:%02d.%03d  +%.1fs  ",
            logLineIndex,
            minutes,
            seconds,
            millis,
            elapsedSeconds
        )

        onLog(prefix + message)
    }

    /*
     * 取某个 UUID 的下一个 RX 包序号。
     */
    private fun nextNotifyIndex(uuid: String): Int {

        val next =
            (notifyCountByUuid[uuid] ?: 0) + 1

        notifyCountByUuid[uuid] = next

        return next
    }

    /*
     * 取某个 UUID 的下一个 TX 包序号。
     */
    private fun nextWriteIndex(uuid: String): Int {

        val next =
            (writeCountByUuid[uuid] ?: 0) + 1

        writeCountByUuid[uuid] = next

        return next
    }

    /*
     * 取某个 UUID 的下一个 CCCD 写入序号。
     */
    private fun nextCccdIndex(uuid: String): Int {

        val next =
            (cccdCountByUuid[uuid] ?: 0) + 1

        cccdCountByUuid[uuid] = next

        return next
    }

    private var bluetoothGatt: BluetoothGatt? = null

    private var latestGattSummary = ""

    /*
     * READ 队列。
     *
     * BLE GATT 操作需要等待前一个操作完成，
     * 所以这里一次只读取一个 Characteristic。
     */
    private val readQueue =
        ArrayDeque<BluetoothGattCharacteristic>()

    private var currentReadCharacteristic:
            BluetoothGattCharacteristic? = null

    /*
     * Notify / Indicate 队列。
     */
    private val notifyQueue =
        ArrayDeque<BluetoothGattCharacteristic>()

    private var currentNotifyCharacteristic:
            BluetoothGattCharacteristic? = null
    /*
 * 当前正在执行的 WRITE。
 *
 * WRITE 请求需要等待 onCharacteristicWrite()
 * 回调后才能认为这次 GATT 操作结束。
 */
    private var currentWriteCharacteristic:
            BluetoothGattCharacteristic? = null

    /*
 * Notify 日志控制。
 *
 * paused = 暂停显示 Notify 日志，但不会取消 BLE Notify。
 * onlyChanges = 相同数据连续到来时只记录第一次。
 */
    private var notifyLoggingPaused = false
    private var notifyOnlyChanges = false

    /**
     * 用户是否已经订阅过 Notify。
     *
     * 重连后必须自动重新订阅：
     *   GATT 断开再连回时，系统会清掉 CCCD 订阅状态，
     *   如果不重新写 CCCD，就再也收不到 Notify。
     *
     * 现象就是"重连成功了，但数据全没了"。
     */
    private var notifyDesired = false

    /**
     * 用户意图标志：是否要断开。
     *
     * 为什么需要它：
     *   autoConnect = true 之后，链路会自己反复重连，
     *   界面看到的连接状态一直在跳，
     *   光靠"链路状态"无法判断用户到底想连还是想断。
     *
     * true  → 用户已要求断开，收到断开回调时【不要】自动重连
     * false → 正常状态，允许系统自动重连
     */
    private var stopReconnecting = false

    private val lastNotifyHexByUuid =
        mutableMapOf<String, String>()

    private val notifyRepeatCountByUuid =
        mutableMapOf<String, Int>()

    private val gattCallback =
        object : BluetoothGattCallback() {

            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int
            ) {

                log(
                    "GATT 状态变化：" +
                            "status=$status " +
                            "(0x${status.toString(16)})"
                )

                when (newState) {

                    BluetoothProfile.STATE_CONNECTED -> {

                        log(
                            "T6 GATT 连接成功"
                        )

                        onConnectionStateChanged(true)

                        if (!hasConnectPermission()) {

                            log(
                                "缺少 BLUETOOTH_CONNECT 权限"
                            )

                            return
                        }

                        log(
                            "开始发现 GATT Services..."
                        )

                        val started =
                            try {

                                gatt.discoverServices()

                            } catch (
                                e: SecurityException
                            ) {

                                log(
                                    "discoverServices 权限错误：" +
                                            e.message
                                )

                                false
                            }

                        log(
                            "discoverServices() " +
                                    "启动结果：$started"
                        )
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {

                        log(
                            "T6 GATT 已断开（status=$status）"
                        )

                        onConnectionStateChanged(false)
                        onServicesReady(false)

                        /*
                         * 只清队列，【不要】closeGatt()。
                         *
                         * 原因：connectGatt 用了 autoConnect = true，
                         *       只要 GATT 客户端还注册着，
                         *       系统就会在耳机重新可用时自动重连。
                         *
                         *       一旦在这里 closeGatt()，
                         *       GATT 客户端被注销，自动重连就永远失效了。
                         *
                         * 旧代码在这里 closeGatt() 正是
                         * "单耳切换后必须手动重连"的直接原因。
                         */
                        clearQueues()

                        if (stopReconnecting) {

                            /*
                             * 用户主动要求断开。
                             * 已经 closeGatt() 注销了客户端，
                             * 这里不再提示"自动重连"。
                             */
                            log("已按用户要求断开，不会自动重连")

                        } else if (status != BluetoothGatt.GATT_SUCCESS) {

                            /*
                             * status 非 0 表示这次断开是异常（出错而非正常掉线）。
                             * 这种情况下系统可能已经注销了 GATT 客户端，
                             * 自动重连不一定生效，这里明确提示，
                             * 由界面上的【连接 T6】按钮手动恢复。
                             */
                            log(
                                "警告：异常断开（status=$status），" +
                                        "自动重连可能失效，请手动重连"
                            )
                        } else {
                            log(
                                "等待系统自动重连" +
                                        "（autoConnect=true，耳机可用时会自动连回）"
                            )
                        }
                    }
                }
            }

            override fun onServicesDiscovered(
                gatt: BluetoothGatt,
                status: Int
            ) {

                log(
                    "Service Discovery 完成：" +
                            "status=$status " +
                            "(0x${status.toString(16)})"
                )

                if (
                    status !=
                    BluetoothGatt.GATT_SUCCESS
                ) {

                    log(
                        "Service Discovery 失败"
                    )

                    onServicesReady(false)

                    return
                }

                if (!hasConnectPermission()) {

                    log(
                        "缺少 BLUETOOTH_CONNECT 权限"
                    )

                    onServicesReady(false)

                    return
                }

                val services =
                    gatt.services

                latestGattSummary =
                    buildGattSummary(
                        services
                    )

                log(
                    "发现 Service 数量：" +
                            services.size
                )

                log(
                    "================================"
                )

                for (
                (serviceIndex, service)
                in services.withIndex()
                ) {

                    logService(
                        serviceIndex,
                        service
                    )
                }

                log(
                    "================================"
                )

                log(
                    "GATT 信息读取完成"
                )

                /*
                 * 重连后自动恢复 Notify 订阅。
                 *
                 * 关键：GATT 断开再连回时，系统会清掉 CCCD，
                 *       不重新订阅就永远收不到数据。
                 *       这正是"自动重连成功但监控断流"的原因。
                 */
                if (notifyDesired) {

                    log(
                        "检测到之前已订阅 Notify，" +
                                "重连后自动恢复订阅"
                    )

                    enableAllCustomNotifications()
                }

                onServicesReady(true)
            }

            /*
             * Android 13+ 使用这个回调。
             */
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic:
                BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int
            ) {

                handleCharacteristicRead(
                    characteristic,
                    value,
                    status
                )
            }

            /*
             * Android 12 及以下兼容。
             */
            @Suppress("DEPRECATION")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic:
                BluetoothGattCharacteristic,
                status: Int
            ) {

                val value =
                    characteristic.value
                        ?: ByteArray(0)

                handleCharacteristicRead(
                    characteristic,
                    value,
                    status
                )
            }

            private fun handleCharacteristicRead(
                characteristic:
                BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int
            ) {

                log(
                    "RX READ"
                )

                log(
                    "UUID: ${characteristic.uuid}"
                )

                log(
                    "Status: $status " +
                            "(0x${status.toString(16)})"
                )

                log(
                    "Length: ${value.size}"
                )

                log(
                    "HEX: " +
                            UgreenT6Hex.toHex(value)
                )

                log("")

                currentReadCharacteristic = null

                startNextRead()
            }

            /*
             * Android 13+。
             */
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic:
                BluetoothGattCharacteristic,
                value: ByteArray
            ) {

                handleCharacteristicChanged(
                    characteristic,
                    value
                )
            }

            /*
             * Android 12 及以下兼容。
             */
            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic:
                BluetoothGattCharacteristic
            ) {

                val value =
                    characteristic.value
                        ?: ByteArray(0)

                handleCharacteristicChanged(
                    characteristic,
                    value
                )
            }

            private fun handleCharacteristicChanged(
                characteristic:
                BluetoothGattCharacteristic,
                value: ByteArray
            ) {

                val uuid = characteristic.uuid.toString()
                val hex = UgreenT6Hex.toHex(value)

                /*
                 * 把原始数据交给上层（例如电量桥）。
                 *
                 * 放在"日志暂停/去重"判断之前：
                 * 那些开关只影响日志显示，
                 * 不应该影响数据解析。
                 */
                try {
                    onNotifyData(value)
                } catch (_: Throwable) {
                    // 上层解析出错不能影响 BLE 主流程
                }

                /*
                 * 每个 UUID 独立编号。
                 *
                 * 注意：编号在"暂停日志"之前就分配。
                 * 因为暂停被跳过的包也是真实存在的包，
                 * 编号必须继续累加，否则恢复日志后
                 * #N 会出现断层，无法判断中间漏了多少包。
                 */
                val notifyIndex =
                    nextNotifyIndex(uuid)

                /*
                 * 暂停的是"日志显示"，不是 BLE Notify。
                 * T6 仍然会继续发送 Notify。
                 */
                if (notifyLoggingPaused) {
                    return
                }

                /*
                 * 开启"仅显示变化"后，
                 * 相同 UUID + 相同 HEX 连续出现时不重复打印。
                 */
                if (notifyOnlyChanges) {

                    val lastHex =
                        lastNotifyHexByUuid[uuid]

                    if (lastHex == hex) {

                        val currentCount =
                            notifyRepeatCountByUuid[uuid]
                                ?: 0

                        notifyRepeatCountByUuid[uuid] =
                            currentCount + 1

                        return
                    }

                    /*
                     * 数据发生变化。
                     * 如果前面有被过滤掉的重复包，
                     * 在这里补一条统计信息。
                     */
                    val repeatCount =
                        notifyRepeatCountByUuid.remove(uuid)
                            ?: 0

                    if (repeatCount > 0) {

                        log(
                            "重复 Notify ×$repeatCount"
                        )

                        log("")
                    }
                }

                lastNotifyHexByUuid[uuid] = hex

                log(
                    "<<< RX NOTIFY #$notifyIndex"
                )

                log(
                    "UUID: $uuid"
                )

                log(
                    "Length: ${value.size}"
                )

                log(
                    "HEX: $hex"
                )

                log("")
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor:
                BluetoothGattDescriptor,
                status: Int
            ) {

                val uuid =
                    descriptor.characteristic
                        .uuid.toString()

                log(
                    "CCCD 写入完成 #" +
                            nextCccdIndex(uuid)
                )

                log(
                    "Characteristic: $uuid"
                )

                log(
                    "Descriptor: " +
                            descriptor.uuid
                )

                log(
                    "Status: $status " +
                            "(0x${status.toString(16)})"
                )

                log("")

                currentNotifyCharacteristic = null

                startNextNotify()
            }
            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {

                val uuid =
                    characteristic.uuid.toString()

                log("<<< TX RESULT")

                log(
                    "UUID: $uuid"
                )

                log(
                    "Status: $status " +
                            "(0x${status.toString(16)})"
                )

                log("")

                currentWriteCharacteristic = null
            }
        }

    fun connect(
        address: String
    ): Boolean {

        if (!hasConnectPermission()) {

            log(
                "缺少 BLUETOOTH_CONNECT 权限"
            )

            return false
        }

        closeGatt()

        /*
         * 用户主动连接，清除"要断开"的意图。
         */
        stopReconnecting = false

        val bluetoothManager =
            context.getSystemService(
                Context.BLUETOOTH_SERVICE
            ) as android.bluetooth.BluetoothManager

        val bluetoothAdapter =
            bluetoothManager.adapter

        if (bluetoothAdapter == null) {

            log(
                "BluetoothAdapter 不可用"
            )

            return false
        }

        val device =
            try {

                bluetoothAdapter
                    .getRemoteDevice(address)

            } catch (
                e: IllegalArgumentException
            ) {

                log(
                    "MAC 地址无效：$address"
                )

                return false
            }

        log(
            "准备连接：${device.address}"
        )

        try {

            /*
             * autoConnect = true 是关键。
             *
             * 【问题背景】
             * T6 固件在"单耳"过渡时会主动断开 BLE 链路
             * （实测：状态位 BYTE4 从 0x01 变 0x08 后约 25ms 断链）。
             * 这不是我们代码的 bug —— 官方"声学"APP 也一样，
             * 取证日志显示它在一次会话里断连 29 次、重连 30 次。
             *
             * 【为什么用 autoConnect = true】
             * autoConnect = false 时：
             *     系统只发起一次连接，断开后就此结束，
             *     必须由我们自己轮询重连，否则永远连不回来。
             *
             * autoConnect = true 时：
             *     系统会在后台【持续自动重连】，
             *     只要耳机重新出现在可连接状态就会连上。
             *     这正是我们需要的"断链自愈"。
             *
             * 文档定义（BluetoothDevice.connectGatt）：
             *     autoConnect – Whether to directly connect to the remote
             *     device (false) or to automatically connect as soon as the
             *     remote device becomes available (true).
             */
            bluetoothGatt =
                device.connectGatt(
                    context,
                    true,
                    gattCallback,
                    BluetoothDevice.TRANSPORT_LE
                )

        } catch (
            e: SecurityException
        ) {

            log(
                "connectGatt 权限错误：" +
                        e.message
            )

            return false

        } catch (e: Exception) {

            log(
                "connectGatt 失败：" +
                        e.message
            )

            return false
        }

        if (bluetoothGatt == null) {

            log(
                "connectGatt 返回 null"
            )

            return false
        }

        log(
            "connectGatt 已发起"
        )

        return true
    }

    /*
     * 开启所有自定义 Notify / Indicate。
     */
    fun enableAllCustomNotifications() {

        /*
         * 记下"用户希望订阅"。
         * 重连后在 onServicesDiscovered 里会据此自动恢复订阅。
         */
        notifyDesired = true

        val gatt =
            bluetoothGatt

        if (gatt == null) {

            log(
                "当前没有 GATT 连接"
            )

            return
        }

        if (!hasConnectPermission()) {

            log(
                "缺少 BLUETOOTH_CONNECT 权限"
            )

            return
        }

        notifyQueue.clear()
        currentNotifyCharacteristic = null

        for (service in gatt.services) {

            if (isStandardService(service)) {
                continue
            }

            for (
            characteristic
            in service.characteristics
            ) {

                val properties =
                    characteristic.properties

                val supportsNotify =
                    properties and
                            BluetoothGattCharacteristic
                                .PROPERTY_NOTIFY != 0

                val supportsIndicate =
                    properties and
                            BluetoothGattCharacteristic
                                .PROPERTY_INDICATE != 0

                if (
                    supportsNotify ||
                    supportsIndicate
                ) {

                    notifyQueue.add(
                        characteristic
                    )
                }
            }
        }

        log(
            "发现可订阅特征：" +
                    notifyQueue.size
        )

        startNextNotify()
    }

    private fun startNextNotify() {

        val gatt =
            bluetoothGatt

        if (gatt == null) {

            notifyQueue.clear()
            return
        }

        if (
            currentNotifyCharacteristic != null
        ) {

            return
        }

        if (notifyQueue.isEmpty()) {

            log(
                "所有 Notify / Indicate 已处理完成"
            )

            return
        }

        val characteristic =
            notifyQueue.removeFirst()

        currentNotifyCharacteristic =
            characteristic

        val properties =
            characteristic.properties

        val supportsIndicate =
            properties and
                    BluetoothGattCharacteristic
                        .PROPERTY_INDICATE != 0

        val supportsNotify =
            properties and
                    BluetoothGattCharacteristic
                        .PROPERTY_NOTIFY != 0

        log(
            "开始订阅：${characteristic.uuid}"
        )

        log(
            "类型：" +
                    when {
                        supportsNotify ->
                            "NOTIFY"

                        supportsIndicate ->
                            "INDICATE"

                        else ->
                            "UNKNOWN"
                    }
        )

        val localEnabled =
            try {

                gatt.setCharacteristicNotification(
                    characteristic,
                    true
                )

            } catch (
                e: SecurityException
            ) {

                log(
                    "设置本地 Notify 失败：" +
                            e.message
                )

                false
            }

        log(
            "setCharacteristicNotification：" +
                    localEnabled
        )

        if (!localEnabled) {

            currentNotifyCharacteristic = null
            startNextNotify()
            return
        }

        val cccd =
            characteristic.descriptors
                .firstOrNull {
                    it.uuid ==
                            java.util.UUID.fromString(
                                "00002902-0000-1000-8000-00805f9b34fb"
                            )
                }

        if (cccd == null) {

            log(
                "没有找到 CCCD：${characteristic.uuid}"
            )

            currentNotifyCharacteristic = null

            startNextNotify()

            return
        }

        val value =
            if (supportsIndicate) {
                BluetoothGattDescriptor
                    .ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor
                    .ENABLE_NOTIFICATION_VALUE
            }

        val started =
            try {

                if (
                    Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.TIRAMISU
                ) {

                    val result =
                        gatt.writeDescriptor(
                            cccd,
                            value
                        )

                    result ==
                            android.bluetooth
                                .BluetoothStatusCodes
                                .SUCCESS

                } else {

                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(
                        cccd.apply {
                            this.value = value
                        }
                    )
                }

            } catch (
                e: SecurityException
            ) {

                log(
                    "写 CCCD 失败：" +
                            e.message
                )

                false
            }

        log(
            "CCCD 写入启动：" +
                    started
        )

        if (!started) {

            currentNotifyCharacteristic = null
            startNextNotify()
        }
    }

    /*
     * 读取所有自定义 Service 中支持 READ 的特征。
     */
    fun readAllCustomCharacteristics() {

        val gatt =
            bluetoothGatt

        if (gatt == null) {

            log(
                "当前没有 GATT 连接"
            )

            return
        }

        if (!hasConnectPermission()) {

            log(
                "缺少 BLUETOOTH_CONNECT 权限"
            )

            return
        }

        readQueue.clear()
        currentReadCharacteristic = null

        for (service in gatt.services) {

            if (isStandardService(service)) {
                continue
            }

            for (
            characteristic
            in service.characteristics
            ) {

                val readable =
                    characteristic.properties and
                            BluetoothGattCharacteristic
                                .PROPERTY_READ != 0

                if (readable) {

                    readQueue.add(
                        characteristic
                    )
                }
            }
        }

        log(
            "发现可读取特征：" +
                    readQueue.size
        )

        startNextRead()
    }

    private fun startNextRead() {

        val gatt =
            bluetoothGatt

        if (gatt == null) {

            readQueue.clear()
            return
        }

        if (
            currentReadCharacteristic != null
        ) {

            return
        }

        if (readQueue.isEmpty()) {

            log(
                "所有自定义 READ 特征读取完成"
            )

            return
        }

        val characteristic =
            readQueue.removeFirst()

        currentReadCharacteristic =
            characteristic

        log(
            "开始读取：${characteristic.uuid}"
        )

        val started =
            try {

                gatt.readCharacteristic(
                    characteristic
                )

            } catch (
                e: SecurityException
            ) {

                log(
                    "读取失败：" +
                            e.message
                )

                false
            }

        log(
            "readCharacteristic()：" +
                    started
        )

        if (!started) {

            currentReadCharacteristic = null
            startNextRead()
        }
    }

    fun writeHex(
        uuid: java.util.UUID,
        data: ByteArray,
        noResponse: Boolean
    ) {

        val gatt = bluetoothGatt

        if (gatt == null) {
            log("当前没有 GATT 连接")
            return
        }

        if (!hasConnectPermission()) {
            log("缺少 BLUETOOTH_CONNECT 权限")
            return
        }

        if (currentWriteCharacteristic != null) {
            log("当前正在等待上一次 WRITE 完成")
            return
        }

        if (currentReadCharacteristic != null) {
            log("当前正在执行 READ，请等待完成")
            return
        }

        val characteristic =
            gatt.services
                .asSequence()
                .flatMap { it.characteristics.asSequence() }
                .firstOrNull {
                    it.uuid == uuid
                }

        if (characteristic == null) {
            log("找不到 Characteristic：$uuid")
            return
        }

        val properties = characteristic.properties

        val supportsWrite =
            properties and
                    BluetoothGattCharacteristic.PROPERTY_WRITE != 0

        val supportsWriteNoResponse =
            properties and
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0

        if (noResponse && !supportsWriteNoResponse) {
            log("该 Characteristic 不支持 WRITE_NO_RESPONSE")
            return
        }

        if (!noResponse && !supportsWrite) {
            log("该 Characteristic 不支持 WRITE")
            return
        }

        val writeType =
            if (noResponse) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }

        val uuidText = uuid.toString()

        log(
            ">>> TX WRITE #" +
                    nextWriteIndex(uuidText)
        )

        log(
            "UUID: $uuid"
        )

        log(
            "Type: " +
                    if (noResponse) {
                        "WRITE_NO_RESPONSE"
                    } else {
                        "WRITE"
                    }
        )

        log(
            "Length: ${data.size}"
        )

        log(
            "HEX: ${UgreenT6Hex.toHex(data)}"
        )

        val started =
            try {

                if (
                    Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.TIRAMISU
                ) {

                    val result =
                        gatt.writeCharacteristic(
                            characteristic,
                            data,
                            writeType
                        )

                    result ==
                            android.bluetooth
                                .BluetoothStatusCodes
                                .SUCCESS

                } else {

                    @Suppress("DEPRECATION")
                    characteristic.writeType =
                        writeType

                    @Suppress("DEPRECATION")
                    characteristic.value =
                        data

                    @Suppress("DEPRECATION")
                    gatt.writeCharacteristic(
                        characteristic
                    )
                }

            } catch (
                e: SecurityException
            ) {

                log(
                    "WRITE 权限错误：" +
                            e.message
                )

                false

            } catch (e: Exception) {

                log(
                    "WRITE 失败：" +
                            e.message
                )

                false
            }

        log(
            "writeCharacteristic()：" +
                    started
        )

        log("")

        if (!started) {
            currentWriteCharacteristic = null
            return
        }

        /*
         * WRITE_NO_RESPONSE 通常不等待传统
         * onCharacteristicWrite() 作为协议响应。
         *
         * 这里把它视为 GATT 调用已经成功发起。
         */
        if (noResponse) {
            currentWriteCharacteristic = null
        } else {
            currentWriteCharacteristic = characteristic
        }
    }

    /**
     * 用户主动断开。
     *
     * 注意：因为 connectGatt 用了 autoConnect = true，
     *       只调用 disconnect() 的话，系统【立刻就会自动连回来】，
     *       用户会发现"点了断开却连不上"。
     *
     * 所以这里必须先 disconnect() 再 closeGatt()：
     *   disconnect() —— 通知系统主动断开
     *   closeGatt()  —— 注销 GATT 客户端，自动重连随之停止
     */
    fun disconnect() {

        log("===== 用户主动断开 =====")

        // 标记用户意图：断开着，不要自动重连
        stopReconnecting = true

        if (!hasConnectPermission()) {

            closeGatt()
            return
        }

        try {

            bluetoothGatt?.disconnect()

        } catch (
            _: SecurityException
        ) {
        }

        // 注销 GATT 客户端，否则 autoConnect 会立刻把它连回来
        closeGatt()

        log("已注销 GATT 客户端，自动重连停止")
    }

    fun close() {
        closeGatt()
    }

    fun getGattSummary(): String {
        return latestGattSummary
    }

    fun setNotifyLoggingPaused(
        paused: Boolean
    ) {

        notifyLoggingPaused = paused

        if (paused) {
            log("Notify 日志：已暂停（注意：暂停期间的包不会显示）")
        } else {
            log("Notify 日志：已恢复（编号不会中断，可看出中间漏了多少包）")
        }
    }

    fun setNotifyOnlyChanges(
        enabled: Boolean
    ) {

        notifyOnlyChanges = enabled

        /*
         * 切换模式时清空比较基准。
         * 避免旧连接/旧模式的数据影响新的测试。
         */
        lastNotifyHexByUuid.clear()
        notifyRepeatCountByUuid.clear()

        log(
            "Notify 仅显示变化：" +
                    if (enabled) "已开启" else "已关闭"
        )
    }

    private fun logService(
        serviceIndex: Int,
        service: BluetoothGattService
    ) {

        log(
            "Service[$serviceIndex]"
        )

        log(
            "UUID: ${service.uuid}"
        )

        log(
            "Type: ${service.type}"
        )

        log(
            "Characteristics: " +
                    service.characteristics.size
        )

        for (
        (characteristicIndex, characteristic)
        in service.characteristics
            .withIndex()
        ) {

            logCharacteristic(
                serviceIndex,
                characteristicIndex,
                characteristic
            )
        }

        log("")
    }

    private fun logCharacteristic(
        serviceIndex: Int,
        characteristicIndex: Int,
        characteristic:
        BluetoothGattCharacteristic
    ) {

        val properties =
            characteristic.properties

        log(
            "  Characteristic[" +
                    "$serviceIndex.$characteristicIndex]"
        )

        log(
            "  UUID: ${characteristic.uuid}"
        )

        log(
            "  Properties: " +
                    describeProperties(
                        properties
                    ) +
                    "  (0x" +
                    properties.toString(16)
                        .uppercase(Locale.US) +
                    ")"
        )

        log(
            "  Descriptors: " +
                    characteristic.descriptors.size
        )

        for (
        (descriptorIndex, descriptor)
        in characteristic.descriptors
            .withIndex()
        ) {

            log(
                "    Descriptor[" +
                        "$descriptorIndex]: " +
                        descriptor.uuid
            )
        }
    }

    private fun buildGattSummary(
        services:
        List<BluetoothGattService>
    ): String {

        val builder =
            StringBuilder()

        builder.appendLine(
            "UGREEN HiTune T6 GATT"
        )

        builder.appendLine()

        for (
        (serviceIndex, service)
        in services.withIndex()
        ) {

            if (isStandardService(service)) {
                continue
            }

            builder.appendLine(
                "S$serviceIndex ${service.uuid}"
            )

            for (
            (characteristicIndex, characteristic)
            in service.characteristics
                .withIndex()
            ) {

                builder.append(
                    "  C$characteristicIndex "
                )

                builder.append(
                    characteristic.uuid
                )

                builder.append(" ")

                builder.append(
                    describeProperties(
                        characteristic.properties
                    )
                )

                if (
                    characteristic.descriptors
                        .isNotEmpty()
                ) {

                    builder.append(
                        " D="
                    )

                    builder.append(
                        characteristic.descriptors
                            .joinToString(",") {
                                it.uuid.toString()
                            }
                    )
                }

                builder.appendLine()
            }

            builder.appendLine()
        }

        return builder
            .toString()
            .trim()
    }

    private fun isStandardService(
        service: BluetoothGattService
    ): Boolean {

        val uuid =
            service.uuid.toString()

        return uuid.startsWith(
            "00001800-"
        ) || uuid.startsWith(
            "00001801-"
        )
    }

    private fun describeProperties(
        properties: Int
    ): String {

        val result =
            mutableListOf<String>()

        if (
            properties and
            BluetoothGattCharacteristic
                .PROPERTY_READ != 0
        ) {
            result.add("READ")
        }

        if (
            properties and
            BluetoothGattCharacteristic
                .PROPERTY_WRITE != 0
        ) {
            result.add("WRITE")
        }

        if (
            properties and
            BluetoothGattCharacteristic
                .PROPERTY_WRITE_NO_RESPONSE != 0
        ) {
            result.add("WRITE_NO_RESPONSE")
        }

        if (
            properties and
            BluetoothGattCharacteristic
                .PROPERTY_NOTIFY != 0
        ) {
            result.add("NOTIFY")
        }

        if (
            properties and
            BluetoothGattCharacteristic
                .PROPERTY_INDICATE != 0
        ) {
            result.add("INDICATE")
        }

        if (
            properties and
            BluetoothGattCharacteristic
                .PROPERTY_SIGNED_WRITE != 0
        ) {
            result.add("SIGNED_WRITE")
        }

        if (result.isEmpty()) {
            return "NONE"
        }

        return result.joinToString(
            " | "
        )
    }

    private fun clearQueues() {

        readQueue.clear()
        notifyQueue.clear()

        currentReadCharacteristic = null
        currentNotifyCharacteristic = null
        currentWriteCharacteristic = null

        lastNotifyHexByUuid.clear()
        notifyRepeatCountByUuid.clear()

        /*
         * 断开时清空包序号。
         *
         * 下一次连接从 #1 重新开始，
         * 避免新旧连接的编号混在一起无法区分。
         */
        notifyCountByUuid.clear()
        writeCountByUuid.clear()
        cccdCountByUuid.clear()
    }

    private fun closeGatt() {

        clearQueues()

        try {
            bluetoothGatt?.close()
        } catch (
            _: SecurityException
        ) {
        }

        bluetoothGatt = null
    }

    private fun hasConnectPermission():
            Boolean {

        return if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.S
        ) {

            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) ==
                    PackageManager.PERMISSION_GRANTED

        } else {

            true
        }
    }
}