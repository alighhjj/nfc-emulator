package io.github.nfcemu.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HexTest {

    @Test
    fun `decode 支持下划线逗号冒号三种分隔符`() {
        val expect = byteArrayOf(0x42, 0x4D, 0x44, 0x4A)
        assertArrayEquals(expect, Hex.decode("42:4D:44:4A"))
        assertArrayEquals(expect, Hex.decode("42,4D,44,4A"))
        assertArrayEquals(expect, Hex.decode("42 4D 44 4A"))
        assertArrayEquals(expect, Hex.decode("42-4D-44-4A"))
        assertArrayEquals(expect, Hex.decode("42_4D_44_4A"))
    }

    @Test
    fun `decode 忽略 0x 前缀与换行缩进`() {
        val text = """
            0x20, 0x02, 0x2D,
                    0x33, 0x04,
        """.trimIndent()
        assertArrayEquals(byteArrayOf(0x20, 0x02, 0x2D, 0x33, 0x04), Hex.decode(text))
    }

    /**
     * 这条是回归测试：曾经的实现用 `BigInteger(hex, 16)` 解码，
     * 前导 0x00 会被吃掉，7 字节 UID 变成 6 字节，之后所有偏移全部错位。
     */
    @Test
    fun `decode 保留前导 0x00`() {
        val out = Hex.decode("00 00 11 22")
        assertEquals(4, out.size)
        assertArrayEquals(byteArrayOf(0x00, 0x00, 0x11, 0x22), out)
    }

    @Test
    fun `decode 单字节单个 00 不被吞掉`() {
        assertEquals(1, Hex.decode("00").size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decode 奇数字符必须抛异常`() {
        Hex.decode("4 2 4 D 4")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decode 非法字符必须抛异常`() {
        Hex.decode("42 ZZ")
    }

    @Test
    fun `decodeOrNull 对垃圾输入返回 null 而不是抛异常`() {
        assertNull(Hex.decodeOrNull("hello world"))
        assertNull(Hex.decodeOrNull("123"))
    }

    @Test
    fun `encode 默认大写无分隔`() {
        assertEquals("424D444A", Hex.encode(byteArrayOf(0x42, 0x4D, 0x44, 0x4A)))
    }

    @Test
    fun `encode 支持小写与逗号分隔`() {
        assertEquals("42, 4d, 44, 4a", Hex.encode(byteArrayOf(0x42, 0x4D, 0x44, 0x4A), upperCase = false, separator = ", "))
    }

    @Test
    fun `encode 按字节数换行并加续行缩进`() {
        val out = Hex.encode(
            bytes = byteArrayOf(0x20, 0x02, 0x2D, 0x02, 0x08, 0x05),
            upperCase = true,
            separator = ", ",
            bytesPerLine = 3,
            linePrefix = "        ",
        )
        assertEquals("20, 02, 2D,\n        02, 08, 05", out)
    }

    @Test
    fun `encode 处理负数字节`() {
        assertEquals("FF 80 7F", Hex.encode(byteArrayOf(0xFF.toByte(), 0x80.toByte(), 0x7F), separator = " "))
    }

    @Test
    fun `compact 只保留十六进制字符`() {
        assertEquals("20022D", Hex.compact("0x20, 0x02, 0x2D"))
    }

    @Test
    fun `encode 与 decode 互为逆运算`() {
        val bytes = ByteArray(64) { (it * 37 and 0xFF).toByte() }
        for (sep in listOf("", ", ", ":", " ")) {
            for (upper in listOf(true, false)) {
                assertArrayEquals(bytes, Hex.decode(Hex.encode(bytes, upper, sep)))
            }
        }
    }
}
