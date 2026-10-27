package io.github.nfcemu.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 判卡逻辑的单测。
 *
 * 这是整个 M1 里唯一能在 CI 上验证的部分 —— 真机读卡要靠用户手动贴卡。
 * 所以这里把"什么组合该判成什么"穷举到位：后面一旦改判卡顺序，
 * 这些用例会立刻告诉你把哪条路径改坏了。
 */
class TagClassifierTest {

    @Test
    fun `没有任何技术栈时判为未识别`() {
        val r = TagClassifier.classify(TechProfile())
        assertEquals(CardKind.UNKNOWN, r.kind)
    }

    @Test
    fun `Ultralight tech 判为 Ultralight`() {
        val r = TagClassifier.classify(
            TechProfile(hasNfcA = true, hasMifareUltralight = true, sak = 0x00)
        )
        assertEquals(CardKind.MIFARE_ULTRALIGHT, r.kind)
    }

    @Test
    fun `Classic 1K 的 SAK 判为 Classic`() {
        val r = TagClassifier.classify(
            TechProfile(hasNfcA = true, hasMifareClassic = true, sak = 0x08)
        )
        assertEquals(CardKind.MIFARE_CLASSIC, r.kind)
    }

    @Test
    fun `Classic 4K 的 SAK 判为 Classic`() {
        val r = TagClassifier.classify(
            TechProfile(hasNfcA = true, hasMifareClassic = true, sak = 0x18)
        )
        assertEquals(CardKind.MIFARE_CLASSIC, r.kind)
    }

    @Test
    fun `纯 IsoDep 判为 CPU 卡`() {
        val r = TagClassifier.classify(TechProfile(hasNfcA = true, hasIsoDep = true, sak = 0x20))
        assertEquals(CardKind.ISO_DEP_CPU, r.kind)
    }

    /**
     * 最重要的一条：SAK 0x28 的 SmartMX 门禁卡会同时挂 IsoDep + MifareClassic。
     * 如果判卡顺序写反，就会把唯一有戏的一类卡判成"没戏"。
     */
    @Test
    fun `双协议卡优先判为 ISO-DEP 并给出提示`() {
        val r = TagClassifier.classify(
            TechProfile(
                hasNfcA = true,
                hasIsoDep = true,
                hasMifareClassic = true,
                sak = 0x28,
            )
        )
        assertEquals(CardKind.ISO_DEP_CPU, r.kind)
        assertTrue(
            "双协议卡必须被明确指出：" + r.notes,
            r.notes.any { it.contains("双协议") }
        )
    }

    @Test
    fun `ISO-DEP 优先于 NFC-B`() {
        val r = TagClassifier.classify(
            TechProfile(hasNfcB = true, hasIsoDep = true)
        )
        assertEquals(CardKind.ISO_DEP_CPU, r.kind)
    }

    @Test
    fun `只有 NFC-B 时判为 NFC-B`() {
        val r = TagClassifier.classify(TechProfile(hasNfcB = true))
        assertEquals(CardKind.NFC_B, r.kind)
    }

    @Test
    fun `FeliCa 优先级最高 即使同时挂了 IsoDep`() {
        val r = TagClassifier.classify(
            TechProfile(hasNfcF = true, hasIsoDep = true, hasNfcA = true, sak = 0x20)
        )
        assertEquals(CardKind.FELICA, r.kind)
    }

    @Test
    fun `ISO15693 判为 ISO15693`() {
        val r = TagClassifier.classify(TechProfile(hasNfcV = true))
        assertEquals(CardKind.ISO15693, r.kind)
    }

    /**
     * 有些 ROM 会裁掉 IsoDep tech，但 SAK bit5 仍然置位。
     * 此时信 SAK 比信 tech 列表靠谱。
     */
    @Test
    fun `只有 NfcA 但 SAK 置了 ISO-DEP 位时 用 SAK 兜底`() {
        val r = TagClassifier.classify(TechProfile(hasNfcA = true, sak = 0x20))
        assertEquals(CardKind.ISO_DEP_CPU, r.kind)
    }

    @Test
    fun `只有 NfcA 且 SAK 无法判断时判为未知 NFC-A`() {
        val r = TagClassifier.classify(TechProfile(hasNfcA = true, sak = 0x00))
        assertEquals(CardKind.NFC_A_ONLY, r.kind)
    }

    @Test
    fun `只有 NfcA 且没有 SAK 时判为未知 NFC-A`() {
        val r = TagClassifier.classify(TechProfile(hasNfcA = true, sak = null))
        assertEquals(CardKind.NFC_A_ONLY, r.kind)
    }

    @Test
    fun `SAK 置 NFC-DEP 位时给出提示`() {
        val r = TagClassifier.classify(TechProfile(hasNfcA = true, sak = 0x40))
        assertEquals(CardKind.NFC_A_ONLY, r.kind)
        assertTrue("应提示 NFC-DEP：" + r.notes, r.notes.any { it.contains("NFC-DEP") })
    }

    @Test
    fun `SAK 置厂商位时提示型号推断不可靠`() {
        val r = TagClassifier.classify(
            TechProfile(hasNfcA = true, hasMifareClassic = true, sak = 0x88)
        )
        assertEquals(CardKind.MIFARE_CLASSIC, r.kind)
        assertTrue("应提示厂商位：" + r.notes, r.notes.any { it.contains("厂商位") })
    }

    @Test
    fun `带 NDEF 时给出提示`() {
        val r = TagClassifier.classify(
            TechProfile(hasNfcA = true, hasNdef = true, sak = 0x00)
        )
        assertTrue("应提示 NDEF：" + r.notes, r.notes.any { it.contains("NDEF") })
    }

    @Test
    fun `常见 SAK 值能翻译成型号`() {
        assertEquals("MIFARE Classic 1K", TagClassifier.sakModelName(0x08))
        assertEquals("MIFARE Classic 4K", TagClassifier.sakModelName(0x18))
        assertTrue(TagClassifier.sakModelName(0x00)!!.contains("Ultralight"))
        assertTrue(TagClassifier.sakModelName(0x20)!!.contains("ISO-DEP"))
    }

    @Test
    fun `未知 SAK 值返回 null 而不是瞎猜`() {
        assertNull(TagClassifier.sakModelName(0x0A))
    }

    @Test
    fun `SAK 位解释按低位到高位输出`() {
        val bits = TagClassifier.sakBits(0x28)
        assertEquals(2, bits.size)
        assertTrue(bits[0], bits[0].startsWith("bit3"))
        assertTrue(bits[1], bits[1].startsWith("bit5"))
    }

    @Test
    fun `SAK 为 0 时没有任何置位`() {
        assertTrue(TagClassifier.sakBits(0x00).isEmpty())
    }

    @Test
    fun `每种卡片类型都必须给出模拟可行性结论`() {
        CardKind.values().forEach { kind ->
            assertTrue("${kind.name} 的 emulation 结论不能为空", kind.emulation.isNotBlank())
            assertTrue("${kind.name} 缺少中文标签", kind.label.isNotBlank())
        }
    }
}
