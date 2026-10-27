package io.github.nfcemu.core

/** TLV 解析失败的统一异常，带上出错偏移，方便对着 hexdump 定位。 */
class TlvFormatException(message: String, val offset: Int = -1) : IllegalArgumentException(message)

/**
 * 一条 NCI 配置参数：单字节 Tag + 单字节 Length + Value。
 *
 * 不需要扩展长度字段的原因：NCI 规定 CORE_SET_CONFIG 的单个参数长度 ≤ 255，
 * NXP 的 NXP_CORE_CONF、Broadcom 的 NFA_DM_START_UP_CFG 都遵守这条，
 * 所以本项目按"1 字节 tag / 1 字节 length"实现即可，避免过度设计。
 *
 * 不变量：
 *  - `tag` ∈ [0x00, 0xFF]
 *  - `value.size` ∈ [0x00, 0xFF]
 *  - `value` 对外只读（构造时已拷贝，getter 再拷贝一次）
 */
class TlvParam(tag: Int, value: ByteArray) {

    val tag: Int = tag
    val value: ByteArray = value.copyOf()

    init {
        require(tag in 0x00..0xFF) { "TLV tag 越界: 0x${tag.toString(16)}" }
        require(this.value.size <= 0xFF) { "TLV value 过长: ${this.value.size} > 255" }
    }

    val length: Int get() = value.size

    /** 编码后占用的字节数（含 tag 与 length 两个字节）。 */
    val encodedSize: Int get() = 2 + value.size

    fun encode(): ByteArray {
        val out = ByteArray(encodedSize)
        out[0] = tag.toByte()
        out[1] = value.size.toByte()
        value.copyInto(out, 2)
        return out
    }

    override fun equals(other: Any?): Boolean =
        other is TlvParam && other.tag == tag && other.value.contentEquals(value)

    override fun hashCode(): Int = 31 * tag + value.contentHashCode()

    override fun toString(): String =
        "Tlv(0x${tag.toString(16).uppercase().padStart(2, '0')}, len=$length, ${Hex.encode(value)})"
}

/**
 * NCI 参数的 TLV 序列编解码。
 *
 * 解析策略是「宁可抛异常，也不要静默错位」：一旦某个 TLV 的 length 超出剩余长度，
 * 说明我们的结构假设错了，此时必须让上层知道，而不是返回一个"看起来解析成功"的截断列表
 * —— 静默错位会导致后续把 UID 写到错误的偏移上，把设备的 NFC 配置写坏。
 */
object Tlv {

    fun parse(bytes: ByteArray): List<TlvParam> = parse(bytes, 0, bytes.size)

    fun parse(bytes: ByteArray, from: Int, to: Int): List<TlvParam> {
        require(from in 0..bytes.size) { "from 越界: $from" }
        require(to in from..bytes.size) { "to 越界: $to" }

        val out = ArrayList<TlvParam>()
        var i = from
        while (i < to) {
            if (to - i < 2) {
                throw TlvFormatException("偏移 $i 处剩余 ${to - i} 字节，不足以构成 TLV 头", i)
            }
            val tag = bytes[i].toInt() and 0xFF
            val len = bytes[i + 1].toInt() and 0xFF

            // 0x00/0x00 是约定俗成的终止符/填充。其后必须全是 0x00。
            if (tag == 0x00 && len == 0x00) {
                for (j in i until to) {
                    if (bytes[j].toInt() != 0) {
                        throw TlvFormatException("终止符($i)之后仍有非零字节($j)", j)
                    }
                }
                return out
            }
            if (i + 2 + len > to) {
                throw TlvFormatException(
                    "偏移 $i 处 tag=0x${tag.toString(16)} len=$len 超出边界(剩余 ${to - i - 2})",
                    i,
                )
            }
            out.add(TlvParam(tag, bytes.copyOfRange(i + 2, i + 2 + len)))
            i += 2 + len
        }
        return out
    }

    fun parseOrNull(bytes: ByteArray): List<TlvParam>? = try {
        parse(bytes)
    } catch (e: RuntimeException) {
        null
    }

    fun serialize(params: List<TlvParam>): ByteArray {
        var total = 0
        for (p in params) total += p.encodedSize
        val out = ByteArray(total)
        var off = 0
        for (p in params) {
            out[off] = p.tag.toByte()
            out[off + 1] = p.value.size.toByte()
            p.value.copyInto(out, off + 2)
            off += p.encodedSize
        }
        return out
    }

    /** 只用于调试输出，格式 `33 04 42 4D 44 4A`。 */
    fun describe(params: List<TlvParam>): String =
        params.joinToString("\n") { "0x${it.tag.toString(16).uppercase().padStart(2, '0')} len=${it.length} ${Hex.encode(it.value)}" }
}
