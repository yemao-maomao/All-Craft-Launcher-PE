package org.aclpe.core.common.result

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [AppResult] 基础语义单元测试。 */
class AppResultTest {

    @Test
    fun `map 转换成功值且失败透传`() {
        val ok: AppResult<Int> = AppResult.Ok(1)
        assertEquals(AppResult.Ok(2), ok.map { it + 1 })

        val err: AppResult<Int> = AppResult.Err(AppError.Parse())
        val mapped: AppResult<Int> = err.map { it + 1 }
        assertTrue(mapped.isErr)
        assertEquals("parse", mapped.errorOrNull()?.code)
    }

    @Test
    fun `getOrNull 与 errorOrNull 语义`() {
        assertEquals(42, AppResult.Ok(42).getOrNull())
        assertNull(AppResult.Err(AppError.Cancelled).getOrNull())
        assertEquals("cancelled", AppResult.Err(AppError.Cancelled).errorOrNull()?.code)
        assertNull(AppResult.Ok(1).errorOrNull())
    }

    @Test
    fun `onOk 与 onErr 链式副作用`() {
        val log: StringBuilder = StringBuilder()
        AppResult.Ok(7).onOk { log.append("ok:$it") }.onErr { log.append("err") }
        AppResult.Err(AppError.Io()).onOk { log.append("ok2") }.onErr { log.append("err2") }
        assertEquals("ok:7err2", log.toString())
    }

    @Test
    fun `resultOf 捕获异常并保留 cause`() {
        val r: AppResult<Int> = resultOf { throw IllegalStateException("boom") }
        assertTrue(r.isErr)
        val error: AppError = r.errorOrNull()!!
        assertTrue(error is AppError.Unknown)
        assertTrue(error.cause is IllegalStateException)
    }

    @Test
    fun `resultOf 正常返回 Ok`() {
        assertEquals(AppResult.Ok("v"), resultOf { "v" })
    }
}
