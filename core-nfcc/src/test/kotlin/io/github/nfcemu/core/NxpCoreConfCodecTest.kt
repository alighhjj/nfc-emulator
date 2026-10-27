package io.github.nfcemu.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NxpCoreConfCodecTest {

    /**
     * 结构照抄真实机型的 NXP_CORE_CONF 片段：
     *  - 头部 `20, 02, 2D` 不是合法 TLV（tag=0x20 len=2 后面接不上），这会让严格 TLV 链解析失败；
     *  - 每行一个语义参数，行宽不一致。
     * 这正是必须保留"裸扫描回退"路径的原因。
     */
    private val confBytes: ByteArray = Hex.decode(
        """
        20 02 2D
        02 08 05
        33 04 42 4D 44 4A
        35 06 01 01 01 02 07 46
        """.trimIndent()
    )

    // 字节序列：20 02 2D | 02 08 05 | 33 04 42 4D 44 4A | 35 06 01 01 01 02 07 46
    // 下标：     0  1  2    3  4  5    6  7  8  9 10 11   12 ...

    @Test
    fun `在带非 TLV 前缀的配置里定位到 TAG_ID`() {
        val span = NxpCoreConfCodec.locate(confBytes)
        assertEquals(NxpCoreConfCodec.Span(offset = 6, length = 4), span)
        assertEquals(8, span!!.valueStart)
        assertEquals(12, span.endExclusive)
    }

    @Test
    fun `读出当前 UID`() {
        val uid = NxpCoreConfCodec.read(confBytes)
        assertEquals("42:4D:44:4A", uid!!.toDisplay())
    }

    @Test
    fun `等长替换只改动 value 的四个字节`() {
        val newUid = Uid.of(0x11, 0x22, 0x33, 0x44)
        val out = NxpCoreConfCodec.replaceEqualLength(confBytes, newUid)

        assertEquals(confBytes.size, out.size)
        // 8..11 被改写
        assertArrayEquals(byteArrayOf(0x11, 0x22, 0x33, 0x44), out.copyOfRange(8, 12))
        // 其余字节逐字节一致 —— 这条断言是整个模块的安全底线
        for (i in confBytes.indices) {
            if (i in 8..11) continue
            assertEquals("偏移 $i 被意外修改", confBytes[i], out[i])
        }
        // 原始数组不被就地修改
        assertEquals("42:4D:44:4A", NxpCoreConfCodec.read(confBytes)!!.toDisplay())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `等长替换拒绝不同长度`() {
        NxpCoreConfCodec.replaceEqualLength(confBytes, Uid.of("42 4D 44 4A 00 11 22"))
    }

    @Test(expected = TlvFormatException::class)
    fun `找不到 TAG_ID 时抛异常而不是返回原样`() {
        NxpCoreConfCodec.replaceEqualLength(Hex.decode("20 02 2D 02 08 05"), Uid.of(0x11, 0x22, 0x33, 0x44))
    }

    @Test
    fun `变长替换会同步改写 length 字节`() {
        val uid7 = Uid.of("42 4D 44 4A 00 11 22")
        val out = NxpCoreConfCodec.replaceAllowResize(confBytes, uid7)

        assertEquals(confBytes.size + 3, out.size)
        assertEquals(0x33, out[6].toInt() and 0xFF)
        assertEquals(7, out[7].toInt() and 0xFF)
        assertArrayEquals(uid7.bytes, out.copyOfRange(8, 15))
        // 尾部参数原样保留
        assertArrayEquals(Hex.decode("35 06 01 01 01 02 07 46"), out.copyOfRange(15, out.size))
        assertEquals(uid7, NxpCoreConfCodec.read(out))
    }

    @Test
    fun `变长替换在长度相同时等价于等长替换`() {
        val uid = Uid.of(0x11, 0x22, 0x33, 0x44)
        assertArrayEquals(
            NxpCoreConfCodec.replaceEqualLength(confBytes, uid),
            NxpCoreConfCodec.replaceAllowResize(confBytes, uid),
        )
    }

    @Test
    fun `纯 TLV 配置优先走严格链解析`() {
        val pure = Hex.decode("35 02 01 01 33 04 42 4D 44 4A 28 01 04")
        assertEquals(NxpCoreConfCodec.Span(offset = 4, length = 4), NxpCoreConfCodec.locate(pure))
        assertEquals("42:4D:44:4A", NxpCoreConfCodec.read(pure)!!.toDisplay())
    }

    @Test
    fun `纯 TLV 配置的 smart 替换保持参数顺序`() {
        val pure = Hex.decode("35 02 01 01 33 04 42 4D 44 4A 28 01 04")
        val out = NxpCoreConfCodec.replaceSmart(pure, Uid.of(0xAA.toInt(), 0xBB.toInt(), 0xCC.toInt(), 0xDD.toInt()))
        val params = Tlv.parse(out)
        assertEquals(listOf(0x35, 0x33, 0x28), params.map { it.tag })
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte()), params[1].value)
    }

    @Test
    fun `已设置相同 UID 时 alreadySet 为真`() {
        assertTrue(NxpCoreConfCodec.alreadySet(confBytes, Uid.of(0x42, 0x4D, 0x44, 0x4A)))
        assertFalse(NxpCoreConfCodec.alreadySet(confBytes, Uid.of(0x42, 0x4D, 0x44, 0x4B)))
    }

    @Test
    fun `值里恰好出现 0x33 时不会误定位`() {
        // 0x28 参数的 value 里含 0x33 0x04
        val tricky = Hex.decode("33 04 42 4D 44 4A 28 02 33 04")
        assertEquals(NxpCoreConfCodec.Span(offset = 0, length = 4), NxpCoreConfCodec.locate(tricky))
    }

    @Test
    fun `空配置返回 null`() {
        assertNull(NxpCoreConfCodec.locate(ByteArray(0)))
        assertNull(NxpCoreConfCodec.read(Hex.decode("20 02 2D")))
    }
}
