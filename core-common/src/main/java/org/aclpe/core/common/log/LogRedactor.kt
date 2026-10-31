package org.aclpe.core.common.log

/**
 * 日志脱敏器：任何落盘/落日志面板的文本必须先经 [redact]（防御纵深：
 * core-account 等模块本身严禁打印 token，本类兜底）。
 *
 * 覆盖三类高危形态：
 * 1. JWT：三段式 base64url 串（Microsoft OAuth2 的 access_token / id_token 即此形态）；
 * 2. key=value / key:value：token/secret/password/api_key/authorization 等敏感键；
 * 3. `Authorization: Bearer <token>` 头。
 *
 * 已知局限（可接受）：任意三段 20+ 字符点分串（如个别超长 URL 路径）可能被误脱敏。
 * 取舍：宁可多脱敏，不可漏 token。
 */
class LogRedactor {

    fun redact(input: String): String {
        var out: String = input
        // 顺序：键值（最精确）→ JWT（结构）→ Bearer（形态）。
        // 键值规则放最前，可整体吞掉敏感键后的 token，避免 JWT 标记被键值规则二次改写。
        out = SENSITIVE_KEY_VALUE.replace(out) { match ->
            val key: String = match.groupValues[1]
            val separator: String = match.groupValues[2]
            "$key$separator<redacted>"
        }
        out = JWT.replace(out, "<redacted-jwt>")
        out = BEARER.replace(out, "Bearer <redacted>")
        return out
    }

    companion object {
        /** 三段 base64url：每段 ≥20/20/8 字符，避免短点分串（如版本号 1.20.1）误伤。 */
        private val JWT: Regex = Regex("\\b[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]{8,}\\b")

        /** 敏感键 + 分隔符 + 值；值 ≥8 字符才视为真实 secret，避免 "key=1" 之类误伤。 */
        private val SENSITIVE_KEY_VALUE: Regex = Regex(
            """(?i)\b(token|access_token|refresh_token|client_secret|api_key|apikey|password|authorization)\b(\s*["']?\s*[:=]\s*["']?\s*)([^\s,;&"']{8,})"""
        )

        /** Authorization 头常见形态。 */
        private val BEARER: Regex = Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/=-]{8,}\b""")
    }
}
