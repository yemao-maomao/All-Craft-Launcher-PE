package org.aclpe.core.download.resolve

import org.aclpe.core.download.json.Artifact
import org.aclpe.core.download.json.ExtractSpec
import org.aclpe.core.download.json.Library
import org.aclpe.core.download.mirror.MirrorId
import org.aclpe.core.download.mirror.MirrorRegistry
import org.aclpe.core.download.rules.PlatformContext
import org.aclpe.core.download.rules.RulesEngine

/** 解析后的 classpath 库（无分类器的普通 jar）。 */
data class ResolvedLibrary(
    val name: String,
    val artifact: Artifact,
    val url: String,
)

/** 解析后的 natives 库（下载并解压到 natives 目录，extract.exclude 过滤）。 */
data class ResolvedNatives(
    val name: String,
    val classifier: String,
    val artifact: Artifact,
    val url: String,
    val extract: ExtractSpec?,
)

/**
 * 库解析（双模型，已用 6 个真实 version json golden 交叉验证）：
 *
 * 现代模型（1.19+）：natives 是**带第四段分类器名的独立库条目**
 *   （如 `org.lwjgl:lwjgl-glfw:3.3.1:natives-linux`，artifact 即 natives jar，rules 选平台）；
 * 遗留模型（≤1.12.2）：库带 `natives` 映射（os → 分类器名），jar 在 downloads.classifiers 里。
 *
 * 归属规则：
 * - rules 不过滤的库忽略（RulesEngine，默认 disallow）；
 * - 现代模型：四段名且分类器以 natives- 开头 → natives；其余四段名（如 sources）丢弃；
 * - 遗留模型：带 natives 映射 → natives（不进 classpath）；其余 → classpath；
 * - classpath 顺序保持输入顺序（顺序敏感，Step 3 拼 classpath 依赖此性质）。
 */
object LibraryResolver {

    /** 遗留模型 natives 映射键候选：先 os-arch（部分构建），再 os。 */
    fun nativesClassifierKeys(ctx: PlatformContext): List<String> = listOf(
        "${ctx.osName}-${ctx.osArch}",
        ctx.osName,
    )

    fun resolve(libraries: List<Library>, ctx: PlatformContext, mirror: MirrorId): List<ResolvedLibrary> =
        libraries.mapNotNull { library ->
            if (!RulesEngine.evaluate(library.rules, ctx)) return@mapNotNull null
            val parts: List<String> = library.name.split(':')
            if (parts.size >= 4) return@mapNotNull null            // 分类器条目（natives/sources）不进 classpath
            if (library.natives != null) return@mapNotNull null    // 遗留 natives 库不进 classpath
            val artifact: Artifact = library.downloads?.artifact ?: return@mapNotNull null
            val url: String = artifactUrl(library, artifact, mirror) ?: return@mapNotNull null
            ResolvedLibrary(
                name = library.name,
                artifact = artifact,
                url = url,
            )
        }

    fun resolveNatives(libraries: List<Library>, ctx: PlatformContext, mirror: MirrorId): List<ResolvedNatives> =
        libraries.mapNotNull { library ->
            if (!RulesEngine.evaluate(library.rules, ctx)) return@mapNotNull null
            val parts: List<String> = library.name.split(':')
            // 现代模型：名称第四段即分类器。
            // natives-* 传统 natives（rules 选平台）；架构专属原生库（如 netty linux-aarch_64）
            // 按 [PlatformContext.platformClassifierCandidates] 匹配，规则通常只写 os 不写 arch。
            if (parts.size == 4) {
                val classifier: String = parts[3]
                val isNatives: Boolean =
                    classifier.startsWith("natives-") || classifier in ctx.platformClassifierCandidates()
                if (!isNatives) return@mapNotNull null
                val artifact: Artifact = library.downloads?.artifact ?: return@mapNotNull null
                val url: String = artifactUrl(library, artifact, mirror) ?: return@mapNotNull null
                return@mapNotNull ResolvedNatives(
                    name = library.name,
                    classifier = classifier,
                    artifact = artifact,
                    url = url,
                    extract = library.extract,
                )
            }
            // 遗留模型：natives 映射
            val nativesMap: Map<String, String> = library.natives ?: return@mapNotNull null
            val classifierKey: String = nativesClassifierKeys(ctx).firstOrNull { nativesMap.containsKey(it) }
                ?: return@mapNotNull null
            val classifierName: String = nativesMap.getValue(classifierKey)
            val artifact: Artifact = library.downloads?.classifiers?.get(classifierName)
                ?: return@mapNotNull null
            val url: String = artifactUrl(library, artifact, mirror) ?: return@mapNotNull null
            ResolvedNatives(
                name = library.name,
                classifier = classifierName,
                artifact = artifact,
                url = url,
                extract = library.extract,
            )
        }

    /**
     * 地址回退链：artifact.url → 库自定基址 + path（如 Forge maven）→ 官方 libraries 基址 + path；
     * 最终统一经镜像重写。path 与 url 都缺失时返回 null（该条目无法下载，调用方跳过）。
     */
    fun artifactUrl(library: Library, artifact: Artifact, mirror: MirrorId): String? {
        val raw: String? = artifact.url
            ?: library.url?.trimEnd('/')?.let { base -> artifact.path?.let { "$base/$it" } }
            ?: artifact.path?.let { "https://libraries.minecraft.net/$it" }
        return raw?.let { MirrorRegistry.rewrite(it, mirror) }
    }
}
