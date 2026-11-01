package org.aclpe.core.download.rules

import org.aclpe.core.common.platform.OsSpec
import org.aclpe.core.download.json.OsRule
import org.aclpe.core.download.json.Rule

/**
 * rules 求值上下文。唯一合法构造途径是 [fromOsSpec] —— 硬性约束：
 * OsName/Arch 等与 Step 3 启动参数共用 core-common OsSpec 单一常量源，禁止自行硬编码。
 */
data class PlatformContext(
    val osName: String,
    val osVersion: String,
    val osArch: String,
    val features: Set<String>,
) {
    companion object {
        fun fromOsSpec(): PlatformContext = PlatformContext(
            osName = OsSpec.NAME,
            osVersion = OsSpec.OS_VERSION,
            osArch = OsSpec.ARCH,
            features = OsSpec.FEATURES,
        )
    }

    /**
     * 架构专属原生库分类器候选（如 netty 的 linux-aarch_64）。
     * 与 rules 不同：此类条目的规则通常只写 os 不写 arch（见 1.20.1 netty 条目），
     * 平台选择靠分类器名匹配。
     */
    fun platformClassifierCandidates(): List<String> = when (osArch) {
        "arm64" -> listOf("$osName-arm64", "$osName-aarch_64")
        "x86_64" -> listOf("$osName-x86_64", "$osName-amd64")
        else -> listOf("$osName-$osArch")
    }
}

/**
 * rules 求值引擎（用户规格 ★）：
 * - **无规则 = 不设防**：空 rules 列表一律视为允许（官方 launcher 语义；绝大多数库无 rules）；
 * - allow/disallow **有序匹配**：从第一条开始，第一个条件命中的规则决定结果；
 * - **默认 disallow**：存在规则但无任何命中 → 不允许（用户规格；与 Mojang 官方 launcher 的
 *   「无命中默认允许」不同——真实 version json 的规则列表均以无条件兜底规则结尾，两者对
 *   真实数据的判定一致；golden 测试用 6 个真实 json 交叉验证）；
 * - os 条件：name 精确匹配、arch 精确匹配、version 整串正则（matches）；
 * - features 条件：缺失视为 false（{x:true} 需特性存在；{x:false} 需不存在）。
 */
object RulesEngine {

    fun evaluate(rules: List<Rule>, ctx: PlatformContext): Boolean {
        if (rules.isEmpty()) return true
        for (rule in rules) {
            if (matches(rule, ctx)) return rule.action.equals("allow", ignoreCase = true)
        }
        return false
    }

    fun matches(rule: Rule, ctx: PlatformContext): Boolean {
        val os: OsRule? = rule.os
        if (os != null) {
            if (os.name != null && os.name != ctx.osName) return false
            if (os.arch != null && os.arch != ctx.osArch) return false
            if (os.version != null) {
                val regex: Regex = runCatching { Regex(os.version) }.getOrNull() ?: return false
                if (!regex.matches(ctx.osVersion)) return false
            }
        }
        val features: Map<String, Boolean>? = rule.features
        if (features != null) {
            for ((key, value) in features) {
                if ((key in ctx.features) != value) return false
            }
        }
        return true
    }
}
