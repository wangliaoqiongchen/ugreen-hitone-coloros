package com.mo.ugreenforcoloros

object UgreenT6Hex {

    fun toHex(data: ByteArray): String {
        if (data.isEmpty()) return "(empty)"

        return data.joinToString(" ") {
            "%02X".format(it.toInt() and 0xFF)
        }
    }

    /**
     * 把用户输入的 HEX 文本转换成 ByteArray。
     *
     * 支持：
     * 01 02 03 04
     * 01020304
     * 01:02:03:04
     * 01-02-03-04
     */
    fun fromHex(text: String): ByteArray? {
        val cleaned = text
            .replace("0x", "", ignoreCase = true)
            .replace(Regex("[\\s:_-]"), "")

        // 空输入单独处理
        if (cleaned.isEmpty()) {
            return byteArrayOf()
        }

        // 一个字节必须由两个十六进制字符组成
        if (cleaned.length % 2 != 0) {
            return null
        }

        return try {
            ByteArray(cleaned.length / 2) { index ->
                val start = index * 2
                cleaned.substring(start, start + 2).toInt(16).toByte()
            }
        } catch (_: NumberFormatException) {
            null
        }
    }
}