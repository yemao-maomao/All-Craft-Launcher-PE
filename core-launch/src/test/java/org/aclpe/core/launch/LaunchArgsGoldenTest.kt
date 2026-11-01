package org.aclpe.core.launch

import org.aclpe.core.download.json.VersionJson
import org.aclpe.core.download.json.VersionJsonParser
import org.aclpe.core.download.mirror.MirrorId
import org.aclpe.core.download.resolve.LibraryResolver
import org.aclpe.core.download.resolve.ResolvedLibrary
import org.aclpe.core.download.rules.PlatformContext
import org.aclpe.core.launch.args.LaunchArgsBuilder
import org.aclpe.core.launch.model.LaunchAuth
import org.aclpe.core.launch.model.LaunchContext
import org.aclpe.core.launch.model.MemoryTier
import org.aclpe.core.launch.process.LaunchCommand
import org.aclpe.core.launch.process.LaunchPlanBuilder
import org.aclpe.core.env.probe.EnvLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 启动命令拼装 golden 测试（用户规格：给定真实 version json，
 * 断言生成命令的关键字段与官方 launcher 等价）。
 * 平台上下文与 Step 2 rules 引擎共用 OsSpec 单一常量源。
 */
class LaunchArgsGoldenTest {

    private val ctx: PlatformContext = PlatformContext.fromOsSpec()

    private fun version(id: String): VersionJson {
        val text: String = File("src/test/resources/versions/$id.json").readText()
        val result = VersionJsonParser.parseVersionJson(text)
        assertTrue("解析 $id 失败", result.isOk)
        return (result as org.aclpe.core.common.result.AppResult.Ok).value
    }

    private fun context(
        version: VersionJson,
        libraries: List<ResolvedLibrary> = LibraryResolver.resolve(version.libraries, ctx, MirrorId.OFFICIAL),
        clientJar: File = File("/tmp/g/versions/${version.id}/${version.id}.jar"),
    ): LaunchContext = LaunchContext(
        version = version,
        auth = LaunchAuth(name = "Steve", uuid = "00000000-0000-0000-0000-000000000001"),
        gameDir = File("/tmp/g"),
        assetsDir = File("/tmp/g/assets"),
        assetsIndexId = version.assetIndex?.id ?: "legacy",
        nativesDir = File("/tmp/g/natives"),
        libraries = libraries,
        clientJar = clientJar,
        javaMajor = version.javaVersion?.majorVersion ?: 8,
        memoryTier = MemoryTier.MEDIUM,
        deviceMemoryMb = 4096,
    )

    @Test
    fun `1_20_1 现代模板_game 参数与官方等价`() {
        val v: VersionJson = version("1.20.1")
        val args: List<String> = LaunchArgsBuilder.buildGameArgs(context(v))

        // 官方 launcher 前 6 项顺序（模板展开 + 占位符替换）
        assertEquals("--username", args[0])
        assertEquals("Steve", args[1])
        assertEquals("--version", args[2])
        assertEquals("1.20.1", args[3])
        assertEquals("--gameDir", args[4])
        assertEquals(File("/tmp/g").absolutePath, args[5])
        // 后续模板项含 assetsDir/assetIndex/uuid/accessToken(offline 空)/userType
        val idx: Int = args.indexOf("--assetIndex")
        assertTrue(idx > 0)
        assertEquals("5", args[idx + 1])
        assertTrue(args.indexOf("--accessToken") > 0)
        assertTrue(args[args.indexOf("--accessToken") + 1].isEmpty())   // offline token 空
    }

    @Test
    fun `1_20_1 jvm 参数_模板 cp 不重复_主类追加`() {
        val v: VersionJson = version("1.20.1")
        val args: List<String> = LaunchArgsBuilder.buildJvmArgs(context(v))

        assertEquals(1, args.count { it == "-cp" })
        val cpIndex: Int = args.indexOf("-cp")
        assertTrue(cpIndex >= 0)
        val classpath: String = args[cpIndex + 1]
        // classpath = 43 个库 + client jar（与 Step 2 golden 基线一致：库顺序敏感）
        val parts: List<String> = classpath.split(':')
        assertEquals(44, parts.size)
        assertTrue(parts.last().endsWith("1.20.1.jar"))
        // 模板注入的 natives 目录参数已展开
        assertTrue(args.any { it == "-Djava.library.path=${File("/tmp/g/natives").absolutePath}" })
        // 品牌注入
        assertTrue(args.any { it == "-Dminecraft.launcher.brand=ACL-PE" })
        // 内存参数（-Xmx ≤ 可用内存一半 = 2048，档位 MEDIUM=2048）
        assertTrue(args.any { it == "-Xmx2048M" })
        assertTrue(args.any { it == "-XX:+UseG1GC" })
        // osx/windows 专属参数被 rules 过滤
        assertTrue(args.none { it.contains("MojangTricksIntelDrivers") })
        assertTrue(args.none { it == "-XstartOnFirstThread" })
        // 主类在最后
        assertEquals("net.minecraft.client.main.Main", args.last())
    }

    @Test
    fun `1_8_9 遗留模板_game 参数占位符替换`() {
        val v: VersionJson = version("1.8.9")
        val args: List<String> = LaunchArgsBuilder.buildGameArgs(context(v))

        assertEquals("--username", args[0])
        assertEquals("Steve", args[1])
        assertTrue(args.contains("--uuid"))
        assertTrue(args.contains("00000000-0000-0000-0000-000000000001"))
        assertTrue(args.contains("--userProperties"))
        assertEquals("{}", args[args.indexOf("--userProperties") + 1])
        assertTrue(args.contains("--accessToken"))
    }

    @Test
    fun `1_8_9 遗留模板_jvm 全量生成`() {
        val v: VersionJson = version("1.8.9")
        val args: List<String> = LaunchArgsBuilder.buildJvmArgs(context(v))

        assertEquals(1, args.count { it == "-cp" })
        assertTrue(args.any { it.startsWith("-Xmx") })
        assertTrue(args.any { it.startsWith("-Xloggc:") })   // Java 8 分流
        assertEquals("net.minecraft.client.main.Main", args.last())
        // classpath = 30 库 + client jar（1.8.9 无 downloads.client → 传统位置）
        val cp: String = args[args.indexOf("-cp") + 1]
        assertEquals(31, cp.split(':').size)
        assertTrue(cp.endsWith("versions/1.8.9/1.8.9.jar"))
    }

    @Test
    fun `features 条件参数被过滤_OsSpec 空特性集`() {
        // 有 has_custom_resolution 条件的条目在特性缺失时不生效（rules 引擎语义）
        val v: VersionJson = version("1.20.1")
        val args: List<String> = LaunchArgsBuilder.buildGameArgs(context(v))
        // 1.20.1 的 --width/--height 由特性规则包裹；OsSpec.FEATURES 为空 → 不出现
        assertTrue(args.none { it == "--width" })
        assertTrue(args.none { it == "--height" })
    }

    @Test
    fun `LaunchPlanBuilder 装配环境变量与二进制路径`() {
        val v: VersionJson = version("1.20.1")
        val root: File = File(System.getProperty("java.io.tmpdir"), "plan-${System.nanoTime()}").apply { mkdirs() }
        val layout: EnvLayout = EnvLayout(root)
        val jreBin: File = layout.jreJavaBinary(17)
        jreBin.parentFile?.mkdirs()
        jreBin.writeText("x")

        val builder: LaunchPlanBuilder = LaunchPlanBuilder(layout)
        val result = builder.build(context(v))
        assertTrue("build 失败: ${result.errorOrNull()?.code} / ${result.errorOrNull()?.message} / ${result.errorOrNull()?.cause}", result.isOk)
        val cmd: LaunchCommand = result.getOrNull()!!
        assertEquals(jreBin, cmd.javaBinary)
        assertTrue(cmd.env["LD_LIBRARY_PATH"]!!.contains("lib${File.separator}server"))
        assertEquals(root.absolutePath, cmd.env["ANDROID_DATA"])
        assertEquals("/system", cmd.env["ANDROID_ROOT"])
        assertEquals(cmd.jvmArgs + cmd.gameArgs, cmd.fullArgs)
    }

    @Test
    fun `java 二进制缺失返回 env 错误`() {
        val v: VersionJson = version("1.20.1")
        val root: File = File(System.getProperty("java.io.tmpdir"), "plan-missing-${System.nanoTime()}").apply { mkdirs() }
        val builder: LaunchPlanBuilder = LaunchPlanBuilder(EnvLayout(root))
        val result = builder.build(context(v))
        assertTrue(result.isErr)
        assertEquals("jre_binary_missing", result.errorOrNull()?.code)
    }
}
