package org.aclpe.core.settings.launcher

import kotlinx.serialization.Serializable

/** 图形后端（存启动器配置——用户已确认；options.txt 无法承载）。 */
@Serializable
enum class GraphicsBackend { ZINK, ANGLE, VULKAN }

/** 性能开关档位（L0~L5 逐项总闸，默认全关——M7 逐项上线并附基线对照）。 */
@Serializable
data class PerformanceSwitches(
    val l0Baseline: Boolean = false,
    val l1AppCds: Boolean = false,
    val l2JitWarmup: Boolean = false,
    val l3PipelineCache: Boolean = false,
    val l4Adpf: Boolean = false,
    val l5ThreadAffinity: Boolean = false,
)

/**
 * 启动器侧设置（不进 options.txt；按 Profile 隔离，JSON 落盘）。
 * 校验规则见 [LauncherSettingsValidator]。
 */
@Serializable
data class LauncherSettings(
    val memoryTier: String = "MEDIUM",
    val extraJvmArgs: String = "",
    val mirrorId: String = "OFFICIAL",
    val proxy: String = "",
    val jreMajorOverride: Int? = null,
    val graphicsBackend: GraphicsBackend = GraphicsBackend.ZINK,
    val performanceSwitches: PerformanceSwitches = PerformanceSwitches(),
)

object LauncherSettingsValidator {

    const val MAX_EXTRA_JVM_ARGS: Int = 512

    fun validate(settings: LauncherSettings): LauncherSettings? {
        val tierOk: Boolean = settings.memoryTier in setOf("LIGHT", "MEDIUM", "HIGH", "EXTREME")
        val mirrorOk: Boolean = settings.mirrorId in setOf("OFFICIAL", "BMCLAPI")
        val jreOk: Boolean = settings.jreMajorOverride == null || settings.jreMajorOverride in setOf(8, 17, 21, 25)
        val proxyOk: Boolean = settings.proxy.isEmpty() ||
            (settings.proxy.startsWith("http://") || settings.proxy.startsWith("https://"))
        val argsOk: Boolean = settings.extraJvmArgs.length <= MAX_EXTRA_JVM_ARGS
        return if (tierOk && mirrorOk && jreOk && proxyOk && argsOk) settings else null
    }
}
