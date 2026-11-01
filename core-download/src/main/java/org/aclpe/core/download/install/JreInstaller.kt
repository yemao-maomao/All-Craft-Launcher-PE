package org.aclpe.core.download.install

import org.aclpe.core.common.result.AppError
import org.aclpe.core.common.result.AppResult
import org.aclpe.core.env.probe.EnvLayout
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream

/**
 * tar.xz 解压：XZ → tar → 文件，带 zip-slip 防护与顶层目录剥离（Pojav 构建 tar 内含顶层目录）。
 */
object TarXzExtractor {

    fun extract(tarXz: File, destDir: File): AppResult<Unit> = try {
        val root: File = destDir.apply { mkdirs() }
        XZInputStream(BufferedInputStream(FileInputStream(tarXz))).use { xz ->
            TarArchiveInputStream(xz).use { tar ->
                var stripPrefix: String? = null
                val symlinks: MutableList<Pair<String, String>> = mutableListOf()
                while (true) {
                    val entry: TarArchiveEntry = tar.nextEntry ?: break
                    val name: String = entry.name
                    if (stripPrefix == null) {
                        stripPrefix = name.substringBefore('/').let { if (it != name) it else null }
                    }
                    val relative: String = if (stripPrefix != null) name.removePrefix("$stripPrefix/") else name
                    if (relative.isEmpty()) continue
                    // zip-slip 防护：拒绝绝对路径与 .. 穿越
                    if (relative.startsWith("/") || relative.split('/').any { it == ".." }) {
                        return AppResult.Err(AppError.Io("zip_slip", "非法 tar 条目: $name"))
                    }
                    when {
                        entry.isDirectory -> File(root, relative).mkdirs()
                        // 注意顺序：符号链接条目同时满足 isFile 与 isSymbolicLink（commons-compress 语义），
                        // 必须先判 isSymbolicLink，否则会按普通文件写出 0 字节
                        entry.isSymbolicLink -> symlinks += relative to entry.linkName
                        entry.isFile -> {
                            val target: File = File(root, relative)
                            target.parentFile?.mkdirs()
                            target.outputStream().use { out -> tar.copyTo(out) }
                        }
                        else -> Unit   // 其余条目类型跳过
                    }
                }
                // 二遍：归档内符号链接（如 lib/server/libjvm.so → ../aarch64/server/libjvm.so）
                // 复制目标内容而非建链接：Windows 测试环境无建链权限，且 Android 上真实文件同档案内
                for ((linkPath, rawTarget) in symlinks) {
                    val normalized: String = normalizeWithinRoot(linkPath, rawTarget) ?: continue
                    val source: File = File(root, normalized)
                    if (source.isFile) {
                        val linkFile: File = File(root, linkPath)
                        linkFile.parentFile?.mkdirs()
                        source.copyTo(linkFile, overwrite = true)
                    }
                }
            }
        }
        AppResult.Ok(Unit)
    } catch (t: Throwable) {
        AppResult.Err(AppError.Io("extract_failed", cause = t))
    }

    /** 链接路径相对其父目录解析目标；越出根目录或仍含 .. 则拒绝。 */
    private fun normalizeWithinRoot(linkPath: String, rawTarget: String): String? {
        val parent: String = linkPath.substringBeforeLast('/', "")
        val joined: List<String> = (if (parent.isEmpty()) "" else parent + "/" + rawTarget).split('/')
        val stack: ArrayDeque<String> = ArrayDeque()
        for (part in joined) {
            when (part) {
                "", "." -> Unit
                ".." -> if (stack.isEmpty()) return null else stack.removeLast()
                else -> stack.addLast(part)
            }
        }
        return stack.joinToString("/")
    }
}

/**
 * JRE 安装器（设计文档 §5.2 原子性）：
 * staging 解压 → 静态冒烟（bin/java + libjvm）→ 原子 rename 替换 → 写 .build 就绪标记。
 * 任何一步失败：staging 清理，旧目录保持原状（升级场景不受影响）。
 */
object JreInstaller {

    fun install(tarXz: File, layout: EnvLayout, javaMajor: Int, buildId: String, sha256: String): AppResult<File> {
        val target: File = layout.jreDir(javaMajor)
        val parent: File = target.parentFile ?: return AppResult.Err(AppError.Io("no_parent"))
        parent.mkdirs()
        val staging: File = File(parent, ".staging-$javaMajor-${System.currentTimeMillis()}")

        val extract: AppResult<Unit> = TarXzExtractor.extract(tarXz, staging)
        if (extract is AppResult.Err) {
            staging.deleteRecursively()
            return extract
        }

        // 静态冒烟：文件全但跑不起来的情况由 EnvProbe 动态探测兜底（libjvm/libjli 用候选布局）
        val javaBin: File = File(staging, "bin/java")
        val serverLibOk: Boolean = listOf(
            File(staging, "lib/server/libjvm.so"),
            File(staging, "lib/aarch64/server/libjvm.so"),
            File(staging, "lib/arm64/server/libjvm.so"),
        ).any { it.isFile && it.length() > 0L }
        if (!javaBin.isFile || javaBin.length() == 0L || !serverLibOk) {
            staging.deleteRecursively()
            return AppResult.Err(AppError.Env("jre_smoke_failed", "解压后关键文件缺失"))
        }

        // 原子替换：target → backup；staging → target；失败回滚
        val backup: File = File(parent, "arm64.old-${System.currentTimeMillis()}")
        if (target.exists() && !target.renameTo(backup)) {
            staging.deleteRecursively()
            return AppResult.Err(AppError.Io("swap_failed", "旧目录移出失败"))
        }
        if (!staging.renameTo(target)) {
            if (backup.exists()) backup.renameTo(target)   // 回滚
            return AppResult.Err(AppError.Io("swap_failed", "staging 就位失败"))
        }
        backup.deleteRecursively()

        // 就绪标记（.build）：与 DB 双写互证；内容见设计文档 §1。sha256 规范为小写。
        val marker: File = layout.jreBuildMarker(javaMajor)
        try {
            marker.writeText("buildId=$buildId\nsha256=${sha256.lowercase()}\njavaVersion=$javaMajor\n")
        } catch (t: Throwable) {
            return AppResult.Err(AppError.Io("marker_failed", cause = t))
        }
        return AppResult.Ok(target)
    }
}
