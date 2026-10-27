package io.github.nfcemu.core

import kotlin.random.Random

/**
 * ISO/IEC 14443-3 定义的 NFCID1（即俗称的 UID / 卡号）。
 *
 * 长度只有三种合法取值：
 *  - 4  字节：单长（CL1，4 字节 UID）
 *  - 7  字节：双长（CL1 3 字节 + CL2 4 字节）
 *  - 10 字节：三长（CL1 3 字节 + CL2 3 字节 + CL3 4 字节）
 *
 * **这是本项目存在的全部理由**：HCE 只能模拟 ISO-DEP（14443-4）层，NFCID1 由
 * NFC 控制器在硬件层产生，App 无法指定。要改 UID 只能改控制器的 listen 配置
 * （NXP 的 TAG_ID 参数 / 0x33），而那需要 root 改 /vendor/etc 下的 conf 文件。
 */
class Uid private constructor(private val raw: ByteArray) {

    val size: Int get() = raw.size

    /** 返回副本，避免调用方把内部的不可变状态改掉。 */
    val bytes: ByteArray get() = raw.copyOf()

    operator fun get(index: Int): Byte = raw[index]

    fun toHex(separator: String = "", upperCase: Boolean = true): String =
        Hex.encode(raw, upperCase = upperCase, separator = separator)

    /** 形如 `42:4D:44:4A`，给人看。 */
    fun toDisplay(): String = toHex(separator = ":")

    /**
     * 软告警：不影响写入，但很可能导致读卡器行为异常，UI 上要提示用户。
     *
     * 刻意做成"告警"而不是"拒绝"：很多门禁系统确实在用非标 UID，
     * 硬拒会把用户挡在门外。
     */
    fun warnings(): List<String> {
        val out = ArrayList<String>(2)

        if (raw.size == 4 && (raw[0].toInt() and 0xFF) == 0x08) {
            out.add(
                "首字节 0x08 在 ISO14443-3 里被保留为级联标记(CT)。作为单长 UID 使用时，" +
                    "部分读卡器会误判为双长/三长卡片并继续发 RATS/级联请求，导致识别失败。建议改成 0x42 等其他值。"
            )
        }
        if (raw.all { it.toInt() == 0 }) {
            out.add("全 0x00 的 UID 会被多数 NFC 控制器直接丢弃，请换一个值。")
        }
        if (raw.all { it == raw[0] }) {
            out.add("所有字节相同（如 11:11:11:11），部分门禁系统会判定为非法卡号。")
        }
        if (raw.size == 10) {
            out.add("10 字节（三长）UID 兼容性最差，不少老门禁只支持 4 字节。")
        }
        return out
    }

    override fun equals(other: Any?): Boolean = other is Uid && other.raw.contentEquals(raw)

    override fun hashCode(): Int = raw.contentHashCode()

    override fun toString(): String = "Uid(${toDisplay()}, ${size}B)"

    companion object {

        /** ISO14443-3 允许的 NFCID1 字节长度。 */
        val SUPPORTED_SIZES: Set<Int> = setOf(4, 7, 10)

        fun of(bytes: ByteArray): Uid {
            require(bytes.size in SUPPORTED_SIZES) {
                "NFCID1 只能是 4/7/10 字节，收到 ${bytes.size} 字节: ${Hex.encode(bytes)}"
            }
            return Uid(bytes.copyOf())
        }

        fun of(vararg bytes: Int): Uid {
            val arr = ByteArray(bytes.size)
            for (i in bytes.indices) {
                require(bytes[i] in 0..0xFF) { "第 $i 个字节越界: ${bytes[i]}" }
                arr[i] = bytes[i].toByte()
            }
            return of(arr)
        }

        fun of(hex: String): Uid = of(Hex.decode(hex))

        fun ofOrNull(hex: String): Uid? = try {
            of(Hex.decode(hex))
        } catch (e: RuntimeException) {
            null
        }

        /**
         * 生成一个"看起来像真实卡片"的随机 UID。
         *
         * 首字节刻意避开 0x08（级联标记）与 0x00，其余字节完全随机。
         * 注意：不同厂商的 UID 段有 OUI 划分，但 MIFARE Classic 的 UID 是厂家
         * 出厂烧写的 4 字节，不存在 OUI 约束，所以随机是可行的。
         */
        fun random(size: Int = 4, random: Random = Random.Default): Uid {
            require(size in SUPPORTED_SIZES) { "不支持的长度: $size" }
            val arr = ByteArray(size)
            random.nextBytes(arr)
            if (arr[0].toInt() == 0x08 || arr[0].toInt() == 0x00) {
                // 1..246，遇到 8 就跳到 9，最终落在 [1,7] ∪ [9,247]
                var v = 1 + random.nextInt(0xF6)
                if (v >= 0x08) v += 1
                arr[0] = v.toByte()
            }
            return of(arr)
        }
    }
}
