package org.aclpe.core.env.mapping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [JreVersionMapping] 区间表测试（设计文档 §4）。 */
class JreVersionMappingTest {

    @Test
    fun `json javaVersion 优先于区间表`() {
        assertEquals(21, JreVersionMapping.javaMajorFor("1.21.1", jsonJavaMajor = 21))
        assertEquals(21, JreVersionMapping.javaMajorFor("1.20.1", jsonJavaMajor = 21)) // 以 json 为准
        assertEquals(8, JreVersionMapping.javaMajorFor("1.12.2", jsonJavaMajor = 8))
    }

    @Test
    fun `区间表_旧版本走 Java 8`() {
        assertEquals(8, JreVersionMapping.fallbackFor("1.8.9"))
        assertEquals(8, JreVersionMapping.fallbackFor("1.12.2"))
        assertEquals(8, JreVersionMapping.fallbackFor("1.16.5"))
    }

    @Test
    fun `区间表_1_17 系列走 17`() {
        assertEquals(17, JreVersionMapping.fallbackFor("1.17"))
        assertEquals(17, JreVersionMapping.fallbackFor("1.17.1"))
    }

    @Test
    fun `区间表_1_18 到 1_20_4 走 17`() {
        assertEquals(17, JreVersionMapping.fallbackFor("1.18"))
        assertEquals(17, JreVersionMapping.fallbackFor("1.19.4"))
        assertEquals(17, JreVersionMapping.fallbackFor("1.20.1"))
        assertEquals(17, JreVersionMapping.fallbackFor("1.20.4"))
    }

    @Test
    fun `区间表_1_20_5 及以上走 21`() {
        assertEquals(21, JreVersionMapping.fallbackFor("1.20.5"))
        assertEquals(21, JreVersionMapping.fallbackFor("1.21"))
        assertEquals(21, JreVersionMapping.fallbackFor("1.21.4"))
    }

    @Test
    fun `快照按版本族映射_真正未知默认 8`() {
        assertEquals(21, JreVersionMapping.fallbackFor("21w14a"))    // 1.21 快照族 → 21
        assertEquals(17, JreVersionMapping.fallbackFor("1.17-rc1"))  // 1.17 预发布 → 17
        assertEquals(8, JreVersionMapping.fallbackFor("rd-132211"))  // 远古快照 → 默认 8
    }

    @Test
    fun `数字段解析`() {
        assertEquals(intArrayOf(1, 20, 1).toList(), JreVersionMapping.parseNumericParts("1.20.1")!!.toList())
        assertEquals(intArrayOf(1, 21, 0).toList(), JreVersionMapping.parseNumericParts("1.21")!!.toList())
        assertEquals(intArrayOf(21, 0, 0).toList(), JreVersionMapping.parseNumericParts("21w14a")!!.toList())
        assertNull(JreVersionMapping.parseNumericParts("rd-132211"))
    }
}
