package io.github.nfcemu.core

/**
 * 把一段十六进制文本里的每个字节映射回它在字符串中的字符区间。
 *
 * 存在的理由：改 UID 时我们希望**只动那几个字符**，而不是整块重新排版。
 * 因为 NXP 的 conf 块通常按「一行一个参数」人工排版：
 *
 * ```
 * NXP_CORE_CONF={ 20, 02, 2D,
 *                 02, 08, 05,
 *                 33, 04, 42, 4D, 44, 4A,
 *                 35, 06, 01, 01, 01, 02, 07, 46 }
 * ```
 *
 * 如果按「每行固定 N 字节」重新编码，这些语义分组会全部被打散，代码 review 时
 * 完全看不出改了什么 —— 对一个会写坏设备 NFC 配置的项目来说这是不可接受的。
 */
object HexTextIndex {

    /**
     * @return 每个字节对应的 `[startInclusive, endExclusive)`，下标与字节下标一一对应。
     *         文本不是合法十六进制（奇数字节、出现非法字符）时返回 null。
     */
    fun index(text: String): IntArray? {
        val starts = ArrayList<Int>(text.length / 2)
        val ends = ArrayList<Int>(text.length / 2)

        var i = 0
        var pendingStart = -1
        var pendingEnd = -1

        fun flush() {
            if (pendingStart >= 0) {
                starts.add(pendingStart)
                ends.add(pendingEnd + 1)
                pendingStart = -1
                pendingEnd = -1
            }
        }

        while (i < text.length) {
            val c = text[i]
            if (c == '0' && i + 1 < text.length && (text[i + 1] == 'x' || text[i + 1] == 'X')) {
                i += 2
                continue
            }
            if (!isHexDigit(c)) {
                i++
                continue
            }
            if (pendingStart < 0) {
                pendingStart = i
                pendingEnd = i
            } else {
                pendingEnd = i
                flush()
            }
            i++
        }
        if (pendingStart >= 0) return null // 奇数字符

        val out = IntArray(starts.size * 2)
        for (k in starts.indices) {
            out[k * 2] = starts[k]
            out[k * 2 + 1] = ends[k]
        }
        return out
    }

    private fun isHexDigit(c: Char): Boolean = when (c) {
        in '0'..'9', in 'a'..'f', in 'A'..'F' -> true
        else -> false
    }
}

/**
 * 对 `libnfc-nxp.conf` 这类纯文本配置做「区块定位 + 定点改写」。
 *
 * 为什么不用旧方案的正则（`33:0[4,7A]:` 全文匹配）：
 *  1. 全文匹配会误伤 `NXP_CORE_CONF_EXTN`、注释、甚至别的参数的值；
 *  2. 正则改写会顺手把分隔符/大小写/换行风格改掉，人工 diff 时看不出改了什么；
 *  3. 不同 OEM 的分隔符有 `,` / `:` / 纯空格三种，正则要写 N 份。
 *
 * 这里的做法：把 `KEY={ … }` 区块内的字节**解码成 ByteArray** → 在字节层面定位
 * 目标参数 → 再用 [HexTextIndex] 把字节区间映射回字符区间，**只替换那几个字符**。
 * 区块之外的每一个字节都原样保留。
 */
object NfcConfFile {

    /** NXP 平台的核心配置键（绝大多数机型 listen 参数在这里）。 */
    const val KEY_NXP_CORE_CONF = "NXP_CORE_CONF"

    /** NXP 扩展配置键（部分机型把 listen 参数放这里）。 */
    const val KEY_NXP_CORE_CONF_EXTN = "NXP_CORE_CONF_EXTN"

    /** Broadcom 平台的启动配置键。本项目暂未实现该平台，仅登记键名用于诊断展示。 */
    const val KEY_NFA_DM_START_UP_CFG = "NFA_DM_START_UP_CFG"

    /**
     * 一个 `KEY = { … }` 区块的位置信息。
     *
     * @param valueStart `{` 之后的下标
     * @param valueEnd   配对的 `}` 下标
     */
    data class Block(
        val key: String,
        val keyStart: Int,
        val valueStart: Int,
        val valueEnd: Int,
    )

    data class EditResult(
        val text: String,
        val oldUid: Uid?,
        val newUid: Uid,
        val lengthChanged: Boolean,
        val key: String,
        /** 是否走了「逐字符定点替换」这条精确路径。false 表示退化成了整块重排。 */
        val precise: Boolean,
    )

    // ------------------------------------------------------------ 定位

    /**
     * 找到 `KEY = { … }` 区块。
     *
     * 用负向后视模拟词边界，避免 `NXP_CORE_CONF` 匹配到 `NXP_CORE_CONF_EXTN`。
     * 大括号按深度配对，允许值里出现 `{`。
     */
    fun findBlock(text: String, key: String): Block? {
        val re = Regex("(?<![A-Za-z0-9_.])" + Regex.escape(key) + "\\s*=\\s*\\{")
        val m = re.find(text) ?: return null
        val braceIdx = m.range.last
        var depth = 1
        var i = braceIdx + 1
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return Block(key = key, keyStart = m.range.first, valueStart = braceIdx + 1, valueEnd = i)
                    }
                }
            }
            i++
        }
        return null
    }

    fun containsKey(text: String, key: String): Boolean = findBlock(text, key) != null

    /**
     * 枚举文件里所有 `XXX={...}` 形态的键，用于兼容性诊断页展示。
     * 只认「行首（允许缩进）+ 大写键名」的写法。
     */
    fun listKeys(text: String): List<String> {
        val re = Regex("(?m)^[ \\t]*([A-Z][A-Z0-9_]*)\\s*=\\s*\\{")
        return re.findAll(text).map { it.groupValues[1] }.distinct().toList()
    }

    /** 读出某个区块的原始字节。不存在或解析不了返回 null。 */
    fun readBytes(text: String, key: String): ByteArray? {
        val block = findBlock(text, key) ?: return null
        return Hex.decodeOrNull(text.substring(block.valueStart, block.valueEnd))
    }

    /** 读出某个区块里的 UID。 */
    fun readUid(text: String, key: String): Uid? {
        val bytes = readBytes(text, key) ?: return null
        return NxpCoreConfCodec.read(bytes)
    }

    // ------------------------------------------------------------ 改写

    /**
     * 把某个 `KEY={…}` 区块里的 NXP TAG_ID(0x33) 换成 [uid]。
     *
     * 文本配置里不存在外层长度字段，因此**允许变长**（4↔7↔10 都可以），
     * 这是 Magisk 模块路径相对运行时注入的核心优势。
     */
    fun replaceTagId(text: String, key: String, uid: Uid): EditResult {
        val block = findBlockOrThrow(text, key)

        val inner = text.substring(block.valueStart, block.valueEnd)
        val bytes = Hex.decode(inner)
        val span = NxpCoreConfCodec.locate(bytes)
            ?: throw TlvFormatException("`$key` 区块里找不到 NXP TAG_ID(0x33) 参数")
        val oldUid = Uid.of(bytes.copyOfRange(span.valueStart, span.endExclusive))

        // 首选：逐字符定点替换，排版零扰动
        val ranges = HexTextIndex.index(inner)
        if (ranges != null && ranges.size == bytes.size * 2) {
            val sameSize = oldUid.size == uid.size
            // 长度变了就必须连 tag 后面的 length 字节一起改，否则 conf 自相矛盾
            val replaceFrom = if (sameSize) span.valueStart else span.offset + 1
            val payload = if (sameSize) uid.bytes else byteArrayOf(uid.size.toByte()) + uid.bytes

            val charStart = ranges[replaceFrom * 2]
            val charEnd = ranges[span.endExclusive * 2 - 1]
            val newHex = encodeLike(inner, payload)
            val newInner = inner.substring(0, charStart) + newHex + inner.substring(charEnd)
            return EditResult(
                text = text.substring(0, block.valueStart) + newInner + text.substring(block.valueEnd),
                oldUid = oldUid,
                newUid = uid,
                lengthChanged = !sameSize,
                key = key,
                precise = true,
            )
        }

        // 兜底：整块重排（字节数变了又拿不到字符映射时才会走到）
        val newBytes = NxpCoreConfCodec.replaceAllowResize(bytes, uid)
        return EditResult(
            text = renderBlock(text, block, newBytes),
            oldUid = oldUid,
            newUid = uid,
            lengthChanged = oldUid.size != uid.size,
            key = key,
            precise = false,
        )
    }

    private fun findBlockOrThrow(text: String, key: String): Block =
        findBlock(text, key) ?: throw TlvFormatException("配置文件里找不到 `$key={ … }` 区块")

    /** 按区块原有的分隔符与大小写风格编码一段新字节。 */
    private fun encodeLike(inner: String, payload: ByteArray): String {
        val body = inner.trim()
        val upper = body.none { it in 'a'..'f' }
        val separator = when {
            body.contains(',') -> if (body.contains(", ")) ", " else ","
            body.contains(':') -> if (body.contains(": ")) ": " else ":"
            body.contains(' ') || body.contains('\t') -> " "
            else -> ""
        }
        return Hex.encode(payload, upperCase = upper, separator = separator)
    }

    /** 把整个区块内容替换成 [bytes]，尽量沿用原排版风格。 */
    fun renderBlock(text: String, block: Block, bytes: ByteArray): String {
        val inner = text.substring(block.valueStart, block.valueEnd)

        var leadEnd = 0
        while (leadEnd < inner.length && inner[leadEnd].isWhitespace()) leadEnd++
        var trailStart = inner.length
        while (trailStart > leadEnd && inner[trailStart - 1].isWhitespace()) trailStart--

        val lead = inner.substring(0, leadEnd)
        val body = inner.substring(leadEnd, trailStart)
        val trail = inner.substring(trailStart)

        val style = HexStyle.detect(body)
        return text.substring(0, block.valueStart) + lead + style.format(bytes) + trail + text.substring(block.valueEnd)
    }

    // ------------------------------------------------------------ 排版风格

    /** 一段十六进制排版的外在风格，仅用于兜底路径。 */
    data class HexStyle(
        val upperCase: Boolean,
        val separator: String,
        val spaceAfterSeparator: Boolean,
        val bytesPerLine: Int,
        val linePrefix: String,
    ) {
        fun format(bytes: ByteArray): String {
            val sep = if (spaceAfterSeparator) "$separator " else separator
            return Hex.encode(
                bytes = bytes,
                upperCase = upperCase,
                separator = sep,
                bytesPerLine = bytesPerLine,
                linePrefix = linePrefix,
            )
        }

        companion object {
            fun detect(body: String): HexStyle {
                val upper = body.none { it in 'a'..'f' }
                val separator = when {
                    body.contains(',') -> ","
                    body.contains(':') -> ":"
                    body.contains(' ') || body.contains('\t') -> " "
                    else -> ""
                }
                val spaceAfter = separator.isNotEmpty() && separator != " " && body.contains("$separator ")

                val lines = body.split('\n')
                val multi = lines.size > 1
                val bytesPerLine = if (!multi) 0 else countBytes(lines[0])
                val linePrefix = if (!multi) "" else lines[1].takeWhile { it == ' ' || it == '\t' }

                return HexStyle(upper, separator, spaceAfter, bytesPerLine, linePrefix)
            }

            private fun countBytes(line: String): Int = Hex.compact(line).length / 2
        }
    }
}
