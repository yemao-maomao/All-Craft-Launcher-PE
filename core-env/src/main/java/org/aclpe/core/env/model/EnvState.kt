package org.aclpe.core.env.model

/**
 * 环境总体状态（用户规格要求的名词，设计文档 §2.4 合成规则）：
 * READY > CORRUPTED > PARTIAL > MISSING 优先级合成，明细见 [EnvIssue]。
 */
enum class EnvState {
    MISSING, PARTIAL, READY, CORRUPTED,
}

/** 探测发现的具体问题，供 UI 逐条展示与日志记录。 */
data class EnvIssue(val code: String, val message: String)

/** 通用组件（natives / assets 索引）的探测结论。 */
sealed interface ComponentProbeResult {
    data object Ok : ComponentProbeResult
    data class Missing(val reason: String) : ComponentProbeResult
    data class Partial(val missingFiles: List<String>) : ComponentProbeResult
    data class Corrupted(val issues: List<EnvIssue>) : ComponentProbeResult
}
