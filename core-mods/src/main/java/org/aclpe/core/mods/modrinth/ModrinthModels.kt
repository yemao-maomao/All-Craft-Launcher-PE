package org.aclpe.core.mods.modrinth

import kotlinx.serialization.Serializable

/**
 * Modrinth API 模型（labrinth API v2，仅本模块用到的字段，忽略未知键）。
 * https://docs.modrinth.com/
 */
object ModrinthApi {
    const val BASE_URL: String = "https://api.modrinth.com/v2"
    const val USER_AGENT: String = "ACL-PE/0.0.4 (github.com/yemao-maomao/All-Craft-Launcher-PE)"
}

@Serializable
data class ModrinthSearchResult(
    val hits: List<ModrinthProject> = emptyList(),
)

@Serializable
data class ModrinthProject(
    val project_id: String? = null,
    val slug: String? = null,
    val title: String? = null,
    val description: String? = null,
    val project_type: String? = null,
    val icon_url: String? = null,
    val downloads: Long? = null,
    val follows: Long? = null,
    val categories: List<String> = emptyList(),
    /** facet 检索返回的 client_side/server_side 支持声明。 */
    val client_side: String? = null,
    val server_side: String? = null,
)

@Serializable
data class ModrinthVersionsResponse(
    val versions: List<ModrinthVersion> = emptyList(),
)

@Serializable
data class ModrinthVersion(
    val id: String? = null,
    val project_id: String? = null,
    val name: String? = null,
    val version_number: String? = null,
    val version_type: String? = null,
    /** 支持的游戏版本（如 "1.20.1"）。 */
    val game_versions: List<String> = emptyList(),
    /** 加载器（"fabric"、"forge"、"quilt"、"neoforge"、"minecraft"）。 */
    val loaders: List<String> = emptyList(),
    val date_published: String? = null,
    val downloads: Long? = null,
    val files: List<ModrinthFile> = emptyList(),
)

@Serializable
data class ModrinthFile(
    val url: String? = null,
    val filename: String? = null,
    val primary: Boolean? = null,
    val size: Long? = null,
    val sha1: String? = null,
    val sha512: String? = null,
    val file_type: String? = null,
)
