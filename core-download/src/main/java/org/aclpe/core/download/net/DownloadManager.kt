package org.aclpe.core.download.net

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.aclpe.core.common.result.AppError
import org.aclpe.core.common.result.AppResult
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

data class DownloadProgress(val downloadedBytes: Long, val totalBytes: Long?)

data class DownloadRequest(
    val id: String,
    val url: String,
    val mirrorUrls: List<String> = emptyList(),
    val destFile: File,
    val sha1: String? = null,
    val sha256: String? = null,
    val sizeBytes: Long? = null,
    val chunkCount: Int = DEFAULT_CHUNK_COUNT,
    val onProgress: (DownloadProgress) -> Unit = {},
) {
    companion object {
        const val DEFAULT_CHUNK_COUNT: Int = 4
    }
}

/**
 * OkHttp 下载传输层（设计文档 §5.1 / 用户规格 Step 2）：
 * - 分片并发：新任务按 [DownloadRequest.chunkCount] 个分段并行下载（RandomAccessFile 定点写入）；
 * - Range 续传：断点从 part 文件长度继续（If-Range 语义简化：服务端 206 追加、200 视为内容变更从头、
 *   416 视为已完成直接进入校验）；进程被杀后由 Room 任务状态 + part 文件恢复；
 * - 重试退避：1s / 4s / 16s，镜像按序轮换，全部镜像耗尽仍失败 → Err(network)；
 * - 校验：整包流式 SHA-1 / SHA-256 比对（失败删 part 重下，计入重试）；
 * - 原子落地：part 校验通过后 rename 到最终文件；
 * - 可取消：协程取消即中止（配合 [kotlinx.coroutines.ensureActive] 检查点）。
 */
class DownloadManager(
    private val client: OkHttpClient,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun download(request: DownloadRequest): AppResult<File> = withContext(io) {
        val candidates: List<String> = (listOf(request.url) + request.mirrorUrls).distinct()
        var lastError: AppError? = null

        for (mirror in candidates) {
            var attempt: Int = 0
            while (attempt < MAX_ATTEMPTS_PER_MIRROR) {
                attempt++
                coroutineContext.ensureActive()
                val result: AppResult<File> = tryDownload(request, mirror)
                when (result) {
                    is AppResult.Ok -> return@withContext result
                    is AppResult.Err -> {
                        lastError = result.error
                        if (result.error == AppError.Cancelled) return@withContext result
                    }
                }
                if (attempt < MAX_ATTEMPTS_PER_MIRROR) {
                    delay(BACKOFF_MS[attempt - 1])   // 1s / 4s / 16s
                }
            }
        }
        AppResult.Err(
            lastError ?: AppError.Network("download_exhausted", "全部镜像与重试耗尽")
        )
    }

    private suspend fun tryDownload(request: DownloadRequest, mirrorUrl: String): AppResult<File> {
        val partFile: File = partOf(request.destFile)
        partFile.parentFile?.mkdirs()

        val result: AppResult<Unit> = transfer(request, mirrorUrl, partFile)
        if (result is AppResult.Err) return result

        // 校验
        val verify: AppResult<Unit> = verifyHashes(partFile, request)
        if (verify is AppResult.Err) {
            partFile.delete()   // 内容不符，删掉重下
            return verify
        }

        // 原子落地
        request.destFile.delete()
        if (!partFile.renameTo(request.destFile)) {
            return AppResult.Err(AppError.Io("rename_failed", "part → dest rename 失败"))
        }
        return AppResult.Ok(request.destFile)
    }

    private suspend fun transfer(request: DownloadRequest, url: String, partFile: File): AppResult<Unit> {
        val existing: Long = if (partFile.isFile) partFile.length() else 0L

        // 服务端总大小与续传起点（IO 异常收敛为 Err：沿调用链上抛会击穿调度器协程——审计高危#2）
        val probe: Pair<Long, Boolean> = try {
            probeSize(url, existing)  // (total, resumed)
        } catch (t: Throwable) {
            coroutineContext.ensureActive()
            return AppResult.Err(AppError.Network("probe_failed", cause = t))
        } ?: return AppResult.Err(AppError.Network("head_failed", "无法确定资源大小"))
        val (total, resumed) = probe

        val fresh: Boolean = existing == 0L
        return if (fresh && total > 0L && request.chunkCount > 1) {
            downloadChunked(request, url, partFile, total)
        } else {
            downloadSingle(request, url, partFile, existing, resumed)
        }
    }

    /** Range 探针：确定 total 与是否可续传。 */
    private fun probeSize(url: String, existing: Long): Pair<Long, Boolean>? {
        val probeReq: Request = Request.Builder().url(url)
            .header("Range", "bytes=0-0")
            .build()
        return client.newCall(probeReq).execute().use { resp: Response ->
            when {
                resp.code == 206 -> {
                    val total: Long = resp.header("Content-Range")
                        ?.substringAfter('/')
                        ?.takeIf { it != "*" }
                        ?.toLongOrNull()
                        ?: return null
                    val resumed: Boolean = existing in 1 until total
                    total to resumed
                }
                resp.code == 200 -> {
                    // 不支持 Range：total 从 Content-Length（对 fresh 下载；resume 场景会重来）
                    val total: Long = resp.body?.contentLength() ?: -1L
                    total to false
                }
                else -> null
            }
        }
    }

    private suspend fun downloadSingle(
        request: DownloadRequest,
        url: String,
        partFile: File,
        existing: Long,
        resumed: Boolean,
    ): AppResult<Unit> = try {
        val builder: Request.Builder = Request.Builder().url(url)
        if (resumed) builder.header("Range", "bytes=$existing-")
        client.newCall(builder.build()).execute().use { resp: Response ->
            coroutineContext.ensureActive()
            when {
                resp.code == 206 || resp.code == 200 -> {
                    if (resp.code == 200 && existing > 0L) {
                        // 服务端忽略 Range：内容可能已变更 → 从头重写
                        partFile.delete()
                    }
                    val body = resp.body ?: return AppResult.Err(AppError.Network("empty_body"))
                    RandomAccessFile(partFile, "rw").use { raf ->
                        raf.seek(if (resp.code == 206) existing else 0L)
                        val buffer = ByteArray(BUFFER_SIZE)
                        var written: Long = if (resp.code == 206) existing else 0L
                        body.byteStream().use { input ->
                            while (true) {
                                coroutineContext.ensureActive()
                                val read: Int = input.read(buffer)
                                if (read < 0) break
                                raf.write(buffer, 0, read)
                                written += read
                                request.onProgress(DownloadProgress(written, request.sizeBytes ?: body.contentLength()))
                            }
                        }
                    }
                    AppResult.Ok(Unit)
                }
                resp.code == 416 -> AppResult.Ok(Unit)   // Range 起点越界 = 已完整
                else -> AppResult.Err(AppError.Network("http_${resp.code}"))
            }
        }
    } catch (t: Throwable) {
        coroutineContext.ensureActive()
        AppResult.Err(AppError.Network("io_failed", cause = t))   // 审计高危#2：网络异常不得击穿调度器协程
    }

    /** 分片并发：仅对全新下载；任何分片失败整体降级为重试（下一轮走单流）。 */
    private suspend fun downloadChunked(
        request: DownloadRequest,
        url: String,
        partFile: File,
        total: Long,
    ): AppResult<Unit> {
        val chunkSize: Long = (total + request.chunkCount - 1) / request.chunkCount
        val segments: List<LongRange> = (0 until request.chunkCount).mapNotNull { i ->
            val start: Long = i * chunkSize
            if (start >= total) null else start until minOf(start + chunkSize, total)
        }
        val progress = ProgressCounter(request, total)
        return try {
            coroutineScope {
                segments.map { range ->
                    async {
                        coroutineContext.ensureActive()
                        downloadRange(url, partFile, range, progress)
                    }
                }.forEach { it.await() }
            }
            AppResult.Ok(Unit)
        } catch (t: Throwable) {
            coroutineContext.ensureActive()
            // 审计中危#15：分片失败保留已成功分片（断点续传价值），仅整体报错重试
            AppResult.Err(AppError.Network("chunk_failed", cause = t))
        }
    }

    private suspend fun downloadRange(url: String, partFile: File, range: LongRange, progress: ProgressCounter): Unit {
        val req: Request = Request.Builder().url(url)
            .header("Range", "bytes=${range.first}-${range.last}")
            .build()
        client.newCall(req).execute().use { resp: Response ->
            coroutineContext.ensureActive()
            if (resp.code != 206) throw IllegalStateException("分片请求非 206: ${resp.code}")
            val body = resp.body ?: throw IllegalStateException("分片响应空 body")
            RandomAccessFile(partFile, "rw").use { raf ->
                raf.seek(range.first)
                val buffer = ByteArray(BUFFER_SIZE)
                body.byteStream().use { input ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val read: Int = input.read(buffer)
                        if (read < 0) break
                        raf.write(buffer, 0, read)
                        progress.add(read)
                    }
                }
            }
        }
    }

    private fun verifyHashes(file: File, request: DownloadRequest): AppResult<Unit> {
        val sha1: MessageDigest? = request.sha1?.let { MessageDigest.getInstance("SHA-1") }
        val sha256: MessageDigest? = request.sha256?.let { MessageDigest.getInstance("SHA-256") }
        if (sha1 == null && sha256 == null) return AppResult.Ok(Unit)
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read: Int = input.read(buffer)
                if (read < 0) break
                sha1?.update(buffer, 0, read)
                sha256?.update(buffer, 0, read)
            }
        }
        sha1?.let {
            val actual: String = it.digest().joinToString("") { b -> "%02x".format(b) }
            if (!actual.equals(request.sha1, ignoreCase = true)) {
                return AppResult.Err(AppError.Io("sha1_mismatch", "SHA-1 不匹配: $actual"))
            }
        }
        sha256?.let {
            val actual: String = it.digest().joinToString("") { b -> "%02x".format(b) }
            if (!actual.equals(request.sha256, ignoreCase = true)) {
                return AppResult.Err(AppError.Io("sha256_mismatch", "SHA-256 不匹配: $actual"))
            }
        }
        return AppResult.Ok(Unit)
    }

    private class ProgressCounter(private val request: DownloadRequest, private val total: Long) {
        private var done: Long = 0L

        @Synchronized
        fun add(n: Int) {
            done += n
            // 与累加同一把锁内回调：保证观察者看到的进度序列单调递增
            request.onProgress(DownloadProgress(done, total))
        }
    }

    companion object {
        const val BUFFER_SIZE: Int = 64 * 1024
        const val MAX_ATTEMPTS_PER_MIRROR: Int = 3
        val BACKOFF_MS: LongArray = longArrayOf(1_000L, 4_000L, 16_000L)

        fun partOf(dest: File): File = File(dest.parentFile, dest.name + ".part")
    }
}
