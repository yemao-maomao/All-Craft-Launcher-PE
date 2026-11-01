package org.aclpe.core.account.skin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.aclpe.core.account.model.AuthSession
import org.aclpe.core.common.result.AppError
import org.aclpe.core.common.result.AppResult
import java.io.File

/** 皮肤获取结果。 */
data class SkinResult(
    val file: File,
    val fromCache: Boolean,
)

/**
 * 皮肤缓存（ETag）：
 * - skinUrl 为 null（离线账户）→ null（UI 显示无皮肤占位）；
 * - 请求带 If-None-Match → 304 用本地缓存；200 更新图片与 ETag；
 * - 落盘：files/cache/skins/<uuid>.png + <uuid>.etag。
 * 契约级可测（MockWebServer）；真机网络行为与 JVM 一致。
 */
class SkinCache(
    private val client: OkHttpClient,
    private val cacheDir: File,
) {

    suspend fun getSkin(session: AuthSession, skinUrl: String?): AppResult<SkinResult?> = withContext(Dispatchers.IO) {
        if (skinUrl == null) return@withContext AppResult.Ok(null)
        try {
            cacheDir.mkdirs()
            val png: File = File(cacheDir, "${session.account.uuid}.png")
            val etagFile: File = File(cacheDir, "${session.account.uuid}.etag")
            val storedEtag: String? = if (etagFile.isFile) etagFile.readText().trim().takeIf { it.isNotEmpty() } else null

            val builder: Request.Builder = Request.Builder().url(skinUrl)
            if (storedEtag != null && png.isFile) builder.header("If-None-Match", storedEtag)

            client.newCall(builder.build()).execute().use { response ->
                when (response.code) {
                    304 -> AppResult.Ok(SkinResult(file = png, fromCache = true))
                    200 -> {
                        response.body?.byteStream()?.use { input ->
                            png.outputStream().use { out -> input.copyTo(out) }
                        } ?: return@withContext AppResult.Err(AppError.Network("empty_skin_body"))
                        response.header("ETag")?.let { etagFile.writeText(it) }
                        AppResult.Ok(SkinResult(file = png, fromCache = false))
                    }
                    else -> AppResult.Err(AppError.Network("http_${response.code}"))
                }
            }
        } catch (t: Throwable) {
            AppResult.Err(AppError.Network("skin_fetch_failed", cause = t))
        }
    }
}
