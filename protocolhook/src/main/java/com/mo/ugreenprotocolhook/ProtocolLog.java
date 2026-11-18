package com.mo.ugreenprotocolhook;

import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 协议日志记录器。
 *
 * 特点：
 *   · 单向队列 + 后台写线程，绝不在目标 APP 的调用线程里做磁盘 IO
 *   · 文件大小上限，超出后丢最早的记录（避免无限增长）
 *   · 只在目标 APP 自己的私有目录里写文件，不碰系统分区
 *
 * 只读原则：
 *   本类只做【追加写自己的日志文件】。
 *   不提供任何删除、清空、修改设备上其它文件的方法。
 */
public final class ProtocolLog {

    private static final String TAG = "UGREEN-HOOK";

    /** 单文件上限：1 MB，超出后向前截断。 */
    private static final long MAX_FILE_BYTES = 1024L * 1024L;

    /** 触发一次截断后保留的字节数。 */
    private static final long KEEP_AFTER_TRIM = 512L * 1024L;

    private static final Object LOCK = new Object();
    private static final SimpleDateFormat TIME =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private static final BlockingQueue<String> QUEUE = new ArrayBlockingQueue<>(4096);
    private static final StringBuilder MEMORY = new StringBuilder();

    private static volatile File logFile;
    private static volatile boolean started = false;

    private static final AtomicLong WRITTEN_BYTES = new AtomicLong();

    private ProtocolLog() {
    }

    /** 由 Hook 入口调用一次。 */
    public static void init(File dir) {
        if (started) {
            return;
        }
        synchronized (LOCK) {
            if (started) {
                return;
            }
            started = true;

            if (dir != null) {
                if (!dir.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    dir.mkdirs();
                }
                logFile = new File(dir, "ugreen_protocol.log");
            }

            Thread writer = new Thread(ProtocolLog::writerLoop, "ugreen-hook-writer");
            writer.setDaemon(true);
            writer.start();
        }
    }

    public static File getLogFile() {
        return logFile;
    }

    /** 记录一条 TX（APP -> 耳机）。 */
    public static void tx(byte[] data) {
        enqueue("TX", data);
    }

    /** 记录一条 RX（耳机 -> APP）。 */
    public static void rx(byte[] data) {
        enqueue("RX", data);
    }

    /** 记录一条纯文本事件。 */
    public static void event(String text) {
        submit(prefix() + "  --  " + text);
    }

    private static void enqueue(String dir, byte[] data) {
        if (data == null) {
            return;
        }
        submit(prefix() + "  " + dir + "  " + data.length + "B  " + toHex(data));
    }

    private static String prefix() {
        return TIME.format(new Date());
    }

    private static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 3);
        for (byte b : data) {
            int v = b & 0xFF;
            if (v < 0x10) {
                sb.append('0');
            }
            sb.append(Integer.toHexString(v).toUpperCase(Locale.US)).append(' ');
        }
        return sb.toString().trim();
    }

    private static void submit(String line) {
        if (line == null) {
            return;
        }
        // 满了就丢，绝不阻塞目标 APP
        //noinspection ResultOfMethodCallIgnored
        QUEUE.offer(line);

        synchronized (LOCK) {
            MEMORY.append(line).append('\n');
            if (MEMORY.length() > 400_000) {
                int cut = MEMORY.indexOf("\n", MEMORY.length() / 2);
                if (cut > 0) {
                    MEMORY.delete(0, cut + 1);
                }
            }
        }
    }

    private static void writerLoop() {
        while (true) {
            try {
                String line = QUEUE.take();
                appendToFile(line);
            } catch (InterruptedException ie) {
                return;
            } catch (Throwable t) {
                Log.w(TAG, "writer failed", t);
            }
        }
    }

    private static void appendToFile(String line) {
        File f = logFile;
        if (f == null) {
            return;
        }

        try (FileOutputStream fos = new FileOutputStream(f, true)) {
            fos.write(line.getBytes("UTF-8"));
            fos.write('\n');
        } catch (IOException e) {
            Log.w(TAG, "append failed", e);
            return;
        }

        long written = WRITTEN_BYTES.addAndGet(line.length() + 1L);
        if (written > MAX_FILE_BYTES) {
            trim();
            WRITTEN_BYTES.set(0);
        }
    }

    /** 文件太大时，只保留尾部。 */
    private static void trim() {
        File f = logFile;
        if (f == null) {
            return;
        }
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
            long len = raf.length();
            if (len <= KEEP_AFTER_TRIM) {
                return;
            }
            long start = len - KEEP_AFTER_TRIM;
            raf.seek(start);
            byte[] tail = new byte[(int) (len - start)];
            raf.readFully(tail);

            // 从换行处对齐，避免把一行切两半
            int nl = 0;
            while (nl < tail.length && tail[nl] != '\n') {
                nl++;
            }
            int off = (nl < tail.length) ? nl + 1 : 0;

            try (OutputStream os = new FileOutputStream(f, false)) {
                os.write(tail, off, tail.length - off);
            }
        } catch (Throwable t) {
            Log.w(TAG, "trim failed", t);
        }
    }

    /** 设备信息，写进日志头部，便于回溯。 */
    public static String deviceHeader() {
        return "device=" + Build.MANUFACTURER + " " + Build.MODEL
                + " android=" + Build.VERSION.RELEASE
                + " sdk=" + Build.VERSION.SDK_INT;
    }
}
