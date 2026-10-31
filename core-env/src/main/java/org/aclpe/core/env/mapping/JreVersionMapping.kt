package org.aclpe.core.env.mapping

/**
 * MC 版本 ↔ Java major 映射（设计文档 §4）：
 * 1. version json 的 `javaVersion.majorVersion` 优先（1.17+ 必有，权威来源）；
 * 2. 缺省时查内置区间表（快照按版本族映射，如 21w14a → 21）；
 * 3. 仍未知（无法解析数字前缀，如 rd-132211）默认 8（老版本惯例）。
 */
object JreVersionMapping {

    const val DEFAULT_JAVA_MAJOR: Int = 8

    fun javaMajorFor(mcVersion: String, jsonJavaMajor: Int?): Int =
        jsonJavaMajor ?: fallbackFor(mcVersion)

    fun fallbackFor(mcVersion: String): Int {
        val parts: IntArray = parseNumericParts(mcVersion) ?: return DEFAULT_JAVA_MAJOR
        val major: Int = parts[0]
        val minor: Int = parts[1]
        val patch: Int = parts[2]
        return when {
            major < 1 -> DEFAULT_JAVA_MAJOR
            major == 1 && minor < 17 -> 8            // ≤1.16.x
            major == 1 && minor == 17 -> 17          // 1.17.x：官方要求 16，社区统一 17（无 16 移动构建）
            major == 1 && minor in 18..19 -> 17      // 1.18 – 1.19.x
            major == 1 && minor == 20 && patch < 5 -> 17  // 1.20 – 1.20.4
            else -> 21                               // ≥1.20.5（含 1.21.x）
        }
    }

    /** 解析前三个数字段为 [major, minor, patch]（缺失补 0）；非数字版本号（快照）返回 null。 */
    internal fun parseNumericParts(version: String): IntArray? {
        val match: MatchResult = NUMERIC_PREFIX.find(version.trim()) ?: return null
        val major: Int = match.groupValues[1].toInt()
        val minor: Int = match.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 0
        val patch: Int = match.groupValues[3].takeIf { it.isNotEmpty() }?.toInt() ?: 0
        return intArrayOf(major, minor, patch)
    }

    private val NUMERIC_PREFIX: Regex = Regex("""^(\d+)(?:\.(\d+))?(?:\.(\d+))?""")
}
