package io.github.nfcemu.core

import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UidTest {

    @Test
    fun `构造 4 字节 UID`() {
        val uid = Uid.of(0x42, 0x4D, 0x44, 0x4A)
        assertEquals(4, uid.size)
        assertEquals("42:4D:44:4A", uid.toDisplay())
        assertEquals("424D444A", uid.toHex())
    }

    @Test
    fun `构造 7 字节与 10 字节 UID`() {
        assertEquals(7, Uid.of("42 4D 44 4A 00 11 22").size)
        assertEquals(10, Uid.of("42 4D 44 4A 00 11 22 33 44 55").size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `5 字节不是合法 NFCID1 长度`() {
        Uid.of(0x11, 0x22, 0x33, 0x44, 0x55)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `空 UID 不合法`() {
        Uid.of(ByteArray(0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `单字节越界必须抛异常`() {
        Uid.of(0x11, 0x22, 0x33, 0x1FF)
    }

    @Test
    fun `bytes 返回副本，外部修改不影响内部状态`() {
        val uid = Uid.of(0x42, 0x4D, 0x44, 0x4A)
        val b = uid.bytes
        b[0] = 0x00
        assertEquals(0x42, uid.bytes[0].toInt())
    }

    @Test
    fun `相等性与哈希按内容`() {
        val a = Uid.of(0x42, 0x4D, 0x44, 0x4A)
        val b = Uid.of("424D444A")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, Uid.of("424D444B"))
    }

    @Test
    fun `首字节 0x08 触发级联标记告警`() {
        val warnings = Uid.of(0x08, 0x11, 0x22, 0x33).warnings()
        assertTrue(warnings.any { it.contains("0x08") })
    }

    @Test
    fun `全零 UID 触发告警`() {
        assertTrue(Uid.of("00000000").warnings().any { it.contains("0x00") })
    }

    @Test
    fun `所有字节相同触发告警`() {
        assertTrue(Uid.of("11111111").warnings().any { it.contains("相同") })
    }

    @Test
    fun `10 字节 UID 提示兼容性`() {
        assertTrue(Uid.of("424D444A001122334455").warnings().isNotEmpty())
    }

    @Test
    fun `正常 4 字节 UID 无告警`() {
        assertEquals(emptyList<String>(), Uid.of(0x42, 0x4D, 0x44, 0x4A).warnings())
    }

    @Test
    fun `random 生成的 UID 长度合法且首字节避开 0x08 与 0x00`() {
        val rnd = Random(20261027)
        for (size in Uid.SUPPORTED_SIZES) {
            repeat(2000) {
                val uid = Uid.random(size, rnd)
                assertEquals(size, uid.size)
                val first = uid[0].toInt() and 0xFF
                assertTrue("首字节不应为 0x08，实际 $first", first != 0x08)
                assertTrue("首字节不应为 0x00，实际 $first", first != 0x00)
            }
        }
    }

    @Test
    fun `ofOrNull 对非法输入返回 null`() {
        assertEquals(null, Uid.ofOrNull("1122334455"))
        assertEquals(null, Uid.ofOrNull("zz"))
    }

    @Test
    fun `get 下标读取原始字节`() {
        val uid = Uid.of(0x42, 0x4D, 0x44, 0x4A)
        assertArrayEquals(byteArrayOf(0x4D), byteArrayOf(uid[1]))
    }
}
