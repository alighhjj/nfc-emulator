package io.github.nfcemu.core

/**
 * NXP NFC 控制器 listen 配置里的 TAG_ID 参数（NCI CORE_CONF tag `0x33`）。
 *
 * 背景：NXP 的 NFCC 固件允许通过 `NXP_CORE_CONF` 里的私有参数 `0x33` 覆盖
 * ISO14443-3 层的 NFCID1，这正是"免 root 无法实现、root 后唯一可行"的改 UID 通道。
 * 值的字节长度直接决定卡片是单长/双长/三长：
 *
 * ```
 * 33 04 42 4D 44 4A              // 4 字节，单长
 * 33 07 42 4D 44 4A 00 11 22     // 7 字节，双长
 * 33 0A AA BB CC DD EE FF 00 11 22 33  // 10 字节，三长
 * ```
 *
 * 本类只负责「在一段字节里精确定位这个参数并替换值」，不负责读写文件、
 * 不负责重启服务 —— 那些属于 app 模块。保持纯函数是它能被单元测试覆盖的前提。
 */
object NxpCoreConfCodec {

    /** NXP 私有参数 TAG_ID / NFCID1。 */
    const val TAG_ID = 0x33

    /** TAG_ID 参数合法的 value 长度。 */
    val TAG_ID_LENGTHS: Set<Int> = Uid.SUPPORTED_SIZES

    /**
     * 参数在字节流里的位置。
     *
     * @param offset `tag` 字节所在下标
     * @param length value 字节数（不含 tag / length 两个字节）
     */
    data class Span(val offset: Int, val length: Int) {
        val valueStart: Int get() = offset + 2
        val endExclusive: Int get() = offset + 2 + length
        val encodedSize: Int get() = length + 2
    }

    /**
     * 定位 TAG_ID 参数。
     *
     * 两级策略：
     *  1. **严格 TLV 链**：从偏移 0 开始按 `tag/len/value` 走，走通且中途遇到 0x33 就用它。
     *     这是最可信的情况，NCI 的 CORE_SET_CONFIG payload 就是这种形态。
     *  2. **裸扫描回退**：NXP 的 `NXP_CORE_CONF` 文本块在头部带一段非 TLV 前缀
     *     （`20, 02, 2D, 02, 08, 05, …`），严格链走不通。此时退化为"在任意偏移上找
     *     `0x33` + 合法长度，并用『其后的尾部能否解析成完整 TLV 链』来消歧"。
     *
     * 返回 null 表示这段字节里没有 TAG_ID。
     */
    fun locate(conf: ByteArray): Span? {
        walkChain(conf)?.let { return it }
        return scanRaw(conf)
    }

    fun read(conf: ByteArray): Uid? {
        val span = locate(conf) ?: return null
        return Uid.of(conf.copyOfRange(span.valueStart, span.endExclusive))
    }

    /**
     * 等长替换。**这是运行时注入路径（手机端）唯一安全的方式**：
     * 因为外层可能还有长度字段（NCI 包的 payload length、Magisk 模块的 blob 长度），
     * 一旦改变长度就要连带修一串值，任何一处算错都会让 NfcService 起不来。
     *
     * @throws TlvFormatException 找不到 TAG_ID
     * @throws IllegalArgumentException 长度不一致
     */
    fun replaceEqualLength(conf: ByteArray, uid: Uid): ByteArray {
        val span = locate(conf)
            ?: throw TlvFormatException("配置块里找不到 NXP TAG_ID(0x33) 参数，无法改写 UID")
        require(span.length == uid.size) {
            "长度不一致：原 TAG_ID 为 ${span.length} 字节，新 UID 为 ${uid.size} 字节。" +
                "运行时注入只支持等长替换，请改用 Magisk 模块路径（会整份重写 conf 文件）。"
        }
        val out = conf.copyOf()
        uid.bytes.copyInto(out, span.valueStart)
        return out
    }

    /**
     * 允许变长替换 —— **只用于整份重写 conf 文本文件**的场景。
     *
     * 安全性前提：宿主结构必须是"扁平列表"（如 `NXP_CORE_CONF={ 20, 02, ... }`），
     * 长度由解析方自己数，没有外层长度字段。若宿主是带外层 length 的二进制 blob，
     * 用这个方法会把结构写坏 —— 所以它叫 `replaceAllowResize` 而不是无脑的 `replace`。
     */
    fun replaceAllowResize(conf: ByteArray, uid: Uid): ByteArray {
        val span = locate(conf)
            ?: throw TlvFormatException("配置块里找不到 NXP TAG_ID(0x33) 参数，无法改写 UID")
        if (span.length == uid.size) return replaceEqualLength(conf, uid)

        val head = conf.copyOfRange(0, span.offset)
        val tail = conf.copyOfRange(span.endExclusive, conf.size)
        val out = ByteArray(head.size + 2 + uid.size + tail.size)
        var off = 0
        head.copyInto(out, off); off += head.size
        out[off++] = TAG_ID.toByte()
        out[off++] = uid.size.toByte()
        uid.bytes.copyInto(out, off); off += uid.size
        tail.copyInto(out, off)
        return out
    }

    /**
     * 如果这段字节本身就是一条完整的 TLV 链，按 TLV 语义替换（并保持其余参数逐字节不变）；
     * 否则走 [replaceAllowResize] 的拼接路径。
     */
    fun replaceSmart(conf: ByteArray, uid: Uid): ByteArray {
        val chain = Tlv.parseOrNull(conf)
        if (chain != null && chain.any { it.tag == TAG_ID }) {
            val rebuilt = chain.map { if (it.tag == TAG_ID) TlvParam(TAG_ID, uid.bytes) else it }
            return Tlv.serialize(rebuilt)
        }
        return replaceAllowResize(conf, uid)
    }

    /** 这段字节里 TAG_ID 是否已经等于给定 UID（用于"是否需要重启 NfcService"的判断）。 */
    fun alreadySet(conf: ByteArray, uid: Uid): Boolean {
        val current = read(conf) ?: return false
        return current == uid
    }

    // ---------------------------------------------------------------- 内部

    private fun walkChain(conf: ByteArray): Span? {
        var i = 0
        while (i + 2 <= conf.size) {
            val tag = conf[i].toInt() and 0xFF
            val len = conf[i + 1].toInt() and 0xFF
            if (i + 2 + len > conf.size) return null
            if (tag == TAG_ID && len in TAG_ID_LENGTHS) return Span(i, len)
            if (tag == 0x00 && len == 0x00) return null
            i += 2 + len
        }
        return null
    }

    private fun scanRaw(conf: ByteArray): Span? {
        val candidates = ArrayList<Span>(2)
        var i = 0
        while (i + 2 <= conf.size) {
            if ((conf[i].toInt() and 0xFF) == TAG_ID) {
                val len = conf[i + 1].toInt() and 0xFF
                if (len in TAG_ID_LENGTHS && i + 2 + len <= conf.size) {
                    candidates.add(Span(i, len))
                }
            }
            i++
        }
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates[0]

        // 多个候选时，优先取「它之后到结尾仍是一条完整 TLV 链」的那个：
        // 真正的 TAG_ID 通常排在 CORE_CONF 的中后段，且它后面还有别的参数。
        val withCoherentTail = candidates.filter { tailIsCoherent(conf, it.endExclusive) }
        return withCoherentTail.firstOrNull() ?: candidates.first()
    }

    private fun tailIsCoherent(conf: ByteArray, start: Int): Boolean {
        if (start == conf.size) return true
        var i = start
        while (i < conf.size) {
            if (conf.size - i < 2) return false
            val len = conf[i + 1].toInt() and 0xFF
            val tag = conf[i].toInt() and 0xFF
            if (tag == 0x00 && len == 0x00) {
                for (j in i until conf.size) if (conf[j].toInt() != 0) return false
                return true
            }
            if (i + 2 + len > conf.size) return false
            i += 2 + len
        }
        return true
    }
}
