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
 * 「设备快连」（com.heytap.accessory）进程的日志。
 *
 * 与其他几个桥一样：不同进程无法共享静态状态，所以各写各的文件。
 */
public final class AccessoryLog {

    private static final String TAG = "UGREEN-ACCESSORY";

    private static final String[] DIRS = {
            "/data/data/com.heytap.accessory/files",
            "/sdcard/Download",
    };

    public static final String FILE_NAME = "ugreen_accessory_hook.log";

    private static final BlockingQueue<String> QUEUE = new ArrayBlockingQueue<>(20000);

    private static final SimpleDateFormat TIME =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private static volatile File logFile;
    private static volatile boolean started = false;

    private AccessoryLog() {
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
                File probe = new File(d, ".ugreen_accessory_probe");
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

        Thread t = new Thread(AccessoryLog::loop, "ugreen-accessory-log");
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
