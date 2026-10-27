package io.github.nfcemu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NfcConfFileTest {

    /**
     * 刻意照抄真实 `libnfc-nxp.conf` 的排版：行首缩进、逗号+空格分隔、一行一个语义参数、
     * 区块外有 `#` 注释、还有一个同样含 `33, 04` 的 `NXP_RF_CONF` 作为干扰项。
     */
    private val confText = """
        |###############################################################################
        |# NXP NFC 配置（片段，仅用于单元测试）
        |###############################################################################
        |NXP_CORE_CONF={ 20, 02, 2D,
        |                02, 08, 05,
        |                33, 04, 42, 4D, 44, 4A,
        |                35, 06, 01, 01, 01, 02, 07, 46 }
        |
        |NXP_RF_CONF={ 20, 02, 39,
        |              33, 04, AA, BB, CC, DD }
        |
        |NXP_CORE_CONF_EXTN={ 20, 02, 1A }
        |""".trimMargin()

    // ------------------------------------------------------------------ 定位

    @Test
    fun `findBlock 不会把 NXP_CORE_CONF 匹配到 NXP_CORE_CONF_EXTN`() {
        val block = NfcConfFile.findBlock(confText, NfcConfFile.KEY_NXP_CORE_CONF)
        assertNotNull(block)
        // 区块内容应止于第一处 },即第 7 行末尾
        val inner = confText.substring(block!!.valueStart, block.valueEnd)
        assertTrue(inner.trimStart().startsWith("20, 02, 2D,"))
        assertTrue(inner.trimEnd().endsWith("07, 46"))
        assertFalse(inner.contains("1A"))
    }

    @Test
    fun `findBlock 对不存在的键返回 null`() {
        assertNull(NfcConfFile.findBlock(confText, "NOT_A_REAL_KEY"))
    }

    @Test
    fun `listKeys 枚举出全部大写下划线键`() {
        assertEquals(
            listOf(NfcConfFile.KEY_NXP_CORE_CONF, NfcConfFile.KEY_NXP_CORE_CONF_EXTN, "NXP_RF_CONF").sorted(),
            NfcConfFile.listKeys(confText).sorted(),
        )
    }

    @Test
    fun `readUid 读出核心配置与射频配置各自的 UID`() {
        assertEquals("42:4D:44:4A", NfcConfFile.readUid(confText, NfcConfFile.KEY_NXP_CORE_CONF)!!.toDisplay())
        assertEquals("AA:BB:CC:DD", NfcConfFile.readUid(confText, "NXP_RF_CONF")!!.toDisplay())
    }

    @Test
    fun `readBytes 对不含 TAG_ID 的区块返回原始字节`() {
        val bytes = NfcConfFile.readBytes(confText, NfcConfFile.KEY_NXP_CORE_CONF_EXTN)!!
        assertEquals(3, bytes.size)
        assertEquals(0x20, bytes[0].toInt())
        assertNull(NxpCoreConfCodec.read(bytes))
    }

    // ------------------------------------------------------------------ 改写

    @Test
    fun `等长改写只动四个字符位置，其余文本逐字符不变`() {
        val newUid = Uid.of(0x11, 0x22, 0x33, 0x44)
        val result = NfcConfFile.replaceTagId(confText, NfcConfFile.KEY_NXP_CORE_CONF, newUid)

        assertTrue(result.precise)
        assertFalse(result.lengthChanged)
        assertEquals("42:4D:44:4A", result.oldUid!!.toDisplay())
        assertEquals(confText.length, result.text.length)

        // 新值写进去了
        assertTrue(result.text.contains("33, 04, 11, 22, 33, 44,"))
        // 旧值没了
        assertFalse(result.text.contains("42, 4D, 44, 4A"))
        // 干扰区块完好
        assertEquals("AA:BB:CC:DD", NfcConfFile.readUid(result.text, "NXP_RF_CONF")!!.toDisplay())
        // 除 UID 外的每一行都原样保留
        val oldLines = confText.lines()
        val newLines = result.text.lines()
        assertEquals(oldLines.size, newLines.size)
        for (i in oldLines.indices) {
            if (oldLines[i].trimEnd().endsWith("4A,")) continue
            assertEquals("第 $i 行被意外修改", oldLines[i], newLines[i])
        }
    }

    @Test
    fun `4 字节改 7 字节会同步改写 length 字段`() {
        val uid7 = Uid.of("42 4D 44 4A 00 11 22")
        val result = NfcConfFile.replaceTagId(confText, NfcConfFile.KEY_NXP_CORE_CONF, uid7)

        assertTrue(result.precise)
        assertTrue(result.lengthChanged)
        assertTrue(result.text.contains("33, 07, 42, 4D, 44, 4A, 00, 11, 22,"))

        // 改完之后必须还能被解析回同一个 UID —— 否则 conf 就自相矛盾了
        assertEquals(uid7, NfcConfFile.readUid(result.text, NfcConfFile.KEY_NXP_CORE_CONF))
        // 只增加了 3 个字节
        assertEquals(confText.length + 3 * 4, result.text.length)
    }

    @Test
    fun `7 字节改回 4 字节`() {
        val uid7 = Uid.of("42 4D 44 4A 00 11 22")
        val step1 = NfcConfFile.replaceTagId(confText, NfcConfFile.KEY_NXP_CORE_CONF, uid7)
        val step2 = NfcConfFile.replaceTagId(step1.text, NfcConfFile.KEY_NXP_CORE_CONF, Uid.of(0x42, 0x4D, 0x44, 0x4A))

        assertTrue(step2.lengthChanged)
        assertEquals(confText, step2.text)
    }

    @Test
    fun `十字节三长 UID 也能写入`() {
        val uid10 = Uid.of("42 4D 44 4A 00 11 22 33 44 55")
        val result = NfcConfFile.replaceTagId(confText, NfcConfFile.KEY_NXP_CORE_CONF, uid10)
        // 不动下标定位：直接用内容找那一行，避免 trimMargin / 前导换行带来的行号偏移
        val uidLine = result.text.lines().first { it.contains("33, 0A,") }
        assertEquals("33, 0A, 42, 4D, 44, 4A, 00, 11, 22, 33, 44, 55,", uidLine.trim())
        // 缩进必须保持原样（16 个空格）
        assertEquals(16, uidLine.indexOf("33, 0A,"))
        assertEquals(uid10, NfcConfFile.readUid(result.text, NfcConfFile.KEY_NXP_CORE_CONF))
    }

    @Test
    fun `冒号分隔与小写风格的区块被正确识别`() {
        val text = """
            LISTEN_CONF={ 20:02:2d:02:08:05:33:04:42:4d:44:4a }
        """.trimIndent()
        val result = NfcConfFile.replaceTagId(text, "LISTEN_CONF", Uid.of(0x11, 0x22, 0x33, 0x44))
        assertEquals("LISTEN_CONF={ 20:02:2d:02:08:05:33:04:11:22:33:44 }", result.text)
    }

    @Test
    fun `纯空格分隔且无逗号的风格被正确识别`() {
        val text = "CONF={ 20 02 2D 02 08 05 33 04 42 4D 44 4A }"
        val result = NfcConfFile.replaceTagId(text, "CONF", Uid.of(0x11, 0x22, 0x33, 0x44))
        assertEquals("CONF={ 20 02 2D 02 08 05 33 04 11 22 33 44 }", result.text)
    }

    @Test(expected = TlvFormatException::class)
    fun `区块不存在时抛异常`() {
        NfcConfFile.replaceTagId(confText, "NO_SUCH_KEY", Uid.of(0x11, 0x22, 0x33, 0x44))
    }

    @Test(expected = TlvFormatException::class)
    fun `区块里没有 TAG_ID 时抛异常`() {
        NfcConfFile.replaceTagId(confText, NfcConfFile.KEY_NXP_CORE_CONF_EXTN, Uid.of(0x11, 0x22, 0x33, 0x44))
    }

    // ------------------------------------------------------------------ 字符索引

    @Test
    fun `HexTextIndex 把每个字节映射回字符区间`() {
        val text = "33, 04, 42"
        val idx = HexTextIndex.index(text)!!
        assertEquals(6, idx.size)
        assertEquals(0, idx[0]); assertEquals(2, idx[1])   // "33"
        assertEquals(4, idx[2]); assertEquals(6, idx[3])   // "04"
        assertEquals(8, idx[4]); assertEquals(10, idx[5])  // "42"
        assertEquals("33", text.substring(idx[0], idx[1]))
        assertEquals("42", text.substring(idx[4], idx[5]))
    }

    @Test
    fun `HexTextIndex 忽略 0x 前缀`() {
        val text = "0x33, 0x04"
        val idx = HexTextIndex.index(text)!!
        assertEquals(4, idx.size)
        assertEquals("33", text.substring(idx[0], idx[1]))
        assertEquals("04", text.substring(idx[2], idx[3]))
    }

    @Test
    fun `HexTextIndex 对奇数位字符返回 null`() {
        assertNull(HexTextIndex.index("3 3 4"))
    }

    // ------------------------------------------------------------------ 平台

    @Test
    fun `平台推断优先识别 NXP`() {
        assertEquals(NfccPlatform.NXP, NfccPlatform.guessFromConfKeys(NfcConfFile.listKeys(confText)))
    }
}
