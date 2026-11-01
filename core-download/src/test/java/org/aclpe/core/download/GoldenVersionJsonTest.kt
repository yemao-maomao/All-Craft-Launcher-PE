package org.aclpe.core.download

import org.aclpe.core.download.json.VersionJson
import org.aclpe.core.download.json.VersionJsonParser
import org.aclpe.core.download.mirror.MirrorId
import org.aclpe.core.download.resolve.LibraryResolver
import org.aclpe.core.download.resolve.ResolvedNatives
import org.aclpe.core.download.rules.PlatformContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 6 个真实 version json golden 交叉验证（附件 A 替代方案）：
 * 1) 解析全部成功且 id/mainClass 正确；
 * 2) 库/natives 解析结果与按官方语义手工推导的基准数一致（基线随测试固化为常量）。
 * 基准推导：linux/arm64、features 空集、有序匹配、无规则=允许、有规则无命中=disallow。
 */
class GoldenVersionJsonTest {

    private val ctx: PlatformContext = PlatformContext.fromOsSpec()

    private fun fixture(id: String): File =
        File("src/test/resources/versions/$id.json")

    private fun parse(id: String): VersionJson {
        val text: String = fixture(id).readText()
        val result = VersionJsonParser.parseVersionJson(text)
        assertTrue("解析 $id 失败: ${(result as? org.aclpe.core.common.result.AppResult.Err)?.error?.code}", result.isOk)
        return (result as org.aclpe.core.common.result.AppResult.Ok).value
    }

    private data class Expect(val id: String, val classpath: Int, val natives: Int, val javaMajor: Int?)

    private val expectations: List<Expect> = listOf(
        Expect("1.8.9", classpath = 30, natives = 2, javaMajor = 8),     // Mojang 已补 javaVersion；natives=2：
        //   lwjgl-platform 2.9.4（linux 可用）+ jinput；lwjgl 2.9.2 为 osx-only 被规则排除、twitch 缺 natives-linux 被丢弃
        Expect("1.12.2", classpath = 31, natives = 3, javaMajor = 8),    // + text2speech
        Expect("1.20.1", classpath = 43, natives = 8, javaMajor = 17),   // natives = 7×LWJGL + netty linux-aarch_64
        Expect("1.20.5", classpath = 46, natives = 9, javaMajor = 21),
        Expect("1.21", classpath = 46, natives = 9, javaMajor = 21),
        Expect("1.21.4", classpath = 59, natives = 10, javaMajor = 21),
    )

    @Test
    fun `全部 golden json 解析成功且字段正确`() {
        expectations.forEach { expect ->
            val v: VersionJson = parse(expect.id)
            assertEquals(expect.id, v.id)
            assertTrue(v.mainClass.isNotBlank())
            assertEquals(expect.javaMajor, v.javaVersion?.majorVersion)
        }
    }

    @Test
    fun `库解析结果与基线一致`() {
        expectations.forEach { expect ->
            val v: VersionJson = parse(expect.id)
            val libs = LibraryResolver.resolve(v.libraries, ctx, MirrorId.OFFICIAL)
            val natives = LibraryResolver.resolveNatives(v.libraries, ctx, MirrorId.OFFICIAL)
            assertEquals("$expect.id classpath 数不符", expect.classpath, libs.size)
            assertEquals("$expect.id natives 数不符", expect.natives, natives.size)
        }
    }

    @Test
    fun `natives 分类器按平台正确选择`() {
        // 现代版本：分类器应为 natives-linux（LWJGL）或平台候选（netty 等架构专属原生库）
        listOf("1.20.1", "1.20.5", "1.21", "1.21.4").forEach { id ->
            val v: VersionJson = parse(id)
            val natives = LibraryResolver.resolveNatives(v.libraries, ctx, MirrorId.OFFICIAL)
            assertTrue("$id 存在非 linux natives", natives.isNotEmpty())
            val allowed: Set<String> = setOf("natives-linux") + ctx.platformClassifierCandidates()
            natives.forEach { n: ResolvedNatives ->
                assertTrue("$id ${n.name} 分类器 ${n.classifier} 应为 linux 平台分类器", n.classifier in allowed)
                assertTrue("$id ${n.name} URL 应指向 linux natives jar", n.artifact.path?.contains("-natives-") == true || n.artifact.path?.contains("${n.classifier}.jar") == true)
            }
        }
        // 遗留版本：natives 映射选 linux
        listOf("1.8.9", "1.12.2").forEach { id ->
            val v: VersionJson = parse(id)
            val natives = LibraryResolver.resolveNatives(v.libraries, ctx, MirrorId.OFFICIAL)
            assertTrue(natives.isNotEmpty())
            natives.forEach { n: ResolvedNatives -> assertEquals("natives-linux", n.classifier) }
        }
    }

    @Test
    fun `平台专属库被排除`() {
        // osx 专属的 java-objc-bridge 在所有版本都不该出现在 linux 的解析结果里
        listOf("1.8.9", "1.12.2", "1.20.1", "1.21.4").forEach { id ->
            val v: VersionJson = parse(id)
            val names: Set<String> = LibraryResolver.resolve(v.libraries, ctx, MirrorId.OFFICIAL).map { it.name }.toSet()
            assertTrue("$id 不应包含 osx 专属库", names.none { it.contains("java-objc-bridge") })
        }
    }

    @Test
    fun `natives 提取规则_遗留带 exclude_现代为 null`() {
        // 遗留（≤1.12.2）：extract.exclude 显式排除 META-INF
        val legacy: VersionJson = parse("1.8.9")
        LibraryResolver.resolveNatives(legacy.libraries, ctx, MirrorId.OFFICIAL).forEach { n: ResolvedNatives ->
            assertEquals("${n.name} extract.exclude", listOf("META-INF/"), n.extract?.exclude)
        }
        // 现代（1.20.1+）：natives 条目无 extract（提取时由安装器默认排除 META-INF，见 JreInstaller 设计）
        val modern: VersionJson = parse("1.20.1")
        LibraryResolver.resolveNatives(modern.libraries, ctx, MirrorId.OFFICIAL).forEach { n: ResolvedNatives ->
            assertEquals("${n.name} extract 应为 null", null, n.extract)
        }
    }

    @Test
    fun `镜像重写后 URL 指向 BMCLAPI`() {
        val v: VersionJson = parse("1.20.1")
        val natives = LibraryResolver.resolveNatives(v.libraries, ctx, MirrorId.BMCLAPI)
        natives.forEach { n: ResolvedNatives ->
            assertTrue("${n.name} 应为 BMCLAPI 地址: ${n.url}", n.url.startsWith("https://bmclapi2.bangbang93.com/maven/"))
        }
    }
}
