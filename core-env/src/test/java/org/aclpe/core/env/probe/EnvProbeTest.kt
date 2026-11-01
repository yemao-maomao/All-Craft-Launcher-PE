package org.aclpe.core.env.probe

import org.aclpe.core.env.model.ComponentProbeResult
import org.aclpe.core.env.model.EnvState
import org.aclpe.core.env.testutil.FakeProcessExecutor
import org.aclpe.core.env.testutil.OUTPUT_JRE17_SERVER
import org.aclpe.core.env.testutil.OUTPUT_JRE21_SERVER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** [EnvProbe] 单元测试：伪造执行器与临时目录，覆盖 JRE/natives/assets 探测矩阵（设计文档 §8）。 */
class EnvProbeTest {

    private fun tempRoot(): File =
        File(System.getProperty("java.io.tmpdir"), "envprobe-${System.nanoTime()}").apply { mkdirs() }

    private fun makeJre(layout: EnvLayout, major: Int, withServer: Boolean = true): File {
        val dir: File = layout.jreDir(major)
        File(dir, "bin").mkdirs()
        File(dir, "lib/server").mkdirs()
        layout.jreJavaBinary(major).writeText("#!/bin/sh\necho fake")
        layout.jreJliLib(major).writeText("fake")
        if (withServer) layout.jreServerLib(major).writeText("fake")
        layout.jreReleaseFile(major).writeText("JAVA_VERSION=\"$major\"")
        return dir
    }

    private fun okExec(output: String) = ExecResult(exitCode = 0, output = output, timedOut = false)

    // ---------- JRE ----------

    @Test
    fun `JRE 目录不存在返回 Missing`() {
        val layout: EnvLayout = EnvLayout(tempRoot())
        val probe: EnvProbe = EnvProbe(FakeProcessExecutor(okExec("")))
        assertEquals(JreProbeResult.Missing, probe.probeJre(layout, 17))
    }

    @Test
    fun `目录存在但缺 java 二进制返回 Partial`() {
        val layout: EnvLayout = EnvLayout(tempRoot())
        layout.jreDir(17).mkdirs()
        val probe: EnvProbe = EnvProbe(FakeProcessExecutor(okExec("")))
        val result: JreProbeResult = probe.probeJre(layout, 17)
        assertTrue(result is JreProbeResult.Partial)
        assertTrue((result as JreProbeResult.Partial).missingFiles.isNotEmpty())
    }

    @Test
    fun `缺 Server Compiler 返回 Partial`() {
        val layout: EnvLayout = EnvLayout(tempRoot())
        makeJre(layout, 17, withServer = false)
        val probe: EnvProbe = EnvProbe(FakeProcessExecutor(okExec(OUTPUT_JRE17_SERVER)))
        assertTrue(probe.probeJre(layout, 17) is JreProbeResult.Partial)
    }

    @Test
    fun `完整 JRE 且输出正常返回 Ok`() {
        val layout: EnvLayout = EnvLayout(tempRoot())
        makeJre(layout, 17)
        val probe: EnvProbe = EnvProbe(FakeProcessExecutor(okExec(OUTPUT_JRE17_SERVER)))
        val result: JreProbeResult = probe.probeJre(layout, 17)
        assertTrue("期望 Ok，实际 $result", result is JreProbeResult.Ok)
        assertEquals(17, (result as JreProbeResult.Ok).info.major)
    }

    @Test
    fun `版本不符返回 WrongVersion`() {
        val layout: EnvLayout = EnvLayout(tempRoot())
        makeJre(layout, 17)
        val probe: EnvProbe = EnvProbe(FakeProcessExecutor(okExec(OUTPUT_JRE21_SERVER)))
        assertEquals(JreProbeResult.WrongVersion(expected = 17, actual = 21), probe.probeJre(layout, 17))
    }

    @Test
    fun `atLeast 语义_槽位 21 接受更高版本`() {
        // 方案 B：Java 21+ 槽位实际安装 JRE25 → 探测接受 major ≥ 21
        val layout: EnvLayout = EnvLayout(tempRoot())
        makeJre(layout, 21)
        val output25: String = """openjdk version "25.0.1" 2025-10-21
OpenJDK 64-Bit Server VM (build 25.0.1+12, mixed mode, sharing)
"""
        val probe: EnvProbe = EnvProbe(FakeProcessExecutor(okExec(output25)))
        val ok: JreProbeResult = probe.probeJre(layout, 21, atLeast = true)
        assertTrue("atLeast 应接受 25: $ok", ok is JreProbeResult.Ok)
        // 低于槽位仍拒绝
        val low: JreProbeResult = probe.probeJre(layout, 21, atLeast = true).let {
            val probeLow: EnvProbe = EnvProbe(FakeProcessExecutor(okExec(OUTPUT_JRE17_SERVER)))
            probeLow.probeJre(layout, 21, atLeast = true)
        }
        assertEquals(JreProbeResult.WrongVersion(expected = 21, actual = 17), low)
        // 非 atLeast 槽位要求精确
        val exact: JreProbeResult = probe.probeJre(layout, 21, atLeast = false)
        assertEquals(JreProbeResult.WrongVersion(expected = 21, actual = 25), exact)
    }

    @Test
    fun `32 位输出返回 Not64Bit`() {
        val layout: EnvLayout = EnvLayout(tempRoot())
        makeJre(layout, 17)
        val output: String = """openjdk version "17.0.2" 2022-01-18
OpenJDK 32-Bit Server VM (build 17.0.2+8, mixed mode)
"""
        val probe: EnvProbe = EnvProbe(FakeProcessExecutor(okExec(output)))
        assertEquals(JreProbeResult.Not64Bit, probe.probeJre(layout, 17))
    }

    @Test
    fun `位宽不明确返回 Not64Bit_设计决策`() {
        val layout: EnvLayout = EnvLayout(tempRoot())
        makeJre(layout, 17)
        val output: String = """openjdk version "17.0.5" 2022-10-18
OpenJDK Server VM (build 17.0.5+8, mixed mode)
"""
        val probe: EnvProbe = EnvProbe(FakeProcessExecutor(okExec(output)))
        assertEquals(JreProbeResult.Not64Bit, probe.probeJre(layout, 17))
    }

    @Test
    fun `无 Server Compiler 返回 NoServerCompiler`() {
        val layout: EnvLayout = EnvLayout(tempRoot())
        makeJre(layout, 17)
        val output: String = """openjdk version "17.0.5" 2022-10-18
OpenJDK 64-Bit Zero VM (build 17.0.5+8, interpreted mode)
"""
        val probe: EnvProbe = EnvProbe(FakeProcessExecutor(okExec(output)))
        assertEquals(JreProbeResult.NoServerCompiler, probe.probeJre(layout, 17))
    }

    @Test
    fun `执行超时返回 ExecFailed_timeout`() {
        val layout: EnvLayout = EnvLayout(tempRoot())
        makeJre(layout, 17)
        val probe: EnvProbe = EnvProbe(FakeProcessExecutor(ExecResult(0, "", timedOut = true)))
        val result: JreProbeResult = probe.probeJre(layout, 17)
        assertTrue(result is JreProbeResult.ExecFailed && result.code == "timeout")
    }

    @Test
    fun `输出不可解析返回 ExecFailed_parse`() {
        val layout: EnvLayout = EnvLayout(tempRoot())
        makeJre(layout, 17)
        val probe: EnvProbe = EnvProbe(FakeProcessExecutor(okExec("not a java output")))
        val result: JreProbeResult = probe.probeJre(layout, 17)
        assertTrue(result is JreProbeResult.ExecFailed && result.code == "parse")
    }

    @Test
    fun `非零退出码返回 ExecFailed_exit`() {
        val layout: EnvLayout = EnvLayout(tempRoot())
        makeJre(layout, 17)
        val probe: EnvProbe = EnvProbe(FakeProcessExecutor(ExecResult(1, OUTPUT_JRE17_SERVER, timedOut = false)))
        val result: JreProbeResult = probe.probeJre(layout, 17)
        assertTrue(result is JreProbeResult.ExecFailed && result.code == "exit_1")
    }

    // ---------- natives ----------

    @Test
    fun `natives 各状态矩阵`() {
        val layout: EnvLayout = EnvLayout(tempRoot())
        val probe: EnvProbe = EnvProbe(FakeProcessExecutor(okExec("")))

        assertEquals(ComponentProbeResult.Missing("natives 目录不存在"), probe.probeNatives(layout, "h1"))

        val dir: File = layout.nativesDir("h1")
        dir.mkdirs()
        assertTrue(probe.probeNatives(layout, "h1") is ComponentProbeResult.Partial)   // 无标记

        layout.nativesMarker("h1").writeText("")
        assertTrue(probe.probeNatives(layout, "h1") is ComponentProbeResult.Partial)   // 目录空

        File(dir, "libtest.so").writeText("ELF")
        assertEquals(ComponentProbeResult.Ok, probe.probeNatives(layout, "h1"))
    }

    @Test
    fun `natives 无有效 so 判定损坏`() {
        val layout: EnvLayout = EnvLayout(tempRoot())
        val probe: EnvProbe = EnvProbe(FakeProcessExecutor(okExec("")))
        val dir: File = layout.nativesDir("h1")
        dir.mkdirs()
        layout.nativesMarker("h1").writeText("")
        File(dir, "notes.txt").writeText("x")
        val result: ComponentProbeResult = probe.probeNatives(layout, "h1")
        assertTrue(result is ComponentProbeResult.Corrupted)
    }

    // ---------- assets 索引 ----------

    @Test
    fun `assets 索引矩阵_缺失空文件与哈希`() {
        val layout: EnvLayout = EnvLayout(tempRoot())
        val probe: EnvProbe = EnvProbe(FakeProcessExecutor(okExec("")))

        assertTrue(probe.probeAssetsIndex(layout, "p1", "1.20.1", "idx", "aa") is ComponentProbeResult.Missing)

        val file: File = layout.assetsIndexFile("p1", "1.20.1", "idx")
        file.parentFile?.mkdirs()
        file.writeText("")
        assertTrue(probe.probeAssetsIndex(layout, "p1", "1.20.1", "idx", "aa") is ComponentProbeResult.Corrupted)

        file.writeText("{\"objects\":{}}")
        assertTrue(probe.probeAssetsIndex(layout, "p1", "1.20.1", "idx", "aa") is ComponentProbeResult.Corrupted)

        val expected: String = EnvProbe.sha1Hex(file)
        assertEquals(ComponentProbeResult.Ok, probe.probeAssetsIndex(layout, "p1", "1.20.1", "idx", expected))
    }

    // ---------- 报告合成 ----------

    @Test
    fun `EnvState 合成优先级 CORRUPTED 大于 PARTIAL 大于 MISSING`() {
        val corruptedJre = mapOf(17 to JreProbeResult.WrongVersion(17, 21))
        assertEquals(EnvState.CORRUPTED, EnvProbeReport(corruptedJre, emptyMap(), emptyMap()).state)

        val partialNatives = mapOf("h1" to ComponentProbeResult.Partial(listOf("x")))
        val missingJre = mapOf(17 to JreProbeResult.Missing)
        assertEquals(EnvState.PARTIAL, EnvProbeReport(missingJre, partialNatives, emptyMap()).state)
        assertEquals(EnvState.MISSING, EnvProbeReport(missingJre, emptyMap(), emptyMap()).state)

        val okJre = mapOf(17 to JreProbeResult.Ok(JreVersionParser.parse(OUTPUT_JRE17_SERVER)!!))
        assertEquals(EnvState.READY, EnvProbeReport(okJre, emptyMap(), emptyMap()).state)
    }

    @Test
    fun `探测报告带问题明细`() {
        val report = EnvProbeReport(
            jres = mapOf(17 to JreProbeResult.WrongVersion(17, 21)),
            natives = emptyMap(),
            assetsIndexes = emptyMap(),
        )
        assertTrue(report.issues.any { it.code == "jre_wrong_version@jre:17" })
    }
}
