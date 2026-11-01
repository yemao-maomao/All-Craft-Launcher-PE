package org.aclpe.core.settings.options

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** options.txt 双向解析单测（用户规格：未知 key 保留、非法值丢弃、注释与空行处理）。 */
class OptionsFileTest {

    @Test
    fun `解析与序列化保真_注释空行未知键`() {
        val text: String = """
            # Minecraft options file
            version:512
            renderDistance:12
            some_mod_custom:weird-value

            guiScale:auto
        """.trimIndent() + "\n"
        val parsed: OptionsFile = OptionsFile.parse(text)
        // 未知键保留
        assertEquals("weird-value", parsed.get("some_mod_custom"))
        assertEquals("12", parsed.get("renderDistance"))
        // 序列化回写与原文一致
        assertEquals(text, parsed.serialize())
    }

    @Test
    fun `值可含冒号_按键映射`() {
        val parsed: OptionsFile = OptionsFile.parse("key_key.pickItem:key.mouse.middle\n")
        assertEquals("key.mouse.middle", parsed.get("key_key.pickItem"))
    }

    @Test
    fun `无冒号坏行视为注释保留_键值视图不可见`() {
        val parsed: OptionsFile = OptionsFile.parse("this line has no colon\nfov:70\n")
        assertNull(parsed.get("this line has no colon"))
        assertEquals("70", parsed.get("fov"))
        assertTrue(parsed.serialize().contains("this line has no colon"))
    }

    @Test
    fun `CRLF 行尾被容忍`() {
        val parsed: OptionsFile = OptionsFile.parse("renderDistance:8\r\nlang:zh_cn\r\n")
        assertEquals("8", parsed.get("renderDistance"))
        assertEquals("zh_cn", parsed.get("lang"))
    }

    @Test
    fun `with 覆盖与新增保持顺序`() {
        val parsed: OptionsFile = OptionsFile.parse("a:1\nb:2\n")
        val updated: OptionsFile = parsed.with("b", "3").with("c", "4")
        assertEquals("3", updated.get("b"))
        assertEquals("4", updated.get("c"))
        assertTrue(updated.serialize().indexOf("a:1") < updated.serialize().indexOf("b:3"))
    }

    @Test
    fun `diff 只报告变化键`() {
        val mine: OptionsFile = OptionsFile.parse("a:1\nb:2\nc:3\n")
        val theirs: OptionsFile = OptionsFile.parse("a:1\nb:9\nd:4\n")
        val changes: List<OptionsChange> = mine.diff(theirs)
        assertEquals(3, changes.size)
        assertEquals(OptionsChange("b", "2", "9"), changes.first { it.key == "b" })
        assertEquals(OptionsChange("c", "3", null), changes.first { it.key == "c" })
        assertEquals(OptionsChange("d", null, "4"), changes.first { it.key == "d" })
    }

    @Test
    fun `写入原子性_备份产生_临时文件清理`() {
        val dir: File = File(System.getProperty("java.io.tmpdir"), "options-${System.nanoTime()}").apply { mkdirs() }
        val file: File = File(dir, "options.txt")
        file.writeText("a:1\n")

        val result = OptionsFile.writeAtomic(file, OptionsFile.parse("a:2\nb:3\n"))
        assertTrue(result.isOk)
        assertEquals("a:2\nb:3\n", file.readText())
        assertEquals("a:1\n", File(dir, "options.txt.bak").readText())
        assertFalse(File(dir, "options.txt.tmp").exists())
    }

    @Test
    fun `游戏运行中拒绝覆盖`() {
        val dir: File = File(System.getProperty("java.io.tmpdir"), "options-run-${System.nanoTime()}").apply { mkdirs() }
        val file: File = File(dir, "options.txt")
        file.writeText("a:1\n")
        val result = OptionsFile.writeAtomic(file, OptionsFile.parse("a:2\n"), runningCheck = { true })
        assertTrue(result.isErr)
        assertEquals("game_running", result.errorOrNull()?.code)
        assertEquals("a:1\n", file.readText())   // 原文件未被触碰
    }

    @Test
    fun `restoreBak 回退`() {
        val dir: File = File(System.getProperty("java.io.tmpdir"), "options-bak-${System.nanoTime()}").apply { mkdirs() }
        val file: File = File(dir, "options.txt")
        file.writeText("a:1\n")
        OptionsFile.writeAtomic(file, OptionsFile.parse("a:2\n"))
        assertTrue(OptionsFile.restoreBak(file).isOk)
        assertEquals("a:1\n", file.readText())
        // 无备份时报错
        assertTrue(OptionsFile.restoreBak(File(dir, "nope.txt")).isErr)
    }
}
