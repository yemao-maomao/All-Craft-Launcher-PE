package org.aclpe.core.env.probe

import org.aclpe.core.env.model.ComponentProbeResult
import org.aclpe.core.env.model.EnvIssue
import org.aclpe.core.env.model.EnvState
import java.io.File
import java.security.MessageDigest

/** 单一 JRE 的探测结论（用户规格：解析主/次版本、Server Compiler 是否存在、是否 64 位）。 */
sealed interface JreProbeResult {
    data class Ok(val info: JreVersionInfo) : JreProbeResult
    data object Missing : JreProbeResult
    data class Partial(val missingFiles: List<String>) : JreProbeResult
    data class ExecFailed(val code: String, val detail: String) : JreProbeResult
    data class WrongVersion(val expected: Int, val actual: Int?) : JreProbeResult
    data object Not64Bit : JreProbeResult
    data object NoServerCompiler : JreProbeResult
}

/** 探测范围：当前所选 Profile 依赖的组件集合（无版本时 natives/assets 为空集）。 */
data class EnvScope(
    val jreMajors: Set<Int> = emptySet(),
    val nativesHashes: Set<String> = emptySet(),
    val assetsIndexes: Set<AssetsIndexRef> = emptySet(),
    /**
     * 「至少」语义的 JRE 槽位（方案 B：Java 21+ 轨实际安装 JRE25，探测接受 major ≥ 槽位值）。
     * 槽位语义：组件键仍为 jre:21（运行 Java 21 字节码所需的最低版本），安装目录 env/jre/21/arm64。
     */
    val jreAtLeastMajors: Set<Int> = emptySet(),
)

data class AssetsIndexRef(
    val profileId: String,
    val versionId: String,
    val indexId: String,
    val sha1: String,
)

/** 探测报告：各组件结论 + 合成 EnvState（设计文档 §2.4 规则）。 */
data class EnvProbeReport(
    val jres: Map<Int, JreProbeResult>,
    val natives: Map<String, ComponentProbeResult>,
    val assetsIndexes: Map<String, ComponentProbeResult>,
) {
    val state: EnvState
        get() {
            val results: List<ComponentProbeResult> = buildList {
                jres.values.forEach { add(it.asComponentResult()) }
                addAll(natives.values)
                addAll(assetsIndexes.values)
            }
            return when {
                results.any { it is ComponentProbeResult.Corrupted } -> EnvState.CORRUPTED
                results.any { it is ComponentProbeResult.Partial } -> EnvState.PARTIAL
                results.any { it is ComponentProbeResult.Missing } -> EnvState.MISSING
                else -> EnvState.READY
            }
        }

    val issues: List<EnvIssue>
        get() = buildList {
            jres.forEach { (major, r) -> addAll(r.asIssues("jre:$major")) }
            natives.forEach { (hash, r) -> addAll(r.asIssues("natives:$hash")) }
            assetsIndexes.forEach { (_, r) -> addAll(r.asIssues("assets")) }
        }

    private fun JreProbeResult.asComponentResult(): ComponentProbeResult = when (this) {
        is JreProbeResult.Ok -> ComponentProbeResult.Ok
        JreProbeResult.Missing -> ComponentProbeResult.Missing("JRE 目录不存在")
        is JreProbeResult.Partial -> ComponentProbeResult.Partial(missingFiles)
        is JreProbeResult.ExecFailed -> ComponentProbeResult.Corrupted(listOf(EnvIssue("jre_exec_failed", detail)))
        is JreProbeResult.WrongVersion -> ComponentProbeResult.Corrupted(listOf(EnvIssue("jre_wrong_version", "期望 $expected 实际 $actual")))
        JreProbeResult.Not64Bit -> ComponentProbeResult.Corrupted(listOf(EnvIssue("jre_not_64bit", "非 64 位 JRE")))
        JreProbeResult.NoServerCompiler -> ComponentProbeResult.Corrupted(listOf(EnvIssue("jre_no_server", "缺少 Server Compiler")))
    }

    private fun JreProbeResult.asIssues(component: String): List<EnvIssue> =
        (asComponentResult() as? ComponentProbeResult.Corrupted)?.issues.orEmpty()
            .map { it.copy(code = "${it.code}@$component") }

    private fun ComponentProbeResult.asIssues(component: String): List<EnvIssue> = when (this) {
        is ComponentProbeResult.Corrupted -> issues.map { it.copy(code = "${it.code}@$component") }
        else -> emptyList()
    }
}

/**
 * 环境探测器（设计文档 §2）：JRE 静态+动态双检、natives 标记+抽查、assets 索引 SHA-1。
 * 事实源是文件与运行态；DB/标记只是缓存，不一致时以探测为准回写（幂等自愈）。
 */
class EnvProbe(private val executor: ProcessExecutor) {

    fun probe(scope: EnvScope, layout: EnvLayout): EnvProbeReport = EnvProbeReport(
        jres = scope.jreMajors.associateWith { javaMajor ->
            probeJre(layout, javaMajor, atLeast = javaMajor in scope.jreAtLeastMajors)
        },
        natives = scope.nativesHashes.associateWith { probeNatives(layout, it) },
        assetsIndexes = scope.assetsIndexes.associate { ref ->
            ref.indexId to probeAssetsIndex(layout, ref.profileId, ref.versionId, ref.indexId, ref.sha1)
        },
    )

    /**
     * [atLeast]：接受 major ≥ javaMajor（Java 21+ 槽位装 JRE25 的场景）；默认要求精确匹配。
     */
    fun probeJre(layout: EnvLayout, javaMajor: Int, atLeast: Boolean = false): JreProbeResult {
        val dir: File = layout.jreDir(javaMajor)
        val javaBin: File = layout.jreJavaBinary(javaMajor)
        val required: List<File> = listOf(javaBin, layout.jreReleaseFile(javaMajor))
        // libjvm/libjli 用候选（Pojav 经典布局 + 架构子目录变体）
        val serverLibs: List<File> = layout.jreServerLibCandidates(javaMajor)
        val jliLibs: List<File> = layout.jreJliLibCandidates(javaMajor)
        val missingNames: List<String> = required.filterNot { it.isFile && it.length() > 0L }.map { it.name } +
            (if (serverLibs.none { it.isFile && it.length() > 0L }) listOf("libjvm.so") else emptyList()) +
            (if (jliLibs.none { it.isFile && it.length() > 0L }) listOf("libjli.so") else emptyList())

        if (!javaBin.exists()) {
            return if (dir.exists()) JreProbeResult.Partial(missingNames) else JreProbeResult.Missing
        }
        if (missingNames.isNotEmpty()) return JreProbeResult.Partial(missingNames)

        val result: ExecResult = executor.execute(
            command = listOf(javaBin.absolutePath, "-version"),
            env = mapOf(
                "LD_LIBRARY_PATH" to layout.jreLibPathCandidates(javaMajor),
                "HOME" to dir.absolutePath,
                "TMPDIR" to dir.absolutePath,
            ),
            workingDir = dir,
            timeoutMs = TIMEOUT_MS,
        )
        if (result.timedOut) return JreProbeResult.ExecFailed("timeout", "java -version 超时（${TIMEOUT_MS}ms）")
        val info: JreVersionInfo = JreVersionParser.parse(result.output)
            ?: return JreProbeResult.ExecFailed("parse", "无法解析版本输出: " + result.output.take(200))
        return when {
            info.major != javaMajor && !(atLeast && info.major > javaMajor) ->
                JreProbeResult.WrongVersion(javaMajor, info.major)
            info.bits == JreVersionInfo.Bits.BITS_32 -> JreProbeResult.Not64Bit
            // 设计决策：位宽不明确即视为风险（宁可重下，不可误启动）
            info.bits == JreVersionInfo.Bits.UNKNOWN -> JreProbeResult.Not64Bit
            info.vm != JreVersionInfo.VmType.SERVER -> JreProbeResult.NoServerCompiler
            result.exitCode != 0 -> JreProbeResult.ExecFailed("exit_${result.exitCode}", result.output.take(200))
            else -> JreProbeResult.Ok(info)
        }
    }

    fun probeNatives(layout: EnvLayout, nativesHash: String): ComponentProbeResult {
        val dir: File = layout.nativesDir(nativesHash)
        if (!dir.exists()) return ComponentProbeResult.Missing("natives 目录不存在")
        if (!layout.nativesMarker(nativesHash).isFile) {
            return ComponentProbeResult.Partial(listOf("缺少 .complete 标记"))
        }
        val entries: List<File> = dir.listFiles()?.filterNot { it.name == ".complete" }.orEmpty()
        if (entries.isEmpty()) return ComponentProbeResult.Partial(listOf("目录为空"))
        val hasValidSo: Boolean = entries.any { it.isFile && it.name.endsWith(".so") && it.length() > 0L }
        if (!hasValidSo) {
            return ComponentProbeResult.Corrupted(listOf(EnvIssue("natives_no_so", "无有效 .so 文件")))
        }
        return ComponentProbeResult.Ok
    }

    fun probeAssetsIndex(layout: EnvLayout, profileId: String, versionId: String, indexId: String, expectedSha1: String): ComponentProbeResult {
        val indexFile: File = layout.assetsIndexFile(profileId, versionId, indexId)
        if (!indexFile.isFile) return ComponentProbeResult.Missing("assets 索引文件不存在")
        if (indexFile.length() == 0L) {
            return ComponentProbeResult.Corrupted(listOf(EnvIssue("assets_index_empty", "索引文件为空")))
        }
        val actual: String = sha1Hex(indexFile)
        if (!actual.equals(expectedSha1, ignoreCase = true)) {
            return ComponentProbeResult.Corrupted(listOf(EnvIssue("assets_index_sha1_mismatch", "索引 SHA-1 不匹配")))
        }
        return ComponentProbeResult.Ok
    }

    companion object {
        const val TIMEOUT_MS: Long = 15_000L

        /** 流式计算文件 SHA-1（16 进制小写），大文件不占内存。 */
        fun sha1Hex(file: File): String {
            val digest: MessageDigest = MessageDigest.getInstance("SHA-1")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read: Int = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
