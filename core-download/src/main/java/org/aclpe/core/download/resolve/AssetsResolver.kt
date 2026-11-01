package org.aclpe.core.download.resolve

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.aclpe.core.common.result.AppError
import org.aclpe.core.common.result.AppResult
import org.aclpe.core.download.mirror.MirrorId
import org.aclpe.core.download.mirror.MirrorRegistry

@Serializable
data class AssetsIndex(
    val objects: Map<String, AssetsObject> = emptyMap(),
)

@Serializable
data class AssetsObject(
    val hash: String,
    val size: Long,
)

/** 单个资源下载任务（name 为相对 objects 根的路径，hash 决定下载地址）。 */
data class AssetsFile(
    val name: String,
    val hash: String,
    val size: Long,
)

/**
 * assets 解析：索引 JSON 解析 + 分片（分批并发下载）+ 地址生成。
 * 每个文件 SHA-1 = 内容 hash（地址即 hash），下载后逐文件校验。
 */
object AssetsResolver {

    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parseIndex(text: String): AppResult<AssetsIndex> =
        try {
            AppResult.Ok(json.decodeFromString<AssetsIndex>(text))
        } catch (t: Throwable) {
            AppResult.Err(AppError.Parse("assets_index", cause = t))
        }

    /** 分片：每片不超过 [maxFilesPerShard] 个文件；shard 边界决定断点续传粒度。 */
    fun shard(objects: Map<String, AssetsObject>, maxFilesPerShard: Int = 64): List<List<AssetsFile>> =
        objects.entries
            .map { (name, obj) -> AssetsFile(name = name, hash = obj.hash, size = obj.size) }
            .chunked(maxFilesPerShard)

    /** 资源地址：官方 <2 位前缀>/<hash>，经镜像重写。 */
    fun objectUrl(hash: String, mirror: MirrorId): String {
        val prefix: String = hash.take(2)
        return MirrorRegistry.rewrite("https://resources.download.minecraft.net/$prefix/$hash", mirror)
    }

    /** 索引地址：version json 里 assetIndex.url 直接经镜像重写。 */
    fun indexUrl(versionJsonUrl: String, mirror: MirrorId): String = MirrorRegistry.rewrite(versionJsonUrl, mirror)
}
