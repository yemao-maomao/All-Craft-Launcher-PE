package org.aclpe.core.common.platform

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [OsSpec] 契约测试：Step 2 rules 引擎与 Step 3 启动参数共用本常量源，
 * 这里锁死取值，防止无意改动导致「宣称值 ≠ 实际值」（硬性约束）。
 */
class OsSpecTest {

    @Test
    fun `os 常量与 MC rules 引擎契约一致`() {
        assertEquals("linux", OsSpec.NAME)
        assertEquals("arm64", OsSpec.ARCH)
        assertEquals("aarch64", OsSpec.ABI)
        assertEquals(':', OsSpec.SEPARATOR)
    }
}
