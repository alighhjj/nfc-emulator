package io.github.nfcemu.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TlvTest {

    @Test
    fun `解析两条参数`() {
        val bytes = Hex.decode("33 04 42 4D 44 4A 35 02 01 01")
        val params = Tlv.parse(bytes)

        assertEquals(2, params.size)
        assertEquals(0x33, params[0].tag)
        assertEquals(4, params[0].length)
        assertArrayEquals(byteArrayOf(0x42, 0x4D, 0x44, 0x4A), params[0].value)

        assertEquals(0x35, params[1].tag)
        assertArrayEquals(byteArrayOf(0x01, 0x01), params[1].value)
    }

    @Test
    fun `序列化与解析互为逆运算`() {
        val original = Hex.decode("20 02 2D AA 33 04 42 4D 44 4A 35 02 01 01")
        val params = Tlv.parse(original)
        assertArrayEquals(original, Tlv.serialize(params))
    }

    @Test
    fun `零长度参数的 value 为空数组`() {
        val params = Tlv.parse(Hex.decode("35 00"))
        assertEquals(1, params.size)
        assertEquals(0, params[0].length)
        assertEquals(0, params[0].value.size)
    }

    @Test
    fun `终止符 00 00 之后允许 0x00 填充`() {
        val params = Tlv.parse(Hex.decode("33 04 42 4D 44 4A 00 00 00 00"))
        assertEquals(1, params.size)
        assertEquals(0x33, params[0].tag)
    }

    @Test(expected = TlvFormatException::class)
    fun `终止符之后出现非零字节必须抛异常`() {
        Tlv.parse(Hex.decode("00 00 12 34"))
    }

    @Test(expected = TlvFormatException::class)
    fun `length 超出剩余长度必须抛异常`() {
        // tag=0x33 len=0x0A，但只跟了 4 个字节
        Tlv.parse(Hex.decode("33 0A 42 4D 44 4A"))
    }

    @Test(expected = TlvFormatException::class)
    fun `只剩一个字节时无法构成 TLV 头`() {
        Tlv.parse(Hex.decode("33 04 42 4D 44 4A 01"))
    }

    @Test
    fun `parseOrNull 结构不对时返回 null`() {
        assertNull(Tlv.parseOrNull(Hex.decode("20 02 2D 02 08 05 33 04 42 4D 44 4A")))
        assertNotNull(Tlv.parseOrNull(Hex.decode("33 04 42 4D 44 4A")))
    }

    @Test
    fun `TlvParam 拒绝越界 tag`() {
        try {
            TlvParam(0x100, byteArrayOf())
            assertTrue("应当抛 IllegalArgumentException", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("tag"))
        }
    }

    @Test
    fun `TlvParam 对 value 做防御性拷贝`() {
        val src = byteArrayOf(0x42, 0x4D, 0x44, 0x4A)
        val p = TlvParam(0x33, src)
        src[0] = 0x00
        assertEquals(0x42, p.value[0].toInt())

        val out = p.value
        out[0] = 0x11
        assertEquals(0x42, p.value[0].toInt())
    }

    @Test
    fun `TlvParam 相等性按内容比较`() {
        assertEquals(TlvParam(0x33, byteArrayOf(1, 2)), TlvParam(0x33, byteArrayOf(1, 2)))
        assertTrue(TlvParam(0x33, byteArrayOf(1, 2)).hashCode() == TlvParam(0x33, byteArrayOf(1, 2)).hashCode())
    }

    @Test
    fun `describe 输出人类可读格式`() {
        val text = Tlv.describe(Tlv.parse(Hex.decode("33 04 42 4D 44 4A")))
        assertEquals("0x33 len=4 424D444A", text)
    }
}
