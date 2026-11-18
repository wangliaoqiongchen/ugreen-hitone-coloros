package com.mo.ugreenprotocolhook;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 降噪切换请求文件。
 *
 * ================== 为什么需要它 ==================
 *
 * 用户在系统音量面板点了"降噪/通透"之后：
 *
 *     SystemUI → melody 的 provider.call(melody_method_noise_reduction)
 *                  ↓  我们在这里拦到了（MelodyProviderHook）
 *              ★ 需要把这件事告诉"能发 BLE 命令"的那一方
 *
 * 但发命令要在【官方 APP 进程】（协议桥）或测试 APP 里做，
 * 而当前在【melody 进程】，两者不同 uid，无法直接调用。
 *
 * 所以用文件当通道（和电量桥同理）：
 *
 *     melody 进程  ──写──>  ugreen_anc_request.txt  ──读──>  测试 APP
 *                                                          （su 读 + BLE 发送）
 *
 * ================== 文件格式 ==================
 *
 *     mode=1
 *     address=90:A0:BE:2D:5E:CA
 *     at=1696400000000
 *
 * 只有一行关键信息（mode），故意做得极简。
 */
final class AncRequest {

    private static final String DIR = "/data/data/com.oplus.melody/files";
    private static final String FILE_NAME = "ugreen_anc_request.txt";

    private AncRequest() {
    }

    /**
     * 写入一次降噪切换请求。
     *
     * @param mode ColorOS 的降噪模式值（0=关 1=降噪 2=通透）
     */
    static void write(int mode) {
        try {
            File dir = new File(DIR);
            if (!dir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            String text = "mode=" + mode
                    + "\naddress=" + T6Config.mac()
                    + "\nat=" + System.currentTimeMillis()
                    + "\n";
            try (FileOutputStream fos = new FileOutputStream(
                    new File(dir, FILE_NAME), false)) {
                fos.write(text.getBytes("UTF-8"));
            }
            MelodyLog.event("已写入降噪请求 mode=" + mode);
        } catch (Throwable t) {
            MelodyLog.event("写降噪请求失败: " + t);
        }
    }
}
