package com.mo.ugreenprotocolhook;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 协议日志查看器 + 自诊断面板。
 *
 * 设计目标：不依赖 adb、不依赖 AI，用户自己就能判断"Hook 到底有没有生效"。
 *
 * 判断依据：
 *   Hook 一加载就会往若干候选目录各写一条 ugreen_hook_beacon.txt。
 *   只要能读到任意一条信标，就说明 Hook 确实在官方 APP 里运行了。
 */
public class MainActivity extends AppCompatActivity {

    /** 信标文件名（Hook 加载时写入）。 */
    private static final String BEACON_NAME = "ugreen_hook_beacon.txt";

    /** 正式日志文件名。 */
    private static final String LOG_NAME = "ugreen_protocol.log";

    /** 历史版本用过、现已废弃的信号文件名，仅用于清理残留。 */
    private static final String LEGACY_SIGNAL = ".ugreen_clear_request";

    /** 与 HookEntry.LOG_DIR_CANDIDATES 保持一致，按优先级排列。 */
    private static final String[] DIRS = {
            "/sdcard/Download",
            "/sdcard/Android/data/com.ugreen.iot/files",
            "/data/data/com.ugreen.iot/files",
            "/data/local/tmp",
    };

    private TextView textStatus;
    private TextView textLog;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private volatile String cachedLog = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        textStatus = findViewById(R.id.textStatus);
        textLog = findViewById(R.id.textLog);

        Button btnRefresh = findViewById(R.id.buttonRefresh);
        Button btnCopy = findViewById(R.id.buttonCopy);
        Button btnClear = findViewById(R.id.buttonClear);

        btnRefresh.setOnClickListener(v -> refresh());
        btnCopy.setOnClickListener(v -> copyLog());
        btnClear.setOnClickListener(v -> clearLog());

        refresh();
    }

    /**
     * 清理日志。
     *
     * 设计演进（踩过的两个坑，都已废弃）：
     *
     *   坑 1：用 su 在目标 APP 目录里放信号文件
     *         → 文件属主是 root，Hook 进程（普通应用 uid）删不掉，
     *           信号永久残留，导致 Hook 每次写入前都截断日志，
     *           表现为"日志永远只有最后一行"。
     *
     *   坑 2：改用本 APP 自己的 uid 创建信号文件
     *         → /sdcard 经 FUSE 后属主统一显示为 media_rw，
     *           但【跨应用删除仍然被拒】，信号依然删不掉，问题照旧。
     *
     * 现在的做法：不用信号文件，直接由界面把日志截断为零长度。
     *   · 日志是纯追加文本，截断不会破坏正在运行的 Hook
     *   · Hook 的内存缓冲有独立上限，不影响功能
     *   · 只截断本模块自己写的那个日志文件，不删除任何文件
     */
    private void clearLog() {
        textStatus.setText("清理中…\n");

        new Thread(() -> {
            int done = 0;

            for (String dir : DIRS) {
                // 只截断日志文件（: > 是"长度置零"，文件保留）
                String log = dir + "/" + LOG_NAME;
                runSu("if [ -f " + log + " ]; then : > " + log + "; fi");

                // 顺手清掉历史版本可能残留的信号文件
                String sig = dir + "/" + LEGACY_SIGNAL;
                runSu("if [ -f " + sig + " ]; then rm -f " + sig + "; fi");

                done++;
            }

            final int doneFinal = done;

            handler.post(() -> {
                cachedLog = "";
                textLog.setText("");
                Toast.makeText(this,
                        "已清理 " + doneFinal + " 个位置的日志",
                        Toast.LENGTH_SHORT).show();
                refresh();
            });
        }, "log-clear").start();
    }

    // ------------------------------------------------------------------

    private void refresh() {
        textStatus.setText("检查中…\n");
        new Thread(() -> {
            String report = buildDiagnosticReport();
            String log = findAndReadLog();
            handler.post(() -> {
                textStatus.setText(report);
                if (log != null) {
                    cachedLog = log;
                    textLog.setText(log);
                } else {
                    textLog.setText("");
                }
            });
        }, "diag").start();
    }

    /**
     * 生成诊断报告。
     *
     * 逐项检查：
     *   1. su 是否可用（决定能不能读私有目录）
     *   2. 每个候选目录里有没有信标（证明 Hook 加载过）
     *   3. 每个候选目录里有没有日志、多大
     */
    private String buildDiagnosticReport() {

        StringBuilder sb = new StringBuilder();

        // ---- 1. su 状态 ----
        String suId = runSu("id");
        boolean suOk = suId != null && suId.contains("uid=0");
        sb.append(suOk ? "✅ su 可用（能读私有目录）\n"
                : "❌ su 不可用（只能读 /sdcard 下的日志）\n");

        // ---- 2/3. 逐目录检查 ----
        boolean beaconFound = false;
        List<String> logDirs = new ArrayList<>();

        for (String dir : DIRS) {
            String beacon = statPath(dir + "/" + BEACON_NAME);
            String log = statPath(dir + "/" + LOG_NAME);

            String shortDir = dir.replace("/sdcard/", "SD/");

            if (beacon != null) {
                beaconFound = true;
                sb.append("✅ 信标 ").append(shortDir)
                        .append("  ").append(beacon).append('\n');
            }

            if (log != null) {
                logDirs.add(dir);
                sb.append("📄 日志 ").append(shortDir)
                        .append("  ").append(log).append('\n');
            } else if (beacon == null) {
                sb.append("·  ").append(shortDir).append("  无\n");
            }
        }

        sb.append('\n');

        if (!beaconFound) {
            sb.append("❗ 没有找到任何信标 → Hook 没有加载。\n");
            sb.append("请检查：\n");
            sb.append("  1. LSPosed 里本模块是否【已启用】\n");
            sb.append("  2. 作用域是否勾选了 com.ugreen.iot\n");
            sb.append("  3. 是否【强制停止并重启】过官方 APP\n");
        } else if (logDirs.isEmpty()) {
            sb.append("⚠️ Hook 已加载，但还没有任何协议数据。\n");
            sb.append("请在官方 APP 里操作一次（连上耳机、切个降噪）。\n");
        } else {
            sb.append("✅ Hook 已加载且已抓到数据。\n");
        }

        return sb.toString();
    }

    /** 优先从 /sdcard 读；都读不到再用 su 读私有目录。 */
    private String findAndReadLog() {

        // 先试不需要 root 的位置
        for (String dir : DIRS) {
            if (dir.startsWith("/data/")) {
                continue;
            }
            String content = readPlain(dir + "/" + LOG_NAME);
            if (content != null && !content.isEmpty()) {
                return content;
            }
        }

        // 再用 su 试全部位置
        for (String dir : DIRS) {
            String content = runSu("cat " + dir + "/" + LOG_NAME);
            if (content != null && !content.trim().isEmpty()
                    && !content.contains("No such file")) {
                return content;
            }
        }

        return null;
    }

    /** stat 一个路径，返回 "大小 时间" 之类的描述；不存在返回 null。 */
    private String statPath(String path) {
        String out = runSu("ls -l " + path + " 2>/dev/null");
        if (out == null || out.trim().isEmpty()) {
            return null;
        }
        String s = out.trim();
        if (s.contains("No such file")) {
            return null;
        }
        // 取 "大小" 与 "时间" 两列，尽量精简
        String[] parts = s.split("\\s+");
        if (parts.length >= 8) {
            return parts[4] + "B  " + parts[5] + " " + parts[6];
        }
        return s;
    }

    /** 不需要 root 的直接读取。 */
    private String readPlain(String path) {
        try (InputStream is = new java.io.FileInputStream(path)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toString("UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    /** 通过 su 执行只读命令。 */
    private String runSu(String cmd) {
        try {
            Process p = new ProcessBuilder("su", "-c", cmd)
                    .redirectErrorStream(true)
                    .start();

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (InputStream is = p.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
            }
            p.waitFor();

            return bos.toString("UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    private void copyLog() {
        String content = cachedLog;
        if (content == null || content.isEmpty()) {
            content = textLog.getText().toString();
        }
        if (content.isEmpty()) {
            Toast.makeText(this, "没有可复制的内容", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("UGREEN 协议日志", content));
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
        }
    }
}
