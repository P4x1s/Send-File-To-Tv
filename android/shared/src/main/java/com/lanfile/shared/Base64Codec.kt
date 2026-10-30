package com.lanfile.shared

/**
 * 纯 Kotlin Base64 编解码。
 *
 * 为什么不直接用 `java.util.Base64`？它在 Android 上需要 API 26+，
 * 而本项目的 `minSdk = 21`，且 JVM 验证模块（harness）也需要直接编译
 * shared 模块的源码，因此这里实现一份不依赖任何平台 API 的版本。
 *
 * 编码表与 RFC 4648 标准一致（带 `=` 填充），可与 Python `base64` 互解。
 */
object Base64Codec {

    private const val TABLE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /** Base64 编码（标准字母表 + `=` 填充，无换行）。 */
    fun encode(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val out = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i + 2 < data.size) {
            val n = ((data[i].toInt() and 0xFF) shl 16) or
                ((data[i + 1].toInt() and 0xFF) shl 8) or
                (data[i + 2].toInt() and 0xFF)
            out.append(TABLE[(n ushr 18) and 0x3F])
            out.append(TABLE[(n ushr 12) and 0x3F])
            out.append(TABLE[(n ushr 6) and 0x3F])
            out.append(TABLE[n and 0x3F])
            i += 3
        }
        when (data.size - i) {
            1 -> {
                val n = (data[i].toInt() and 0xFF) shl 16
                out.append(TABLE[(n ushr 18) and 0x3F])
                out.append(TABLE[(n ushr 12) and 0x3F])
                out.append("==")
            }
            2 -> {
                val n = ((data[i].toInt() and 0xFF) shl 16) or ((data[i + 1].toInt() and 0xFF) shl 8)
                out.append(TABLE[(n ushr 18) and 0x3F])
                out.append(TABLE[(n ushr 12) and 0x3F])
                out.append(TABLE[(n ushr 6) and 0x3F])
                out.append('=')
            }
        }
        return out.toString()
    }

    /** Base64 解码（忽略空白，非法字符抛异常）。 */
    fun decode(text: String): ByteArray {
        val clean = StringBuilder(text.length)
        for (c in text) if (!c.isWhitespace()) clean.append(c)
        val s = clean.toString()
        if (s.isEmpty()) return ByteArray(0)
        if (s.length % 4 != 0) throw IllegalArgumentException("非法的 Base64 长度: ${s.length}")

        val out = ByteArray(s.length / 4 * 3)
        var o = 0
        var i = 0
        while (i < s.length) {
            var n = 0
            var pad = 0
            for (j in 0 until 4) {
                val c = s[i + j]
                if (c == '=') {
                    pad++
                    n = n shl 6
                } else {
                    val v = TABLE.indexOf(c)
                    require(v >= 0) { "非法的 Base64 字符: $c" }
                    n = (n shl 6) or v
                }
            }
            out[o++] = ((n ushr 16) and 0xFF).toByte()
            if (pad < 2) out[o++] = ((n ushr 8) and 0xFF).toByte()
            if (pad < 1) out[o++] = (n and 0xFF).toByte()
            i += 4
        }
        return if (o == out.size) out else out.copyOf(o)
    }
}
