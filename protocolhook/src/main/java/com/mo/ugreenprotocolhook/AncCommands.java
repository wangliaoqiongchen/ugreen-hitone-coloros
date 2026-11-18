package com.mo.ugreenprotocolhook;

/**
 * T6 降噪命令表（模块侧）。
 *
 * 与测试 APP 的 UgreenT6Commands.kt 保持一致，
 * 所有命令都是【真机验证过的固定值】（方案甲：抄现成值）。
 *
 * 之所以在模块里再存一份：
 *   模块要能在【不装测试 APP】的情况下独立发命令，
 *   不能反过来依赖 APP 的资源。
 *   两边若将来有改动，务必同步（见文末注意事项）。
 *
 * ================== 命令帧结构 ==================
 *
 *     偏移  含义
 *     0-2   固定头 AA BB CC
 *     3     功能域：09 = 降噪/环境音
 *     4     固定 01
 *     5-6   参数
 *     7     由内容决定的确定性值（算法未知，抄现成值）
 */
final class AncCommands {

    /** 降噪 关。官方 APP 显示"降噪关"。 */
    static final byte[] ANC_OFF = hex("AA BB CC 09 01 A0 A0 2A");

    /** 降噪 深度（最深档）。官方 APP 显示"降噪深度"。 */
    static final byte[] ANC_DEEP = hex("AA BB CC 09 01 A1 61 EA");

    /** 环境音 / 通透。官方 APP 显示"环境音"。 */
    static final byte[] ANC_AMBIENT = hex("AA BB CC 09 01 C2 21 C3");

    /** 降噪 中度。 */
    static final byte[] ANC_MEDIUM = hex("AA BB CC 09 01 B1 60 26");

    /** 降噪 轻度。 */
    static final byte[] ANC_LIGHT = hex("AA BB CC 09 01 C1 61 C2");

    // ------------------------------------------------------------------

    /** ColorOS 模式值（从 SystemUI 资源表反查确认）。 */
    static final int MODE_NOISE = 5;
    static final int MODE_TRANSPARENT = 2;
    static final int MODE_OFF = 1;

    private AncCommands() {
    }

    /**
     * ColorOS 模式 → T6 命令。
     *
     * @return 命令字节；无法识别的模式返回 null
     */
    static byte[] forSystemMode(int mode) {
        switch (mode) {
            case MODE_NOISE:
                return ANC_DEEP;
            case MODE_TRANSPARENT:
                return ANC_AMBIENT;
            case MODE_OFF:
                return ANC_OFF;
            default:
                return null;
        }
    }

    /** 模式 → 中文标签（日志与摘要用）。 */
    static String labelOf(int mode) {
        switch (mode) {
            case MODE_NOISE:
                return "降噪";
            case MODE_TRANSPARENT:
                return "通透";
            case MODE_OFF:
                return "关闭";
            default:
                return "未知(" + mode + ")";
        }
    }

    private static byte[] hex(String s) {
        String[] parts = s.trim().split("\\s+");
        byte[] out = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = (byte) Integer.parseInt(parts[i], 16);
        }
        return out;
    }
}
