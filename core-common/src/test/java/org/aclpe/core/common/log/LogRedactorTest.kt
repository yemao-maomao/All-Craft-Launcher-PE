package org.aclpe.core.common.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [LogRedactor] 单元测试：敏感形态必须脱敏，正常文本必须原样保留。 */
class LogRedactorTest {

    private val redactor: LogRedactor = LogRedactor()

    private val jwt: String = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9" +
        ".eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIn0" +
        ".SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"

    @Test
    fun `独立 JWT 三段式 token 被整体脱敏`() {
        // 上下文不含敏感键：JWT 结构规则生效
        val out: String = redactor.redact("login failed: $jwt")
        assertFalse(out.contains("eyJ"))
        assertTrue(out.contains("<redacted-jwt>"))
    }

    @Test
    fun `敏感键后携带 JWT 时由键值规则整体脱敏`() {
        // 键值规则更精确且先执行：整个 token 被吞掉
        val out: String = redactor.redact("auth token: $jwt")
        assertFalse(out.contains("eyJ"))
        assertTrue(out.contains("<redacted>"))
    }

    @Test
    fun `key=value 形态的敏感键值被脱敏`() {
        assertEquals("access_token=<redacted>", redactor.redact("access_token=abcdef1234567890"))
    }

    @Test
    fun `JSON 形态的密码字段被脱敏`() {
        assertEquals(
            "{\"password\":\"<redacted>\"}",
            redactor.redact("{\"password\":\"hunter2hunter2\"}")
        )
    }

    @Test
    fun `Bearer 头被脱敏`() {
        val out: String = redactor.redact("Authorization: Bearer aB3dE5fG7hI9jK0lMnOpQrStUvWxYz123")
        assertEquals("Authorization: Bearer <redacted>", out)
    }

    @Test
    fun `正常文本原样保留_版本号`() {
        assertEquals("Minecraft 1.20.1 正在启动", redactor.redact("Minecraft 1.20.1 正在启动"))
    }

    @Test
    fun `正常文本原样保留_短值键值对`() {
        // 值 < 8 字符不视为 secret，避免把游戏配置类日志误脱敏
        assertEquals("渲染距离=12", redactor.redact("渲染距离=12"))
        assertEquals("opengl=zink", redactor.redact("opengl=zink"))
    }

    @Test
    fun `正常文本原样保留_普通 URL`() {
        val url: String = "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json"
        assertEquals(url, redactor.redact(url))
    }
}
