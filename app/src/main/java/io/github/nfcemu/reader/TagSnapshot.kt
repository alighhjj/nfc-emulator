package io.github.nfcemu.reader

/**
 * 一张被读到的卡的快照。纯数据，不带 Tag 引用 ——
 * Tag 一旦离开感应区就失效，把 Tag 对象留在内存里后续访问全是 IllegalStateException。
 */
data class TagSnapshot(
    val uidHex: String,
    val uidBytes: Int,
    val techs: List<String>,
    val kind: CardKind,
    val seenAt: Long,

    val atqa: String? = null,
    val sak: String? = null,

    val mifareType: String? = null,
    val mifareSize: String? = null,
    val mifareSectors: String? = null,

    val isoDepHistorical: String? = null,
    val isoDepExtended: Boolean? = null,

    val nfcBAppData: String? = null,
    val nfcBProtocol: String? = null,
    val nfcFSystemCode: String? = null,
    val nfcFManufacturer: String? = null,
    val nfcVDsfId: String? = null,

    val maxTransceive: Int? = null,
    val notes: List<String> = emptyList(),
) {

    /** 标题行：类型 + UID。 */
    val title: String get() = kind.label

    /** 明细行，直接喂给 UI。 */
    fun rows(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>(10)
        out.add("UID" to uidHex + "  ($uidBytes 字节)")
        out.add("技术栈" to techs.joinToString(", "))
        atqa?.let { out.add("ATQA" to it) }
        sak?.let { out.add("SAK" to it) }
        mifareType?.let { out.add("MIFARE 型号" to it) }
        mifareSize?.let { out.add("容量" to it) }
        mifareSectors?.let { out.add("扇区/块" to it) }
        isoDepHistorical?.let { out.add("历史字节" to it) }
        isoDepExtended?.let { out.add("扩展长度 APDU" to if (it) "支持" else "不支持") }
        nfcBAppData?.let { out.add("应用数据" to it) }
        nfcBProtocol?.let { out.add("协议信息" to it) }
        nfcFSystemCode?.let { out.add("System Code" to it) }
        nfcFManufacturer?.let { out.add("厂商代码" to it) }
        nfcVDsfId?.let { out.add("DSFID" to it) }
        maxTransceive?.let { out.add("最大传输长度" to "$it 字节") }
        return out
    }

    /** 导出成纯文本，用于复制/分享排查。 */
    fun toPlainText(): String = buildString {
        appendLine("[$title]")
        rows().forEach { (k, v) -> appendLine("  $k: $v") }
        appendLine("  结论: ${kind.emulation}")
        notes.forEach { appendLine("  备注: $it") }
    }
}
