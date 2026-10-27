package io.github.nfcemu.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TagSnapshot 是 UI 的唯一数据来源，它的一行错位会直接显示成错的卡信息。
 * 这里锁住两件事：null 字段不出现（不能显示 "MIFARE 型号: null"），
 * 以及导出文本里必须带上结论。
 */
class TagSnapshotTest {

    private fun sample() = TagSnapshot(
        uidHex = "04 A2 33 1B 5C 6D 80",
        uidBytes = 7,
        techs = listOf("IsoDep", "NfcA"),
        kind = CardKind.ISO_DEP_CPU,
        seenAt = 1_700_000_000_000L,
        atqa = "03 44",
    )

    @Test
    fun `只输出已采集到的字段`() {
        val rows = sample().rows()
        val keys = rows.map { it.first }
        assertTrue(keys.contains("UID"))
        assertTrue(keys.contains("技术栈"))
        assertTrue(keys.contains("ATQA"))
        assertFalse("没取到 SAK 就不该出现这一行", keys.contains("SAK"))
        assertFalse(keys.contains("MIFARE 型号"))
    }

    @Test
    fun `UID 行带上字节数`() {
        val uidRow = sample().rows().first { it.first == "UID" }
        assertEquals("04 A2 33 1B 5C 6D 80  (7 字节)", uidRow.second)
    }

    @Test
    fun `导出文本包含类型与结论`() {
        val text = sample().toPlainText()
        assertTrue(text.contains(CardKind.ISO_DEP_CPU.label))
        assertTrue(text.contains("结论:"))
        assertTrue(text.contains("04 A2 33 1B 5C 6D 80"))
    }

    @Test
    fun `备注逐条导出`() {
        val snap = sample().copy(notes = listOf("甲", "乙"))
        val text = snap.toPlainText()
        assertTrue(text.contains("备注: 甲"))
        assertTrue(text.contains("备注: 乙"))
    }

    @Test
    fun `扩展长度 APDU 显示为人话`() {
        val rows = sample().copy(isoDepExtended = true).rows()
        assertEquals("支持", rows.first { it.first == "扩展长度 APDU" }.second)
        val rows2 = sample().copy(isoDepExtended = false).rows()
        assertEquals("不支持", rows2.first { it.first == "扩展长度 APDU" }.second)
    }
}
