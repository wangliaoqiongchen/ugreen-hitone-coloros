package com.mo.ugreenforcoloros

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * T6 的 SPP / RFCOMM 通道。
 *
 * 【为什么需要它】
 * 通过 Hook 官方 APP 取证，已确认：
 *
 *     官方"声学"APP 完全通过 RFCOMM/SPP 与 T6 通信，
 *     从不使用 BLE GATT（手机端 GATT Client Map 里没有它）。
 *     · 服务信道 SCN = 13
 *     · 自定义 UUID 形如 ...6666-6666-6666-6666-...
 *     · 命令恒为 8 字节
 *
 * 所以要发命令，最稳的路径就是复刻官方 APP 用的这条 SPP 通道。
 *
 * 同时保留 BLE 写入能力（见 UgreenT6Gatt.writeHex），
 * 因为"耳机是否也接受 BLE 写入"尚未验证，
 * 需要一次真机实验判定哪条通道可用。
 */
class UgreenT6Spp(
    context: Context,
    private val onLog: (String) -> Unit
) {

    companion object {

        /**
         * 官方 APP 使用的自定义服务 UUID。
         *
         * 取证日志显示为：
         *     uuid=****6666-6666-6666-6666-************
         * 与 T6 的 GATT 自定义服务 66666666-6666-6666-6666-666666666666 同源。
         * 官方 APP 用 connectSocket(uuid, port=-1)，
         * 由系统经 SDP 解析出 SCN 13，这里用等价写法。
         */
        val SPP_UUID: UUID =
            UUID.fromString("00006666-6666-6666-6666-666666666666")

        /** 取证确认官方 APP 使用的 RFCOMM 信道号。 */
        const val SPP_CHANNEL = 13

        /** 官方命令恒为 8 字节。 */
        const val CMD_LENGTH = 8
    }

    private val context = context.applicationContext

    private val executor =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "t6-spp").apply { isDaemon = true }
        }

    private var socket: BluetoothSocket? = null
    private var output: OutputStream? = null
    private var input: InputStream? = null

    private val readerRunning = AtomicBoolean(false)

    @Volatile
    private var connected = false

    fun isConnected(): Boolean = connected

    private fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * 建立 SPP 连接。
     *
     * @param useRawChannel true  = 直接按信道号连（绕过 SDP）
     *                      false = 按 UUID 经 SDP 解析（官方 APP 的做法）
     */
    fun connect(address: String, useRawChannel: Boolean = false) {

        if (!hasConnectPermission()) {
            onLog("缺少 BLUETOOTH_CONNECT 权限")
            return
        }

        close()

        executor.execute {
            val s = try {
                createSocket(address, useRawChannel)
            } catch (e: SecurityException) {
                onLog("SPP 权限错误: ${e.message}")
                return@execute
            } catch (e: Exception) {
                onLog("SPP 创建 socket 失败: ${e.message}")
                return@execute
            } ?: return@execute

            onLog("")
            onLog("=== SPP 连接开始 ===")
            onLog("地址: $address")
            onLog(
                if (useRawChannel) {
                    "方式: 直接信道 SCN $SPP_CHANNEL"
                } else {
                    "方式: SDP 解析 UUID $SPP_UUID"
                }
            )

            try {
                s.connect()
            } catch (e: Exception) {
                // 官方 APP 也会先失败几次再成功（取证日志 16:02:39 段可见）
                onLog("connect 失败: ${e.message}")
                onLog("（官方 APP 同样会失败几次，可点重试）")
                try {
                    s.close()
                } catch (_: Exception) {
                }
                return@execute
            }

            socket = s
            output = s.outputStream
            input = s.inputStream
            connected = true

            onLog("=== SPP 连接成功 ===")
            onLog("远端: ${s.remoteDevice.address}")

            startReader()
        }
    }

    /**
     * 创建 RFCOMM socket。
     *
     * 直接信道号需要反射调用隐藏方法 createRfcommSocket(int)，
     * 这是官方 APP 之外的一种兜底手段。
     */
    private fun createSocket(
        address: String,
        useRawChannel: Boolean
    ): BluetoothSocket? {

        val adapter: BluetoothAdapter =
            BluetoothAdapter.getDefaultAdapter() ?: run {
                onLog("BluetoothAdapter 不可用")
                return null
            }

        val device: BluetoothDevice = adapter.getRemoteDevice(address)

        if (!useRawChannel) {
            return device.createRfcommSocketToServiceRecord(SPP_UUID)
        }

        onLog("尝试反射调用 createRfcommSocket($SPP_CHANNEL) ...")
        return try {
            val m = device.javaClass.getMethod(
                "createRfcommSocket",
                Int::class.javaPrimitiveType
            )
            m.invoke(device, SPP_CHANNEL) as BluetoothSocket
        } catch (e: Exception) {
            onLog("反射创建失败: ${e.message}")
            onLog("回退到 UUID + SDP 方式")
            device.createRfcommSocketToServiceRecord(SPP_UUID)
        }
    }

    /** 持续读取，把收到的字节原样打印（与 BLE 侧互相印证）。 */
    private fun startReader() {

        if (!readerRunning.compareAndSet(false, true)) {
            return
        }

        executor.execute {
            val buf = ByteArray(512)
            val inp = input
            if (inp == null) {
                readerRunning.set(false)
                return@execute
            }
            try {
                while (readerRunning.get()) {
                    val n = inp.read(buf)
                    if (n <= 0) {
                        break
                    }
                    val data = buf.copyOf(n)
                    onLog("<<< SPP RX  ${n}B  ${UgreenT6Hex.toHex(data)}")
                }
            } catch (e: Exception) {
                if (readerRunning.get()) {
                    onLog("SPP 读取结束: ${e.message}")
                }
            } finally {
                readerRunning.set(false)
                connected = false
            }
        }
    }

    /** 发送一条 8 字节命令。命令常量表见 UgreenT6Commands。 */
    fun send(data: ByteArray): Boolean {

        if (!connected) {
            onLog("SPP 未连接，请先建立 SPP 连接")
            return false
        }

        if (data.size != CMD_LENGTH) {
            onLog("警告：官方命令恒为 $CMD_LENGTH 字节，本次 ${data.size} 字节")
        }

        val out = output
        if (out == null) {
            onLog("SPP 输出流不可用")
            return false
        }

        return try {
            onLog(">>> SPP TX  ${data.size}B  ${UgreenT6Hex.toHex(data)}")
            out.write(data)
            out.flush()
            true
        } catch (e: Exception) {
            onLog("SPP 发送失败: ${e.message}")
            false
        }
    }

    fun close() {
        readerRunning.set(false)
        connected = false
        try {
            input?.close()
        } catch (_: Exception) {
        }
        try {
            output?.close()
        } catch (_: Exception) {
        }
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        input = null
        output = null
        socket = null
    }
}
