package org.aclpe.core.download.json

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.aclpe.core.common.result.AppError
import org.aclpe.core.common.result.AppResult

// ---------------------------------------------------------------------------
// version_manifest_v2.json
// ---------------------------------------------------------------------------

@Serializable
data class VersionManifestV2(
    val latest: LatestVersions,
    val versions: List<ManifestVersion>,
)

@Serializable
data class LatestVersions(
    val release: String,
    val snapshot: String,
)

@Serializable
data class ManifestVersion(
    val id: String,
    val type: String,
    val url: String,
    val time: String,
    val releaseTime: String,
    val sha1: String? = null,
    val complianceLevel: Int = 0,
)

// ---------------------------------------------------------------------------
// versions/<id>/<id>.json（覆盖现代 arguments 与 1.8.9 遗留 minecraftArguments 两种形态）
// ---------------------------------------------------------------------------

@Serializable
data class VersionJson(
    val id: String,
    val mainClass: String,
    val type: String = "release",
    val arguments: Arguments? = null,
    val minecraftArguments: String? = null,
    val assetIndex: AssetIndexRef? = null,
    val assets: String? = null,
    val javaVersion: JavaVersionRef? = null,
    val libraries: List<Library> = emptyList(),
    val downloads: VersionDownloads? = null,
    val inheritsFrom: String? = null,
)

@Serializable
data class Arguments(
    val game: List<kotlinx.serialization.json.JsonElement> = emptyList(),
    val jvm: List<kotlinx.serialization.json.JsonElement> = emptyList(),
)

@Serializable
data class AssetIndexRef(
    val id: String,
    val sha1: String,
    val size: Long,
    val totalSize: Long,
    val url: String,
)

@Serializable
data class JavaVersionRef(
    val component: String,
    val majorVersion: Int,
)

@Serializable
data class VersionDownloads(
    val client: Artifact,
    val server: Artifact? = null,
)

/** rules 规则（ordered allow/disallow，见 RulesEngine）。 */
@Serializable
data class Rule(
    val action: String,
    val os: OsRule? = null,
    val features: Map<String, Boolean>? = null,
)

@Serializable
data class OsRule(
    val name: String? = null,
    val arch: String? = null,
    val version: String? = null,
)

@Serializable
data class Library(
    val name: String,
    val downloads: LibraryDownloads? = null,
    val rules: List<Rule> = emptyList(),
    val natives: Map<String, String>? = null,
    val extract: ExtractSpec? = null,
    /** 遗留：库自定基址（如 Forge maven），artifact.url 缺失时用于拼接。 */
    val url: String? = null,
)

@Serializable
data class LibraryDownloads(
    val artifact: Artifact? = null,
    val classifiers: Map<String, Artifact>? = null,
)

@Serializable
data class Artifact(
    /** 1.8.9 等老版本的 client 制品无 path 字段（只有 sha1/size/url）。 */
    val path: String? = null,
    val sha1: String,
    val size: Long,
    val url: String? = null,
)

@Serializable
data class ExtractSpec(
    val exclude: List<String> = emptyList(),
)

// ---------------------------------------------------------------------------
// 解析器（统一错误走 AppResult）
// ---------------------------------------------------------------------------

object VersionJsonParser {

    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parseManifestV2(text: String): AppResult<VersionManifestV2> =
        try {
            AppResult.Ok(json.decodeFromString<VersionManifestV2>(text.stripBom()))
        } catch (t: Throwable) {
            AppResult.Err(AppError.Parse("manifest_v2", cause = t))
        }

    fun parseVersionJson(text: String): AppResult<VersionJson> =
        try {
            AppResult.Ok(json.decodeFromString<VersionJson>(text.stripBom()))
        } catch (t: Throwable) {
            AppResult.Err(AppError.Parse("version_json", cause = t))
        }

    /** 下载/缓存文件可能带 UTF-8 BOM（Windows 工具链常见），防御性剥离。 */
    private fun String.stripBom(): String = removePrefix("\uFEFF")
}
