package org.aclpe.core.settings.touch

import kotlinx.serialization.Serializable

/**
 * 触控虚拟键位方案（启动器侧配置，按 Profile 隔离）。
 * mappings: 动作 → 键（复用 options.txt key_* 键值形态，如 key.mouse.left）。
 */
@Serializable
data class TouchScheme(
    val name: String,
    val version: Int = 1,
    val mappings: Map<String, String> = DEFAULT_MAPPINGS,
) {
    companion object {
        /** 默认方案（最小可用集，后续触控模块充实）。 */
        val DEFAULT_MAPPINGS: Map<String, String> = mapOf(
            "jump" to "key.keyboard.space",
            "sneak" to "key.keyboard.left.shift",
            "attack" to "key.mouse.left",
            "use" to "key.mouse.right",
        )
    }
}
