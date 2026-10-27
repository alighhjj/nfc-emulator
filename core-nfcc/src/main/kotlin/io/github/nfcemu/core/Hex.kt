package io.github.nfcemu.core

/**
 * 十六进制编解码。
 *
 * 为什么不用现成的：
 *  - `String.format("%02X", b)` 每字节一次字符串分配，配置块动辄数千字节，很浪费；
 *  - `BigInteger(hex, 16)` 会吃掉前导的 `0x00`。UID 首字节就是 0x00 时，
 *    字节数会静默变少，后续所有偏移全错 —— 这是必须自己实现的原因。
 */
object Hex {

    private const val UPPER = "0123456789ABCDEF"
    private const val LOWER = "0123456789abcdef"

    /** 解码时忽略的分隔符。刻意放宽：不同 OEM 的 conf 文件风格差异极大。 */
    private fun isSeparator(c: Char): Boolean = when (c) {
        ' ', '\t', '\r', '\n', ',', ':', ';', '-', '_', '|', '/', '\\' -> true
        else -> false
    }

    private fun digitOf(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> throw IllegalArgumentException("非法十六进制字符: '$c' (0x%02X)".format(c.code))
    }

    /**
     * 编码。`separator` 为每字节之间的分隔串（可为空），`bytesPerLine` > 0 时按字节数换行，
     * 续行前缀为 `linePrefix`。
     *
     * 例：encode(bytes, upperCase = true, separator = ", ", bytesPerLine = 16, linePrefix = "        ")
     *     → "20, 02, 2D, ...\n        02, 08, 05, ..."
     */
    fun encode(
        bytes: ByteArray,
        upperCase: Boolean = true,
        separator: String = "",
        bytesPerLine: Int = 0,
        linePrefix: String = "",
    ): String {
        val table = if (upperCase) UPPER else LOWER
        val sb = StringBuilder(bytes.size * (2 + separator.length) + 8)
        for (i in bytes.indices) {
            if (i > 0) {
                if (bytesPerLine > 0 && i % bytesPerLine == 0) {
                    sb.append('\n').append(linePrefix)
                } else {
                    sb.append(separator)
                }
            }
            val v = bytes[i].toInt() and 0xFF
            sb.append(table[v ushr 4]).append(table[v and 0x0F])
        }
        return sb.toString()
    }

    /**
     * 严格解码：容忍任意分隔符与 `0x` 前缀，其余字符必须是十六进制。
     * 长度为奇数或出现非法字符时抛 [IllegalArgumentException]。
     */
    fun decode(text: String): ByteArray {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '0' && i + 1 < text.length && (text[i + 1] == 'x' || text[i + 1] == 'X')) {
                i += 2
                continue
            }
            if (isSeparator(c)) {
                i++
                continue
            }
            sb.append(c)
            i++
        }
        if (sb.length % 2 != 0) {
            throw IllegalArgumentException(
                "十六进制串长度为奇数(${sb.length})，无法按字节解析：" +
                    sb.substring(0, minOf(sb.length, 48)) + "…"
            )
        }
        val out = ByteArray(sb.length / 2)
        for (k in out.indices) {
            val hi = digitOf(sb[k * 2])
            val lo = digitOf(sb[k * 2 + 1])
            out[k] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /** 宽松解码：任何异常都返回 null。用于"这段像不像十六进制"的探测。 */
    fun decodeOrNull(text: String): ByteArray? = try {
        decode(text)
    } catch (e: RuntimeException) {
        null
    }

    /** 只保留十六进制字符（丢弃 0x 前缀与分隔符），不校验奇偶。 */
    fun compact(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '0' && i + 1 < text.length && (text[i + 1] == 'x' || text[i + 1] == 'X')) {
                i += 2
                continue
            }
            if (!isSeparator(c)) sb.append(c)
            i++
        }
        return sb.toString()
    }
}
