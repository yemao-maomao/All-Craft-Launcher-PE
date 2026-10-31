package org.aclpe.core.env.probe

import org.aclpe.core.env.testutil.OUTPUT_JRE17_SERVER
import org.aclpe.core.env.testutil.OUTPUT_JRE21_SERVER
import org.aclpe.core.env.testutil.OUTPUT_JRE8_SERVER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

/** [JreVersionParser] 输出矩阵测试（伪造不同 JRE 输出断言解析结果，设计文档 §8）。 */
class JreVersionParserTest {

    @Test
    fun `解析 OpenJDK 17 三段版本与 64 位 Server`() {
        val info: JreVersionInfo = JreVersionParser.parse(OUTPUT_JRE17_SERVER)!!
        assertEquals(17, info.major)
        assertEquals(0, info.minor)
        assertEquals(5, info.patch)
        assertEquals(JreVersionInfo.Bits.BITS_64, info.bits)
        assertEquals(JreVersionInfo.VmType.SERVER, info.vm)
    }

    @Test
    fun `解析 OpenJDK 21`() {
        val info: JreVersionInfo = JreVersionParser.parse(OUTPUT_JRE21_SERVER)!!
        assertEquals(21, info.major)
        assertEquals(JreVersionInfo.Bits.BITS_64, info.bits)
        assertEquals(JreVersionInfo.VmType.SERVER, info.vm)
    }

    @Test
    fun `Java 8 遗留格式主版本记为 8`() {
        val info: JreVersionInfo = JreVersionParser.parse(OUTPUT_JRE8_SERVER)!!
        assertEquals(8, info.major)
        assertEquals(JreVersionInfo.Bits.BITS_64, info.bits)
        assertEquals(JreVersionInfo.VmType.SERVER, info.vm)
    }

    @Test
    fun `内部版本号形式 17-internal 解析主版本`() {
        val info: JreVersionInfo = JreVersionParser.parse("""openjdk version "17-internal" 2021-05-12""")!!
        assertEquals(17, info.major)
        assertNull(info.minor)
    }

    @Test
    fun `aarch64 标记视为 64 位`() {
        val info: JreVersionInfo = JreVersionParser.parse(
            """openjdk version "21.0.3" 2024-04-16
OpenJDK Runtime Environment (build 21.0.3+9-LTS)
OpenJDK 64-Bit Server VM (aarch64, mixed mode, sharing)
"""
        )!!
        assertEquals(JreVersionInfo.Bits.BITS_64, info.bits)
    }

    @Test
    fun `32 位 VM 被识别`() {
        val info: JreVersionInfo = JreVersionParser.parse(
            """openjdk version "17.0.2" 2022-01-18
OpenJDK 32-Bit Server VM (build 17.0.2+8, mixed mode)
"""
        )!!
        assertEquals(JreVersionInfo.Bits.BITS_32, info.bits)
    }

    @Test
    fun `无位宽标记时返回 UNKNOWN`() {
        val info: JreVersionInfo = JreVersionParser.parse("""openjdk version "17.0.1" 2021-10-19""")!!
        assertEquals(JreVersionInfo.Bits.UNKNOWN, info.bits)
    }

    @Test
    fun `Zero VM 与 Client VM 被识别`() {
        val zero: JreVersionInfo = JreVersionParser.parse(
            """openjdk version "17.0.5" 2022-10-18
OpenJDK 64-Bit Zero VM (build 17.0.5+8, interpreted mode)
"""
        )!!
        assertEquals(JreVersionInfo.VmType.ZERO, zero.vm)

        val client: JreVersionInfo = JreVersionParser.parse(
            """java version "1.8.0_292"
Java HotSpot(TM) 64-Bit Client VM (build 25.292-b10, mixed mode)
"""
        )!!
        assertEquals(JreVersionInfo.VmType.CLIENT, client.vm)
    }

    @Test
    fun `乱码输出返回 null`() {
        assertNull(JreVersionParser.parse("garbage output without version"))
    }

    @Test
    fun `空输出返回 null`() {
        assertNull(JreVersionParser.parse(""))
    }
}
