package org.aclpe.core.download.install

import org.aclpe.core.common.result.AppResult
import org.aclpe.core.env.probe.EnvLayout
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.File

/** [JreInstaller] 与 [TarXzExtractor] 测试：合成 tar.xz → 安装 → 标记/替换/防护。 */
class JreInstallerTest {

    private fun tempRoot(): File =
        File(System.getProperty("java.io.tmpdir"), "jreinstall-${System.nanoTime()}").apply { mkdirs() }

    private fun makeTarXz(dir: File, entries: Map<String, String>, topDir: String): File {
        val tarXz: File = File(dir, "jre.tar.xz")
        XZOutputStream(tarXz.outputStream().buffered(), LZMA2Options()).use { xz ->
            TarArchiveOutputStream(xz).use { tar ->
                entries.forEach { (name, content) ->
                    val entry = TarArchiveEntry("$topDir/$name")
                    val bytes: ByteArray = content.toByteArray()
                    entry.size = bytes.size.toLong()
                    tar.putArchiveEntry(entry)
                    tar.write(bytes)
                    tar.closeArchiveEntry()
                }
                tar.finish()
            }
        }
        return tarXz
    }

    private val fakeJreEntries: Map<String, String> = mapOf(
        "bin/java" to "#!/bin/sh\n# fake java",
        "lib/server/libjvm.so" to "ELF",
        "lib/libjli.so" to "ELF",
        "release" to "JAVA_VERSION=\"17.0.5\"",
    )

    @Test
    fun `安装成功_文件就位且写就绪标记`() {
        val root: File = tempRoot()
        val layout: EnvLayout = EnvLayout(root)
        val tarXz: File = makeTarXz(root, fakeJreEntries, topDir = "jre17-arm64")

        val result: AppResult<File> = JreInstaller.install(tarXz, layout, 17, buildId = "b1", sha256 = "abc")
        assertTrue("安装失败: ${result.errorOrNull()?.code}", result.isOk)

        val jreDir: File = layout.jreDir(17)
        assertTrue(File(jreDir, "bin/java").isFile)
        assertTrue(File(jreDir, "lib/server/libjvm.so").isFile)
        val marker: String = layout.jreBuildMarker(17).readText()
        assertTrue(marker.contains("buildId=b1"))
        assertTrue(marker.contains("sha256=abc"))
    }

    @Test
    fun `升级替换_旧目录被换下`() {
        val root: File = tempRoot()
        val layout: EnvLayout = EnvLayout(root)
        val tarXz: File = makeTarXz(root, fakeJreEntries, topDir = "jre17-arm64")

        // 先装一版
        assertTrue(JreInstaller.install(tarXz, layout, 17, "b1", "abc").isOk)
        // 再装一版（内容不同）
        val tarXz2: File = makeTarXz(root, fakeJreEntries + ("bin/java" to "#!/bin/sh\n# v2"), topDir = "jre17-arm64")
        assertTrue(JreInstaller.install(tarXz2, layout, 17, "b2", "def").isOk)

        val javaBin: File = layout.jreJavaBinary(17)
        assertTrue(javaBin.readText().contains("v2"))
        assertTrue(layout.jreBuildMarker(17).readText().contains("buildId=b2"))
        // 无 staging/backup 残留
        val parent: File = layout.jreDir(17).parentFile!!
        val leftovers: Array<File> = parent.listFiles() ?: emptyArray()
        assertTrue(leftovers.none { it.name.startsWith(".staging") || it.name.startsWith("arm64.old") })
    }

    @Test
    fun `关键文件缺失_冒烟失败且不破坏旧目录`() {
        val root: File = tempRoot()
        val layout: EnvLayout = EnvLayout(root)
        val good: File = makeTarXz(root, fakeJreEntries, topDir = "jre17-arm64")
        assertTrue(JreInstaller.install(good, layout, 17, "b1", "abc").isOk)

        val brokenEntries: Map<String, String> = mapOf("release" to "JAVA_VERSION=\"17\"")
        val broken: File = makeTarXz(root, brokenEntries, topDir = "jre17-arm64")
        val result: AppResult<File> = JreInstaller.install(broken, layout, 17, "b2", "def")
        assertTrue(result.isErr)
        assertEquals("jre_smoke_failed", result.errorOrNull()?.code)
        // 旧目录保持原状
        assertTrue(layout.jreJavaBinary(17).isFile)
        assertTrue(layout.jreBuildMarker(17).readText().contains("buildId=b1"))
    }

    @Test
    fun `zip_slip 防护`() {
        val root: File = tempRoot()
        val tarXz: File = File(root, "evil.tar.xz")
        XZOutputStream(tarXz.outputStream().buffered(), LZMA2Options()).use { xz ->
            TarArchiveOutputStream(xz).use { tar ->
                val entry = TarArchiveEntry("top/../evil.txt")
                val bytes: ByteArray = "evil".toByteArray()
                entry.size = bytes.size.toLong()
                tar.putArchiveEntry(entry)
                tar.write(bytes)
                tar.closeArchiveEntry()
                tar.finish()
            }
        }
        val result: AppResult<Unit> = TarXzExtractor.extract(tarXz, File(root, "out"))
        assertTrue(result.isErr)
        assertEquals("zip_slip", result.errorOrNull()?.code)
        assertTrue(!File(root, "evil.txt").exists())
    }

    @Test
    fun `tar 内符号链接解析为内容副本`() {
        // Pojav JRE17 真实包形态：lib/server/libjvm.so → ../aarch64/server/libjvm.so
        val root: File = tempRoot()
        val tarXz: File = File(root, "sym.tar.xz")
        XZOutputStream(tarXz.outputStream().buffered(), LZMA2Options()).use { xz ->
            TarArchiveOutputStream(xz).use { tar ->
                fun putFile(name: String, content: String) {
                    val entry = TarArchiveEntry(name)
                    val bytes: ByteArray = content.toByteArray()
                    entry.size = bytes.size.toLong()
                    tar.putArchiveEntry(entry)
                    tar.write(bytes)
                    tar.closeArchiveEntry()
                }
                fun putSymlink(name: String, target: String) {
                    // LF_SYMLINK = '2'（commons-compress 1.26 移除 TarArchiveConstants，用字节常量）
                    val entry = TarArchiveEntry(name, '2'.code.toByte())
                    entry.linkName = target
                    tar.putArchiveEntry(entry)
                    tar.closeArchiveEntry()
                }
                putFile("top/bin/java", "#!/bin/sh\njava")
                putFile("top/lib/aarch64/server/libjvm.so", "ELF")
                putSymlink("top/lib/server/libjvm.so", "../aarch64/server/libjvm.so")
                tar.finish()
            }
        }
        val result: AppResult<Unit> = TarXzExtractor.extract(tarXz, File(root, "out"))
        assertTrue(result.isOk)
        assertTrue(File(root, "out/lib/aarch64/server/libjvm.so").isFile)
        assertTrue(File(root, "out/lib/server/libjvm.so").isFile)   // 符号链接已解析为内容副本
        assertEquals("ELF", File(root, "out/lib/server/libjvm.so").readText())
    }
}
