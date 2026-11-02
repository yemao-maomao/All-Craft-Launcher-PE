package org.aclpe.core.download.mirror

/**
 * 镜像源（2026-11-02 扩展，全部经真机可用的实测端点）：
 * - OFFICIAL：Mojang 官方 / Modrinth 官方 / Fabric·Quilt 官方；
 * - BMCLAPI：原版资源反代（实测 0.2-0.5s）+ **Fabric Meta 反代**（实测 profile json 0.21s，官方 0.84s）；
 * - MCIMIRROR：Modrinth **API** 反代 `mod.mcimirror.top/modrinth/v2`（实测 0.27s；其不反代文件 CDN，文件仍走官方 cdn）；
 * - GITEE：预留（无稳定公共端点）。
 *
 * 用途分类：[MirrorKind.ORIGIN] 用于原版资源与加载器；[MirrorKind.COMMUNITY] 用于社区资源（Modrinth）。
 */
enum class MirrorId {
    OFFICIAL, BMCLAPI, MCIMIRROR, GITEE,
}

enum class MirrorKind { ORIGIN, COMMUNITY }

data class MirrorSource(
    val id: MirrorId,
    val displayName: String,
    val enabled: Boolean,
    val kind: MirrorKind,
)

object MirrorRegistry {

    val sources: List<MirrorSource> = listOf(
        MirrorSource(MirrorId.OFFICIAL, "官方源", enabled = true, kind = MirrorKind.ORIGIN),
        MirrorSource(MirrorId.BMCLAPI, "BMCLAPI 镜像", enabled = true, kind = MirrorKind.ORIGIN),
        MirrorSource(MirrorId.OFFICIAL, "Modrinth 官方", enabled = true, kind = MirrorKind.COMMUNITY),
        MirrorSource(MirrorId.MCIMIRROR, "MCMirror 镜像", enabled = true, kind = MirrorKind.COMMUNITY),
        MirrorSource(MirrorId.GITEE, "Gitee 镜像（端点待定）", enabled = false, kind = MirrorKind.ORIGIN),
    )

    fun enabledIds(): List<MirrorId> = sources.filter { it.enabled }.map { it.id }.distinct()

    /** 原版/加载器可选镜像（设置页"下载镜像"）。 */
    fun originMirrors(): List<MirrorSource> = sources.filter { it.enabled && it.kind == MirrorKind.ORIGIN }

    /** 社区资源可选镜像（设置页"社区镜像"）。 */
    fun communityMirrors(): List<MirrorSource> = sources.filter { it.enabled && it.kind == MirrorKind.COMMUNITY }

    fun fromName(raw: String?): MirrorId = MirrorId.entries.firstOrNull { it.name == raw } ?: MirrorId.OFFICIAL

    /** 将 URL 重写为指定镜像的等价地址；无法重写时原样返回（该镜像不承载此资源）。 */
    fun rewrite(url: String, mirror: MirrorId): String = when (mirror) {
        MirrorId.OFFICIAL -> url
        MirrorId.BMCLAPI -> rewriteBmclapi(url)
        MirrorId.MCIMIRROR -> rewriteMcimirror(url)
        MirrorId.GITEE -> url
    }

    /** BMCLAPI：原版元数据/下载域 + Fabric Meta 反代（Quilt 无镜像，实测 404 → 原样）。 */
    private fun rewriteBmclapi(url: String): String = when {
        url.startsWith("https://launchermeta.mojang.com") ->
            url.replaceFirst("https://launchermeta.mojang.com", "https://bmclapi2.bangbang93.com")

        url.startsWith("https://piston-meta.mojang.com") ->
            url.replaceFirst("https://piston-meta.mojang.com", "https://bmclapi2.bangbang93.com")

        url.startsWith("https://piston-data.mojang.com") ->
            url.replaceFirst("https://piston-data.mojang.com", "https://bmclapi2.bangbang93.com")

        url.startsWith("https://libraries.minecraft.net") ->
            url.replaceFirst("https://libraries.minecraft.net", "https://bmclapi2.bangbang93.com/maven")

        url.startsWith("https://resources.download.minecraft.net") ->
            url.replaceFirst("https://resources.download.minecraft.net", "https://bmclapi2.bangbang93.com/assets")

        url.startsWith("https://meta.fabricmc.net") ->
            url.replaceFirst("https://meta.fabricmc.net", "https://bmclapi2.bangbang93.com/fabric-meta")

        url.startsWith("https://maven.fabricmc.net") ->
            url.replaceFirst("https://maven.fabricmc.net", "https://bmclapi2.bangbang93.com/maven")

        else -> url
    }

    /** MCMirror：仅 Modrinth API 反代（实测其不承载文件 CDN，cdn.modrinth.com 原样）。 */
    private fun rewriteMcimirror(url: String): String = when {
        url.startsWith("https://api.modrinth.com/v2") ->
            url.replaceFirst("https://api.modrinth.com/v2", "https://mod.mcimirror.top/modrinth/v2")

        else -> url
    }

    /** Modrinth API 基址（社区客户端用；OFFICIAL 返回官方）。 */
    fun modrinthBaseUrl(mirror: MirrorId): String = when (mirror) {
        MirrorId.MCIMIRROR -> "https://mod.mcimirror.top/modrinth/v2"
        else -> "https://api.modrinth.com/v2"
    }
}