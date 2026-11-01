package org.aclpe.core.settings.options

/**
 * options.txt 键定义与取值范围（用户规格 Step 5 分组 a/b/d）。
 * 图形后端不在本表——用户已确认其存启动器配置（options.txt 无法承载，MC 会忽略未知键）。
 */
object GameOptionKeys {

    // ---- 画面 ----
    const val RENDER_DISTANCE: String = "renderDistance"
    const val GUI_SCALE: String = "guiScale"
    const val VSYNC: String = "enableVsync"
    const val FULLSCREEN: String = "fullscreen"
    const val MAX_FPS: String = "maxFps"
    const val MIPMAP_LEVELS: String = "mipmapLevels"

    // ---- 声音（0.0~1.0 浮点）----
    val SOUND_KEYS: List<String> = listOf(
        "soundCategory_master",
        "soundCategory_music",
        "soundCategory_record",
        "soundCategory_weather",
        "soundCategory_block",
        "soundCategory_hostile",
        "soundCategory_neutral",
        "soundCategory_player",
        "soundCategory_ambient",
        "soundCategory_voice",
    )

    // ---- 其他 ----
    const val LANG: String = "lang"
    const val FORCE_UNICODE_FONT: String = "forceUnicodeFont"
    const val CHAT_VISIBILITY: String = "chatVisibility"
    const val REDUCED_DEBUG_INFO: String = "reducedDebugInfo"

    /** 按键映射键前缀（key_key.<动作>:key.mouse.left 形态）。 */
    const val KEYBIND_PREFIX: String = "key_key."
}

/** 值类型。 */
enum class OptionType { INT, FLOAT, BOOL, STRING }

/** 校验器（硬性约束：写入前校验取值范围，非法值丢弃/拒绝写入）。 */
object GameOptionsValidator {

    fun typeOf(key: String): OptionType = when {
        key == GameOptionKeys.RENDER_DISTANCE -> OptionType.INT
        key == GameOptionKeys.GUI_SCALE -> OptionType.STRING   // 数值或 "auto"
        key in setOf(GameOptionKeys.VSYNC, GameOptionKeys.FULLSCREEN, GameOptionKeys.FORCE_UNICODE_FONT, GameOptionKeys.REDUCED_DEBUG_INFO) -> OptionType.BOOL
        key == GameOptionKeys.MAX_FPS -> OptionType.INT
        key == GameOptionKeys.MIPMAP_LEVELS -> OptionType.INT
        key == GameOptionKeys.CHAT_VISIBILITY -> OptionType.INT
        key == GameOptionKeys.LANG -> OptionType.STRING
        key in GameOptionKeys.SOUND_KEYS -> OptionType.FLOAT
        key.startsWith(GameOptionKeys.KEYBIND_PREFIX) -> OptionType.STRING
        else -> OptionType.STRING
    }

    /**
     * 校验并规范化：合法返回规范值，非法返回 null（调用方拒绝写入）。
     * 未知键不校验（模组自定义项由模组自理）。
     */
    fun validate(key: String, raw: String): String? = when (typeOf(key)) {
        OptionType.INT -> raw.toIntOrNull()?.takeIf { inIntRange(key, it) }?.toString()
        OptionType.FLOAT -> raw.toFloatOrNull()?.takeIf { it in 0f..1f }?.toString()
        OptionType.BOOL -> when (raw.lowercase()) {
            "true" -> "true"
            "false" -> "false"
            else -> null
        }
        OptionType.STRING -> when {
            key == GameOptionKeys.GUI_SCALE -> validateGuiScale(raw)
            key == GameOptionKeys.LANG -> raw.trim().takeIf { it.isNotEmpty() && it.length <= 64 }?.lowercase()
            key.startsWith(GameOptionKeys.KEYBIND_PREFIX) ->
                raw.trim().takeIf { it.startsWith("key.") }
            else -> raw   // 未知键：原样（模组项）
        }
    }

    private fun validateGuiScale(raw: String): String? {
        val n: Int = raw.toIntOrNull() ?: -1
        if (n in 0..4) return raw
        if (raw.equals("auto", ignoreCase = true)) return "auto"
        return null
    }

    private fun inIntRange(key: String, value: Int): Boolean = when (key) {
        GameOptionKeys.RENDER_DISTANCE -> value in 2..32
        GameOptionKeys.MAX_FPS -> value in 0..260
        GameOptionKeys.MIPMAP_LEVELS -> value in 0..4
        GameOptionKeys.CHAT_VISIBILITY -> value in 0..2
        else -> true
    }
}
