package org.aclpe.core.download.install

import org.aclpe.core.common.result.AppError
import org.aclpe.core.common.result.AppResult
import org.aclpe.core.env.probe.EnvLayout
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream

/** Windows 鏉€杞壂鎻忎細鐬€佸崰鐢ㄧ洰褰曞彞鏌勶細rename 閲嶈瘯鏈€澶?[attempts] 娆★紙姣忔 100ms锛夈€?*/
private fun renameWithRetry(from: File, to: File, attempts: Int = 3): Boolean {
    repeat(attempts) { i ->
        if (from.renameTo(to)) return true
        if (i < attempts - 1) {
            try {
                Thread.sleep(100L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    }
    return false
}

/**
 * tar.xz 瑙ｅ帇锛歑Z 鈫?tar 鈫?鏂囦欢锛屽甫 zip-slip 闃叉姢涓庨《灞傜洰褰曞墺绂伙紙Pojav 鏋勫缓 tar 鍐呭惈椤跺眰鐩綍锛夈€? */
object TarXzExtractor {

    /** 瑙ｅ帇 IO 缂撳啿锛?KB 榛樿鍦ㄦ墜鏈轰笂瑙?55MB xz 澶參锛堝疄娴嬬摱棰堬級锛?4KB 鏄捐憲鎻愰€熴€?*/
    private const val IO_BUFFER: Int = 64 * 1024

    fun extract(tarXz: File, destDir: File): AppResult<Unit> = try {
        val root: File = destDir.apply { mkdirs() }
        XZInputStream(BufferedInputStream(FileInputStream(tarXz), IO_BUFFER)).use { xz ->
            TarArchiveInputStream(xz, IO_BUFFER).use { tar ->
                var stripPrefix: String? = null
                val symlinks: MutableList<Pair<String, String>> = mutableListOf()
                val buffer: ByteArray = ByteArray(IO_BUFFER)
                while (true) {
                    val entry: TarArchiveEntry = tar.nextEntry ?: break
                    val name: String = entry.name
                    if (stripPrefix == null) {
                        stripPrefix = name.substringBefore('/').let { if (it != name) it else null }
                    }
                    val relative: String = if (stripPrefix != null) name.removePrefix("$stripPrefix/") else name
                    if (relative.isEmpty()) continue
                    // zip-slip 闃叉姢锛氭嫆缁濈粷瀵硅矾寰勪笌 .. 绌胯秺
                    if (relative.startsWith("/") || relative.split('/').any { it == ".." }) {
                        return AppResult.Err(AppError.Io("zip_slip", "闈炴硶 tar 鏉＄洰: $name"))
                    }
                    when {
                        entry.isDirectory -> File(root, relative).mkdirs()
                        // 娉ㄦ剰椤哄簭锛氱鍙烽摼鎺ユ潯鐩悓鏃舵弧瓒?isFile 涓?isSymbolicLink锛坈ommons-compress 璇箟锛夛紝
                        // 蹇呴』鍏堝垽 isSymbolicLink锛屽惁鍒欎細鎸夋櫘閫氭枃浠跺啓鍑?0 瀛楄妭
                        entry.isSymbolicLink -> symlinks += relative to entry.linkName
                        entry.isFile -> {
                            val target: File = File(root, relative)
                            target.parentFile?.mkdirs()
                            target.outputStream().buffered(IO_BUFFER).use { out ->
                                while (true) {
                                    val n: Int = tar.read(buffer)
                                    if (n < 0) break
                                    out.write(buffer, 0, n)
                                }
                            }
                        }
                        else -> Unit   // 鍏朵綑鏉＄洰绫诲瀷璺宠繃
                    }
                }
                // 二遍：归档内符号链接（如 lib/server/libjvm.so → ../aarch64/server/libjvm.so）复制目标内容（Windows 无建链权限）
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



    /** 閾炬帴璺緞鐩稿鍏剁埗鐩綍瑙ｆ瀽鐩爣锛涜秺鍑烘牴鐩綍鎴栦粛鍚?.. 鍒欐嫆缁濄€?*/
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
 * JRE 瀹夎鍣紙璁捐鏂囨。 搂5.2 鍘熷瓙鎬э級锛? * staging 瑙ｅ帇 鈫?闈欐€佸啋鐑燂紙bin/java + libjvm锛夆啋 鍘熷瓙 rename 鏇挎崲 鈫?鍐?.build 灏辩华鏍囪銆? * 浠讳綍涓€姝ュけ璐ワ細staging 娓呯悊锛屾棫鐩綍淇濇寔鍘熺姸锛堝崌绾у満鏅笉鍙楀奖鍝嶏級銆? */
object JreInstaller {

    /** 单调时钟（毫秒）：JVM 单测与 Android 通用（不引 android.os.SystemClock）。 */
    private fun elapsedRealtime(): Long = System.nanoTime() / 1_000_000L

    fun install(tarXz: File, layout: EnvLayout, javaMajor: Int, buildId: String, sha256: String): AppResult<File> {
        val startedAt: Long = elapsedRealtime()
        val target: File = layout.jreDir(javaMajor)
        val parent: File = target.parentFile ?: return AppResult.Err(AppError.Io("no_parent"))
        parent.mkdirs()
        val staging: File = File(parent, ".staging-$javaMajor-${System.currentTimeMillis()}")
        Timber.d("jre install: begin major=%d archive=%d bytes", javaMajor, tarXz.length())

        val extract: AppResult<Unit> = TarXzExtractor.extract(tarXz, staging)
        if (extract is AppResult.Err) {
            staging.deleteRecursively()
            Timber.w("jre install: extract failed major=%d err=%s", javaMajor, extract.error?.code)
            return extract
        }
        Timber.d("jre install: extract done in %dms major=%d",
            elapsedRealtime() - startedAt, javaMajor)

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

        // 原子替换：target → backup；staging → target；失败回滚（Windows 杀软瞬态句柄 rename 重试）
        val backup: File = File(parent, "arm64.old-${System.currentTimeMillis()}")
        if (target.exists() && !renameWithRetry(target, backup)) {
            staging.deleteRecursively()
            return AppResult.Err(AppError.Io("swap_failed", "旧目录移出失败"))
        }
        if (!renameWithRetry(staging, target)) {
            if (backup.exists()) renameWithRetry(backup, target)   // 鍥炴粴
            return AppResult.Err(AppError.Io("swap_failed", "staging 灏变綅澶辫触"))
        }
        backup.deleteRecursively()

        // 就绪标记（.build）：与 DB 双写互证；sha256 规范为小写
        val marker: File = layout.jreBuildMarker(javaMajor)
        try {
            marker.writeText("buildId=$buildId\nsha256=${sha256.lowercase()}\njavaVersion=$javaMajor\n")
        } catch (t: Throwable) {
            return AppResult.Err(AppError.Io("marker_failed", cause = t))
        }
        Timber.i("jre install: READY major=%d total %dms", javaMajor, elapsedRealtime() - startedAt)
        return AppResult.Ok(target)
    }
}
