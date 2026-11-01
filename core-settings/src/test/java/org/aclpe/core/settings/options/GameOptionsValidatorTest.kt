package org.aclpe.core.settings.options

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 取值范围校验矩阵（硬性约束：写入前校验）。 */
class GameOptionsValidatorTest {

    @Test
    fun `renderDistance 2 到 32`() {
        assertEquals("2", GameOptionsValidator.validate(GameOptionKeys.RENDER_DISTANCE, "2"))
        assertEquals("32", GameOptionsValidator.validate(GameOptionKeys.RENDER_DISTANCE, "32"))
        assertNull(GameOptionsValidator.validate(GameOptionKeys.RENDER_DISTANCE, "1"))
        assertNull(GameOptionsValidator.validate(GameOptionKeys.RENDER_DISTANCE, "33"))
        assertNull(GameOptionsValidator.validate(GameOptionKeys.RENDER_DISTANCE, "abc"))
    }

    @Test
    fun `guiScale 合法值`() {
        listOf("0", "1", "2", "3", "4").forEach {
            assertEquals(it, GameOptionsValidator.validate(GameOptionKeys.GUI_SCALE, it))
        }
        assertEquals("auto", GameOptionsValidator.validate(GameOptionKeys.GUI_SCALE, "auto"))
        assertEquals("auto", GameOptionsValidator.validate(GameOptionKeys.GUI_SCALE, "AUTO"))
        assertNull(GameOptionsValidator.validate(GameOptionKeys.GUI_SCALE, "5"))
        assertNull(GameOptionsValidator.validate(GameOptionKeys.GUI_SCALE, "big"))
    }

    @Test
    fun `maxFps 与 mipmap 范围`() {
        assertEquals("120", GameOptionsValidator.validate(GameOptionKeys.MAX_FPS, "120"))
        assertEquals("0", GameOptionsValidator.validate(GameOptionKeys.MAX_FPS, "0"))
        assertNull(GameOptionsValidator.validate(GameOptionKeys.MAX_FPS, "261"))
        assertNull(GameOptionsValidator.validate(GameOptionKeys.MIPMAP_LEVELS, "5"))
        assertEquals("4", GameOptionsValidator.validate(GameOptionKeys.MIPMAP_LEVELS, "4"))
    }

    @Test
    fun `布尔规范化`() {
        assertEquals("true", GameOptionsValidator.validate(GameOptionKeys.VSYNC, "true"))
        assertEquals("false", GameOptionsValidator.validate(GameOptionKeys.FULLSCREEN, "FALSE"))
        assertNull(GameOptionsValidator.validate(GameOptionKeys.VSYNC, "yes"))
    }

    @Test
    fun `声音 0 到 1 浮点`() {
        assertEquals("0.5", GameOptionsValidator.validate("soundCategory_master", "0.5"))
        assertEquals("1.0", GameOptionsValidator.validate("soundCategory_music", "1.0"))
        assertNull(GameOptionsValidator.validate("soundCategory_block", "1.5"))
        assertNull(GameOptionsValidator.validate("soundCategory_hostile", "loud"))
    }

    @Test
    fun `按键映射需 key 前缀`() {
        assertEquals("key.mouse.left", GameOptionsValidator.validate("key_key.attack", "key.mouse.left"))
        assertNull(GameOptionsValidator.validate("key_key.attack", "mouse.left"))
    }

    @Test
    fun `语言与聊天可见性`() {
        assertEquals("zh_cn", GameOptionsValidator.validate(GameOptionKeys.LANG, "zh_cn"))
        assertNull(GameOptionsValidator.validate(GameOptionKeys.LANG, ""))
        assertEquals("2", GameOptionsValidator.validate(GameOptionKeys.CHAT_VISIBILITY, "2"))
        assertNull(GameOptionsValidator.validate(GameOptionKeys.CHAT_VISIBILITY, "3"))
    }

    @Test
    fun `未知键原样保留_模组项不校验`() {
        assertEquals("anything-here", GameOptionsValidator.validate("mod_weird_key", "anything-here"))
    }
}
