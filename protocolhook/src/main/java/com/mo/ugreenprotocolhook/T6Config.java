package com.mo.ugreenprotocolhook;

/**
 * 本模块的【单点配置】。
 *
 * ================== 开源后最重要的一步 ==================
 *
 * 每只耳机的蓝牙 MAC 都不同，模块必须知道你的耳机地址才能工作。
 *
 * 所以如果你不是原作者的设备，请把下面 DEFAULT_MAC
 * 改成你自己耳机的 MAC 地址，然后重新编译安装。
 *
 * 查看自己耳机 MAC 的方法：
 *     设置 → 蓝牙 → 点耳机旁边的详情（i）
 *     或：adb shell dumpsys bluetooth_manager | grep -i 你的耳机名
 *
 * ================== 为什么集中在这里 ==================
 *
 * 原先 MAC 分散写在 7 个类里，改一个漏一个就会出现
 * "音量面板能用、设备空间不能用"这种怪现象。
 * 现在统一从这里取，只需改一处。
 *
 * 注意：各使用方都是在【类初始化时】取一次，
 *       所以改完需要重启相关进程（或重启手机）才生效。
 */
final class T6Config {

    /**
     * 目标耳机 MAC —— ★ 换成你自己的！
     *
     * 默认值是原作者设备的地址，仅用于让示例可编译。
     */
    private static final String DEFAULT_MAC = "90:A0:BE:2D:5E:CA";

    /**
     * 命令特征 UUID。
     *
     * 绿联 T6（以及同系列多数型号）用这一组；
     * 换型号时若命令无效，可在这里替换。
     */
    static final String CMD_CHARACTERISTIC_UUID =
            "77777777-7777-7777-7777-777777777777";

    /** 命令特征所属的 Service UUID。 */
    static final String CMD_SERVICE_UUID =
            "66666666-6666-6666-6666-666666666666";

    private T6Config() {
    }

    /**
     * 取目标耳机 MAC。
     *
     * 做成方法而不是直接暴露字段，是为了将来如果需要
     * 支持多设备/从配置读取时，只改这一个地方。
     */
    static String mac() {
        return DEFAULT_MAC;
    }
}