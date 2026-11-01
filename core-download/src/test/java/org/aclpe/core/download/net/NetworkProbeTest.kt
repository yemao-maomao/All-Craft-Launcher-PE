package org.aclpe.core.download.net

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.aclpe.core.download.install.JreInstaller
import org.aclpe.core.env.probe.EnvLayout
import org.aclpe.core.env.probe.EnvProbe
import org.aclpe.core.env.probe.RealProcessExecutor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 真实网络探针测试（默认跳过，仅本地验证用）：
 *   gradlew :core-download:testDebugUnitTest -Daclpe.network=true --tests "*NetworkProbeTest*"
 * 链路：DownloadManager 真实下载 Pojav JRE17（GitHub，38MB）→ SHA-256 校验 →
 * JreInstaller 原子安装 → 静态探测（EnvProbe 经假执行器验证结构）→ .build 标记。
 * 本测试验证「下载→校验→安装→就绪」全链路；双轨（1.21.x/JRE21）待 JRE21 下载源决策后同法验证。
 */
class NetworkProbeTest {

    companion object {
        const val JRE17_URL: String =
            "https://github.com/PojavLauncherTeam/android-openjdk-build-multiarch/releases/download/jre17-ec28559/jre17-arm64-20210825-release.tar.xz"
        const val JRE17_SHA256: String = "C64583AC2E0EC8857E43456FA9ADCF482C6A8E454A7133173BF15692D2478B8D"
        const val JRE17_SIZE: Long = 38_065_004L

        /** 方案 B：Java 21+ 槽位实际使用社区 JRE25（mcddos 镜像，本机实测 pin）。 */
        const val JRE25_URL: String = "http://oss.mcddos.top/fcl/jre/jre25-arm64.tar.xz"
        const val JRE25_SHA256: String = "94BCC9C654793F1B443BB6A0FC92A6DF0619C06CF7187236638BDAEF2EE9C4C2"
        const val JRE25_SIZE: Long = 37_751_348L
    }

    @Test
    fun `真实下载 JRE17 并安装就绪`() = runBlocking {
        // 环境变量可穿透 Gradle 测试工作 JVM（-D 仅影响 daemon）；默认跳过
        Assume.assumeTrue("网络探针默认跳过：设置环境变量 ACLPE_NETWORK=true 开启", System.getenv("ACLPE_NETWORK") == "true")

        val tmp: File = File(System.getProperty("java.io.tmpdir"), "netprobe-${System.nanoTime()}").apply { mkdirs() }
        val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(600, TimeUnit.SECONDS)
            .build()
        val manager: DownloadManager = DownloadManager(client)

        val dest: File = File(tmp, "jre17.tar.xz")
        val download = manager.download(
            DownloadRequest(
                id = "jre:17",
                url = JRE17_URL,
                destFile = dest,
                sha256 = JRE17_SHA256,
                sizeBytes = JRE17_SIZE,
            )
        )
        assertTrue("下载失败: ${download.errorOrNull()?.code}", download.isOk)

        val layout: EnvLayout = EnvLayout(File(tmp, "files"))
        val install = JreInstaller.install(dest, layout, 17, buildId = "pojav-20210825", sha256 = JRE17_SHA256)
        assertTrue("安装失败: ${install.errorOrNull()?.code}", install.isOk)

        // 结构就位（动态 exec 在真机由 EnvProbe 完成，此处静态断言；libjvm 可能经符号链接布局，用候选检查）
        assertTrue(layout.jreJavaBinary(17).isFile)
        assertTrue(layout.jreServerLibCandidates(17).any { it.isFile && it.length() > 0L })
        assertEquals("17", layout.jreReleaseFile(17).readLines().firstOrNull { it.startsWith("JAVA_VERSION") }?.substringAfter('"')?.substringBefore('"'))
        assertTrue(layout.jreBuildMarker(17).readText().contains(JRE17_SHA256.lowercase()))

        // EnvProbe 静态路径（假执行器输出真实版本 → READY）
        val probe: EnvProbe = EnvProbe(FakeOkExecutor)
        val result = probe.probeJre(layout, 17)
        assertTrue("探测应 Ok，实际 $result", result is org.aclpe.core.env.probe.JreProbeResult.Ok)
    }

    @Test
    fun `真实下载 JRE25 安装到 21 槽位_atLeast 就绪`() = runBlocking {
        Assume.assumeTrue("网络探针默认跳过：设置环境变量 ACLPE_NETWORK=true 开启", System.getenv("ACLPE_NETWORK") == "true")

        val tmp: File = File(System.getProperty("java.io.tmpdir"), "netprobe25-${System.nanoTime()}").apply { mkdirs() }
        val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(600, TimeUnit.SECONDS)
            .build()
        val manager: DownloadManager = DownloadManager(client)

        val dest: File = File(tmp, "jre25.tar.xz")
        val download = manager.download(
            DownloadRequest(id = "jre:21", url = JRE25_URL, destFile = dest, sha256 = JRE25_SHA256, sizeBytes = JRE25_SIZE)
        )
        assertTrue("下载失败: ${download.errorOrNull()?.code}", download.isOk)

        val layout: EnvLayout = EnvLayout(File(tmp, "files"))
        val install = JreInstaller.install(dest, layout, 21, buildId = "mcddos-20260729", sha256 = JRE25_SHA256)
        assertTrue("安装失败: ${install.errorOrNull()?.code}", install.isOk)

        assertTrue(layout.jreJavaBinary(21).isFile)
        assertTrue(layout.jreServerLibCandidates(21).any { it.isFile && it.length() > 0L })
        assertTrue(layout.jreBuildMarker(21).readText().contains(JRE25_SHA256.lowercase()))

        val probe: EnvProbe = EnvProbe(object : org.aclpe.core.env.probe.ProcessExecutor {
            override fun execute(
                command: List<String>,
                env: Map<String, String>,
                workingDir: File?,
                timeoutMs: Long,
            ): org.aclpe.core.env.probe.ExecResult = org.aclpe.core.env.probe.ExecResult(
                exitCode = 0,
                output = """
                    openjdk version "25.0.1" 2025-10-21
                    OpenJDK 64-Bit Server VM (build 25.0.1+12, mixed mode, sharing)
                """.trimIndent(),
                timedOut = false,
            )
        })
        val result = probe.probeJre(layout, 21, atLeast = true)
        assertTrue("21+ 槽位应接受 JRE25，实际 $result", result is org.aclpe.core.env.probe.JreProbeResult.Ok)
    }

    private object FakeOkExecutor : org.aclpe.core.env.probe.ProcessExecutor {
        override fun execute(
            command: List<String>,
            env: Map<String, String>,
            workingDir: File?,
            timeoutMs: Long,
        ): org.aclpe.core.env.probe.ExecResult = org.aclpe.core.env.probe.ExecResult(
            exitCode = 0,
            output = """
                openjdk version "17.0.1" 2021-10-19
                OpenJDK Runtime Environment (build 17.0.1+12-39)
                OpenJDK 64-Bit Server VM (build 17.0.1+12-39, mixed mode, sharing)
            """.trimIndent(),
            timedOut = false,
        )
    }
}
