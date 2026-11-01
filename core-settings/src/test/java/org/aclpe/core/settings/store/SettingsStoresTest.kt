package org.aclpe.core.settings.store

import kotlinx.coroutines.test.runTest
import org.aclpe.core.settings.launcher.GraphicsBackend
import org.aclpe.core.settings.launcher.LauncherSettings
import org.aclpe.core.settings.launcher.LauncherSettingsValidator
import org.aclpe.core.settings.launcher.PerformanceSwitches
import org.aclpe.core.settings.options.OptionsFile
import org.aclpe.core.settings.touch.TouchScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 设置存储（Profile 隔离 / 模板 / 触控方案 / 导入导出）测试。 */
class SettingsStoresTest {

    private fun root(): File =
        File(System.getProperty("java.io.tmpdir"), "settings-${System.nanoTime()}").apply { mkdirs() }

    @Test
    fun `Profile 隔离 options 与 launcher`() = runTest {
        val stores: SettingsStores = SettingsStores(root())
        stores.writeOptions("p1", OptionsFile.parse("renderDistance:8\n"))
        stores.writeLauncher("p1", LauncherSettings(memoryTier = "HIGH", graphicsBackend = GraphicsBackend.ANGLE))

        assertEquals("8", stores.readOptions("p1").get("renderDistance"))
        assertNull(stores.readOptions("p2").get("renderDistance"))   // p2 无文件
        assertEquals("HIGH", stores.readLauncher("p1").memoryTier)
        assertEquals(GraphicsBackend.ZINK, stores.readLauncher("p2").graphicsBackend)   // 默认
    }

    @Test
    fun `启动器设置校验矩阵`() {
        assertNotNull(LauncherSettingsValidator.validate(LauncherSettings()))
        assertNull(LauncherSettingsValidator.validate(LauncherSettings(memoryTier = "HUGE")))
        assertNull(LauncherSettingsValidator.validate(LauncherSettings(mirrorId = "GITEE")))
        assertNull(LauncherSettingsValidator.validate(LauncherSettings(jreMajorOverride = 11)))
        assertNull(LauncherSettingsValidator.validate(LauncherSettings(proxy = "ftp://x")))
        assertNotNull(LauncherSettingsValidator.validate(LauncherSettings(proxy = "https://127.0.0.1:7890")))
        assertNotNull(LauncherSettingsValidator.validate(LauncherSettings(jreMajorOverride = 21)))
    }

    @Test
    fun `触控方案保存加载删除`() = runTest {
        val stores: SettingsStores = SettingsStores(root())
        val scheme: TouchScheme = TouchScheme(name = "双指", mappings = TouchScheme.DEFAULT_MAPPINGS + ("sprint" to "key.keyboard.r"))
        assertTrue(stores.saveTouchScheme("p1", scheme).isOk)
        assertEquals(listOf("双指"), stores.listTouchSchemes("p1"))
        val loaded: TouchScheme = stores.loadTouchScheme("p1", "双指")!!
        assertEquals("key.keyboard.r", loaded.mappings["sprint"])
        assertTrue(stores.deleteTouchScheme("p1", "双指").isOk)
        assertTrue(stores.listTouchSchemes("p1").isEmpty())
    }

    @Test
    fun `默认模板保存与加载`() = runTest {
        val stores: SettingsStores = SettingsStores(root())
        val launcher: LauncherSettings = LauncherSettings(
            memoryTier = "MEDIUM",
            graphicsBackend = GraphicsBackend.ZINK,
            performanceSwitches = PerformanceSwitches(l0Baseline = true),
        )
        assertTrue(stores.saveDefaultTemplate(OptionsFile.parse("renderDistance:12\n"), launcher).isOk)
        val (options, loadedLauncher) = stores.loadDefaultTemplate()
        assertEquals("12", options.get("renderDistance"))
        assertEquals(GraphicsBackend.ZINK, loadedLauncher.graphicsBackend)
        assertTrue(loadedLauncher.performanceSwitches.l0Baseline)
    }

    @Test
    fun `导出与导入`() = runTest {
        val stores: SettingsStores = SettingsStores(root())
        val exported = stores.exportOptions("p1", OptionsFile.parse("renderDistance:8\n"))
        assertTrue(exported.isOk)
        // 导入固定路径：把导出文件复制为 import-options.txt
        val importFile: File = File(stores.exportDir(), "import-options.txt")
        exported.getOrNull()!!.copyTo(importFile, overwrite = true)
        val imported = stores.importOptions("p1")
        assertTrue(imported.isOk)
        assertEquals("8", imported.getOrNull()!!.get("renderDistance"))
        // 缺失时报错
        importFile.delete()
        assertTrue(stores.importOptions("p1").isErr)
    }
}
