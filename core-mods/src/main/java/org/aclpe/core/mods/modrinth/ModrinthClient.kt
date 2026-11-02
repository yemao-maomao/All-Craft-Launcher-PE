package org.aclpe.core.mods.modrinth

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.aclpe.core.common.result.AppError
import org.aclpe.core.common.result.AppResult

/**
 * Modrinth 客户端（labrinth API v2）：
 * - search：关键词 + 游戏版本 + 加载器 facet 过滤，仅 client 侧可用项目；
 * - versions：项目版本列表（游戏版本/加载器过滤）；
 * - 纯 GET + JSON，全部 https；UA 按 Modrinth 规范携带（含项目仓库便于联络）。
 * 部署复用 core-download 的 DownloadManager（sha1/sha512 校验、断点续传）。
 */
class ModrinthClient(
    private val client: OkHttpClient,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /**
     * API 基址来源（2026-11-02 接入设置"社区镜像"）：**每次请求时取一次**，
     * 用户切换社区镜像后，搜索/版本列表直接走所选镜像（MCMirror：`mod.mcimirror.top/modrinth/v2`）。
     * 文件下载（cdn.modrinth.com）实测该镜像不反代，仍走官方——UI 有说明。
     */
    private val baseUrlProvider: suspend () -> String = { ModrinthApi.BASE_URL },
) {

    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }

    private suspend fun base(): String = baseUrlProvider().ifBlank { ModrinthApi.BASE_URL }

    /**
     * 搜索项目：[query] 关键词；[gameVersion] 如 "1.20.1"；[loader] 如 "fabric"。
     * facet 语义：client_side=required/optional 且 loaders/versions 命中。
     */
    suspend fun search(
        query: String,
        gameVersion: String? = null,
        loader: String? = null,
        offset: Int = 0,
        limit: Int = 20,
    ): AppResult<ModrinthSearchResult> {
        val urlBuilder: okhttp3.HttpUrl.Builder = "${base()}/search".toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("limit", limit.toString())
        val facets: MutableList<String> = mutableListOf()
        facets += "[\"project_type:mod\"]"
        // client_side 支持（required 或 optional 均可用）
        facets += "[\"client_side:required\", \"client_side:optional\"]"
        if (gameVersion != null) facets += "[\"versions:$gameVersion\"]"
        if (loader != null) facets += "[\"categories:$loader\"]"
        urlBuilder.addQueryParameter("facets", "[${facets.joinToString(",")}]")
        return get(urlBuilder.build(), ModrinthSearchResult.serializer())
    }

    /** 项目版本列表（按游戏版本/加载器过滤，最新在前——服务端默认按 date_published desc）。 */
    suspend fun versions(
        projectIdOrSlug: String,
        gameVersion: String? = null,
        loader: String? = null,
    ): AppResult<ModrinthVersionsResponse> {
        val urlBuilder: okhttp3.HttpUrl.Builder =
            "${base()}/project/$projectIdOrSlug/version".toHttpUrl().newBuilder()
        if (gameVersion != null) urlBuilder.addQueryParameter("game_versions", "[\"$gameVersion\"]")
        if (loader != null) urlBuilder.addQueryParameter("loaders", "[\"$loader\"]")
        return get(urlBuilder.build(), ModrinthVersionsResponse.serializer())
    }

    private suspend fun <T> get(
        url: okhttp3.HttpUrl,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): AppResult<T> = withContext(io) {
        try {
            val request: Request = Request.Builder()
                .url(url)
                .header("User-Agent", ModrinthApi.USER_AGENT)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext AppResult.Err(AppError.Network("modrinth_http_${response.code}"))
                }
                val body: String = response.body?.string()
                    ?: return@withContext AppResult.Err(AppError.Network("modrinth_empty_body"))
                AppResult.Ok(json.decodeFromString(serializer, body))
            }
        } catch (t: Throwable) {
            AppResult.Err(AppError.Network("modrinth_request_failed", cause = t))
        }
    }
}
