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
 * 「设备空间」（com.heytap.mydevices）进程的日志。
 *
 * 说明：本模块目前有三套日志（协议桥 / 系统桥 / 各 UI 进程），
 *       原因很实际 —— 它们运行在不同进程，
 *       静态状态（含日志文件的句柄）无法共享，
 *       所以必须各写各的文件。
 *
 * 落盘位置：该进程自己的私有目录（root 才能读，AI 用 su cat 取回）。
 */
public final class MyDevicesLog {

    private static final String TAG = "UGREEN-MYDEVICES";

    /** 候选路径，按可写性排列。 */
    private static final String[] DIRS = {
            "/data/data/com.heytap.mydevices/files",
            "/sdcard/Download",
    };

    public static final String FILE_NAME = "ugreen_mydevices_hook.log";

    private static final BlockingQueue<String> QUEUE = new ArrayBlockingQueue<>(20000);

    private static final SimpleDateFormat TIME =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private static volatile File logFile;
    private static volatile boolean started = false;

    private MyDevicesLog() {
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
                File probe = new File(d, ".ugreen_mydevices_probe");
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

        Thread t = new Thread(MyDevicesLog::loop, "ugreen-mydevices-log");
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

        // 同时写 logcat，便于 adb 直接查看
        try {
            Log.i(TAG, text);
        } catch (Throwable ignored) {
        }
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
