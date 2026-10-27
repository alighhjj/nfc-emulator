package io.github.nfcemu.reader

/**
 * 判卡输入：只有"有哪些技术栈 + SAK/ATQA"这几项。
 *
 * 刻意拆成纯数据：`android.nfc.Tag` 一旦离开感应区就废了，而且没法在 JVM 单测里造出来。
 * 把判定逻辑锁在这个 data class 上，就能在 CI 的 `:app:testDebugUnitTest` 里跑几百个用例，
 * 而不需要一台真机或读卡器。
 *
 * @property sak NFC-A 的 SAK（1 字节，0..255）。null = 没取到（非 NFC-A，或读取失败）。
 * @property atqa NFC-A 的 ATQA（2 字节按大端拼成 Int，即 atqa[0] shl 8 or atqa[1]）。
 */
data class TechProfile(
    val hasNfcA: Boolean = false,
    val hasNfcB: Boolean = false,
    val hasNfcF: Boolean = false,
    val hasNfcV: Boolean = false,
    val hasIsoDep: Boolean = false,
    val hasMifareClassic: Boolean = false,
    val hasMifareUltralight: Boolean = false,
    val hasNdef: Boolean = false,
    val sak: Int? = null,
    val atqa: Int? = null,
)

/** 判定结果：类型 + 判定过程中值得告诉用户的旁证。 */
data class Classification(
    val kind: CardKind,
    val notes: List<String> = emptyList(),
)

/**
 * 卡片类型判定。
 *
 * 判定顺序是有讲究的，不能随便换：
 *  1. FeliCa / ISO15693 优先 —— 它们是独立协议族，即便同时挂了别的 tech 也轮不到 NFC-A 判。
 *  2. **IsoDep 优先于 MifareClassic** —— SAK 0x28/0x38 的双协议卡（SmartMX 门禁卡很常见）
 *     会同时暴露这两个 tech。此时"能用 APDU 通信"是更有价值的信息，
 *     因为那正是 HCE 唯一能覆盖的层面。若先判 Classic，就会把唯一有戏的卡判成没戏。
 *  3. Classic 优先于 Ultralight —— 两者都可能只有 NfcA，Classic 的 UID 可改诉求更强。
 *  4. 最后用 SAK 做兜底猜测，猜错也只影响"建议"，不影响功能。
 */
object TagClassifier {

    /** SAK bit5：符合 ISO/IEC 14443-4，即支持 ISO-DEP。 */
    const val SAK_ISO_DEP = 0x20

    /** SAK bit6：符合 ISO/IEC 18092，即 NFC-DEP（点对点，不是卡）。 */
    const val SAK_NFC_DEP = 0x40

    /** SAK bit7：厂商自定义位。置位时型号推断不可靠。 */
    const val SAK_VENDOR = 0x80

    /** SAK bit4：MIFARE Classic 4K 家族。 */
    const val SAK_CLASSIC_4K = 0x10

    /** SAK bit3：MIFARE Classic 1K 家族。 */
    const val SAK_CLASSIC_1K = 0x08

    fun classify(p: TechProfile): Classification {
        val notes = ArrayList<String>(3)

        // 双协议卡是最容易被误判、也最值得单独指出来的一类。
        val dualProtocol = p.hasIsoDep && p.hasMifareClassic
        if (dualProtocol) {
            notes.add(
                "双协议卡：ISO-DEP 与 MIFARE Classic 同时可用（SAK 0x28/0x38 常见于 SmartMX 门禁卡）。" +
                    "这意味着它的数据层可以用 APDU 读取，是本项目唯一有可能" +
                    "「换 UID + 转发 APDU」做成的一条路。"
            )
        }
        if (p.hasNdef) {
            notes.add("带 NDEF：系统会把它当普通标签处理，贴上去可能先弹系统界面。")
        }
        p.sak?.let { sak ->
            if (sak and SAK_VENDOR != 0) {
                notes.add("SAK 置了厂商位（0x80），下面的型号推断只是猜测。")
            }
            if (sak and SAK_NFC_DEP != 0) {
                notes.add("SAK 置了 NFC-DEP 位（0x40），这张「卡」可能其实是另一台设备在扮演。")
            }
        }

        val kind = when {
            p.hasNfcF -> CardKind.FELICA
            p.hasNfcV -> CardKind.ISO15693
            dualProtocol -> CardKind.ISO_DEP_CPU
            p.hasIsoDep -> CardKind.ISO_DEP_CPU
            p.hasNfcB -> CardKind.NFC_B
            p.hasMifareClassic -> CardKind.MIFARE_CLASSIC
            p.hasMifareUltralight -> CardKind.MIFARE_ULTRALIGHT
            p.hasNfcA -> guessFromSak(p.sak) ?: CardKind.NFC_A_ONLY
            else -> CardKind.UNKNOWN
        }

        return Classification(kind, notes)
    }

    /**
     * 只有 NfcA 但没有 IsoDep/MifareClassic tech 时的兜底。
     *
     * 正常情况下 `IsoDep.get(tag)` 在 SAK bit5 置位时一定非空，所以走到这里通常意味着
     * 设备厂商裁剪了 tech 列表，或者 tag extras 不完整。此时信 SAK 比信 tech 列表靠谱。
     */
    private fun guessFromSak(sak: Int?): CardKind? = when {
        sak == null -> null
        sak and SAK_ISO_DEP != 0 -> CardKind.ISO_DEP_CPU
        else -> null
    }

    /**
     * SAK → 型号名。只用于展示，判错不影响任何功能，所以这里刻意只收
     * 共识度高的几个值；剩下的交给 [sakBits] 做位解释。
     */
    fun sakModelName(sak: Int): String? = when (sak and 0xFF) {
        0x00 -> "MIFARE Ultralight / NTAG 系列（NFC Forum Type 2）"
        0x08 -> "MIFARE Classic 1K"
        0x09 -> "MIFARE Mini（320 字节）"
        0x10 -> "MIFARE Plus 2K（SL1）或 Classic 4K 变体"
        0x11 -> "MIFARE Plus 4K（SL1）"
        0x18 -> "MIFARE Classic 4K"
        0x20 -> "ISO-DEP 卡（DESFire / JCOP / 银行卡 / 手机 HCE）"
        0x28 -> "SmartMX + MIFARE Classic 1K（双协议）"
        0x38 -> "SmartMX + MIFARE Classic 4K（双协议）"
        0x88 -> "Infineon MIFARE Classic 1K"
        0x98 -> "Infineon MIFARE Classic 4K"
        0x40, 0x60 -> "NFC-DEP 目标（不是卡，是设备）"
        else -> null
    }

    /** SAK 各置位的含义，用于把 0x28 这种数字翻译成人话。 */
    fun sakBits(sak: Int): List<String> {
        val out = ArrayList<String>(4)
        if (sak and SAK_CLASSIC_1K != 0) out.add("bit3 → MIFARE Classic 1K")
        if (sak and SAK_CLASSIC_4K != 0) out.add("bit4 → MIFARE Classic 4K")
        if (sak and SAK_ISO_DEP != 0) out.add("bit5 → ISO/IEC 14443-4（ISO-DEP）")
        if (sak and SAK_NFC_DEP != 0) out.add("bit6 → ISO/IEC 18092（NFC-DEP）")
        if (sak and SAK_VENDOR != 0) out.add("bit7 → 厂商自定义")
        return out
    }
}
