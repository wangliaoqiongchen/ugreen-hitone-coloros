package com.mo.ugreenprotocolhook;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * 系统桥专用日志。
 *
 * 与 ProtocolLog 分开的原因：
 *   ProtocolLog 属于协议桥（com.ugreen.iot 进程），
 *   本类属于系统桥（com.android.bluetooth 进程），
 *   两者不在同一个进程，静态状态无法共享。
 *
 * 落盘位置全部在 /sdcard 下，不需要 root 就能取回。
 */
public final class SystemBridgeLog {

    private static final String TAG = "UGREEN-SYSBRIDGE";

    /**
     * 候选路径，按可写性从易到难。
     *
     * ================== 为什么首选 logs 目录 ==================
     *
     * 真机实测（以蓝牙 uid 1002 身份）：
     *     /sdcard/Download            → Permission denied ❌
     *     /data/local/tmp             → Permission denied ❌
     *     /data/misc/bluetooth/logs   → OK ✅
     *
     * 原因：com.android.bluetooth 虽然权限不小，
     *       但它仍以受限应用身份运行，写不了 /sdcard 与 /data/local/tmp；
     *       而 /data/misc/bluetooth/logs 是它自己的目录
     *       （drwxrwx--- bluetooth bluetooth），必然可写。
     *
     * 取回方式：这个目录只有 root 能读，AI 用
     *     adb exec-out su -c "cat <path>"
     * 读取即可（已验证可行）。
     */
    private static final String[] DIRS = {
            "/data/misc/bluetooth/logs",
            "/sdcard/Download",
            "/data/local/tmp",
    };

    public static final String FILE_NAME = "ugreen_system_bridge.log";

    /**
     * 队列容量。
     *
     * 设为较大值的原因：降噪探测会一次性打印大量方法签名，
     * 容量太小会丢日志 —— 而丢的恰好是探测结果，
     * 那就白跑一趟了。
     */
    private static final BlockingQueue<String> QUEUE = new ArrayBlockingQueue<>(20000);


    private static final SimpleDateFormat TIME =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private static volatile File logFile;
    private static volatile boolean started = false;

    private SystemBridgeLog() {
    }

    public static synchronized void init() {

        if (started) {
            return;
        }
        started = true;

        for (String dir : DIRS) {
            try {
                File d = new File(dir);
                if (!d.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    d.mkdirs();
                }
                File probe = new File(d, ".ugreen_sys_probe");
                try (FileOutputStream fos = new FileOutputStream(probe, true)) {
                    fos.write('x');
                }
                //noinspection ResultOfMethodCallIgnored
                probe.delete();

                logFile = new File(d, FILE_NAME);
                break;
            } catch (Throwable ignored) {
                // 换下一个候选
            }
        }

        Thread t = new Thread(SystemBridgeLog::loop, "ugreen-sysbridge-log");
        t.setDaemon(true);
        t.start();
    }

    public static File getLogFile() {
        return logFile;
    }

    public static void event(String text) {
        if (text == null) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        QUEUE.offer(TIME.format(new Date()) + "  " + text);
    }

    private static void loop() {
        while (true) {
            try {
                String line = QUEUE.take();
                File f = logFile;
                if (f == null) {
                    continue;
                }
                try (FileOutputStream fos = new FileOutputStream(f, true)) {
                    fos.write(line.getBytes("UTF-8"));
                    fos.write('\n');
                }
            } catch (InterruptedException ie) {
                return;
            } catch (Throwable t) {
                Log.w(TAG, "write failed", t);
            }
        }
    }
}
