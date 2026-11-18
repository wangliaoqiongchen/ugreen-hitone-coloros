package com.mo.ugreenforcoloros

import android.content.Context
import java.io.File
import java.util.Locale

/**
 * 电量桥文件写入器。
 *
 * ================== 为什么需要它 ==================
 *
 * ColorOS 的电量显示由【蓝牙服务进程】负责，
 * 只有那个进程里的 AdapterService.setBatteryLevel() 能改系统显示。
 *
 * 而电量数据来自我们的 BLE 连接（在测试 APP 进程里）。
 * 两个进程不同 uid，无法直接共享内存，
 * 所以用一份【简单的文本文件】当桥：
 *
 *     测试 APP（读 BLE）  ──写──>  ugreen_battery.txt  ──读──>  系统桥（推给 ColorOS）
 *
 * 选择文本文件而不是其它 IPC 的原因：
 *   · 两个进程都能访问 /sdcard 下的位置
 *   · 出问题时可以直接打开看，调试成本最低
 *   · 不依赖任何权限、不依赖 binder、不依赖 socket
 *
 * ================== 文件格式 ==================
 *
 *     mac=90:A0:BE:2D:5E:CA
 *     left=100
 *     right=95
 *     updated=1696400000000
 *
 * 采用 key=value 纯文本，容错优先（读侧能容忍多余字段与空行）。
 */
object UgreenT6BatteryBridge {

    /** 与系统桥约定的桥文件名。 */
    private const val FILE_NAME = "ugreen_battery.txt"

    /**
     * ★ 唯一实测可行的跨进程落点。
     *
     * 该目录是 `drwxrwx--- bluetooth:bluetooth`，
     * 普通 APP 写不进去，必须借助 su。
     * 但它是【唯一】蓝牙进程读得到的共享位置 ——
     * 因为它的 SELinux 标签是 bluetooth_logs_data_file，
     * 而 /sdcard 与 /data/local/tmp 都被域策略挡住。
     */
    private const val TARGET_DIR = "/data/misc/bluetooth/logs"

    /** 节流：电量没变就不写。 */
    private var lastLeft = Int.MIN_VALUE
    private var lastRight = Int.MIN_VALUE

    /** 节流：距上次写入至少间隔这么久（毫秒），避免频繁起 root 进程。 */
    private const val MIN_INTERVAL_MS = 10_000L
    private var lastWriteAt = 0L

    /**
     * 写入电量。
     *
     * 实现要点：
     *   · 先用普通文件写一份到 APP 自己的外部目录（不需要 root，作为备份/调试）
     *   · 再用 su 把它送进蓝牙日志目录（系统桥唯一读得到的地方）
     *   · 双阈值节流：数值不变 + 10 秒内，都不重复执行
     *
     * @param left  左耳电量，-1 表示未知
     * @param right 右耳电量，-1 表示未知
     * @param cas   电池仓电量，-1 表示未知
     */
    fun write(
        context: Context,
        mac: String,
        left: Int,
        right: Int,
        cas: Int = -1
    ) {
        val now = System.currentTimeMillis()

        // 节流：数值没变就不写
        if (left == lastLeft && right == lastRight) {
            return
        }
        // 节流：间隔太短也不写
        if (now - lastWriteAt < MIN_INTERVAL_MS) {
            return
        }

        lastLeft = left
        lastRight = right
        lastWriteAt = now

        val text = String.format(
            Locale.US,
            "mac=%s%nleft=%d%nright=%d%ncase=%d%nupdated=%d%n",
            mac, left, right, cas, now
        )

        /*
         * 第 1 步：写到 APP 自己的外部目录（无需 root，留作备份与调试）
         */
        try {
            val dir = context.getExternalFilesDir(null)
            if (dir != null) {
                if (!dir.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    dir.mkdirs()
                }
                File(dir, FILE_NAME).writeText(text)
            }
        } catch (_: Throwable) {
        }

        /*
         * 第 2 步：用 su 送进蓝牙日志目录。
         *
         * 先写临时文件再 mv 过去，避免 shell 引号把内容弄坏
         * （内容里没有单引号，用单引号包裹最安全）。
         */
        try {
            val tmp = File(context.cacheDir, FILE_NAME)
            tmp.writeText(text)

            val cmd = "cp '${tmp.absolutePath}' '$TARGET_DIR/$FILE_NAME' " +
                    "&& chmod 0644 '$TARGET_DIR/$FILE_NAME'"

            val p = ProcessBuilder("su", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            p.waitFor()
        } catch (_: Throwable) {
        }
    }

    /**
     * 尝试从一条 85 86 87 状态包里解析电量。
     *
     * 已知格式（真机取证）：
     *     85 86 87 04 01 [左] [右] [校验]
     *
     * ================== 重要说明 ==================
     *
     * BYTE5 / BYTE6 是否真的是"左耳/右耳电量"【尚未最终确认】：
     *   · 长时间都是 0x64(100)，符合"满电双耳"
     *   · 后来观察到 0x5A(90)，说明它会随放电下降
     *   · 但也出现过 0xFF，语义存疑
     *
     * 因此这里按"疑似电量"解析，并在日志里明确标注为待确认，
     * 不做任何强制性假设。
     *
     * @return Triple(左, 右, 是否疑似有效)；不是该格式时返回 null
     */
    fun parseStatusPacket(data: ByteArray): Triple<Int, Int, Boolean>? {

        if (data.size != 8) {
            return null
        }
        // 固定的三字节前缀
        if (data[0] != 0x85.toByte() ||
            data[1] != 0x86.toByte() ||
            data[2] != 0x87.toByte()
        ) {
            return null
        }

        val left = data[5].toInt() and 0xFF
        val right = data[6].toInt() and 0xFF

        /*
         * 只接受 0..100 的合理区间；
         * 0xFF 之类的值视为"未知"。
         */
        val leftOk = left in 0..100
        val rightOk = right in 0..100

        return Triple(
            if (leftOk) left else -1,
            if (rightOk) right else -1,
            leftOk || rightOk
        )
    }
}
