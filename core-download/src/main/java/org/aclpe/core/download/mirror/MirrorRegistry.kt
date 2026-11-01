package org.aclpe.core.download.mirror

/**
 * 镜像源：官方 + BMCLAPI + Gitee。
 * Gitee 当前无稳定公共 MC 元数据端点（社区镜像多为私有仓库、随时失效），
 * 框架已预留位置；后续锁定稳定端点后启用（详见设计文档已知问题）。
 */
enum class MirrorId {
    OFFICIAL, BMCLAPI, GITEE,
}

data class MirrorSource(
    val id: MirrorId,
    val displayName: String,
    val enabled: Boolean,
)

object MirrorRegistry {

    val sources: List<MirrorSource> = listOf(
        MirrorSource(MirrorId.OFFICIAL, "官方 Mojang", enabled = true),
        MirrorSource(MirrorId.BMCLAPI, "BMCLAPI 镜像", enabled = true),
        MirrorSource(MirrorId.GITEE, "Gitee 镜像（端点待定）", enabled = false),
    )

    fun enabledIds(): List<MirrorId> = sources.filter { it.enabled }.map { it.id }

    /** 将 URL 重写为指定镜像的等价地址；无法重写时原样返回（该镜像不承载此资源）。 */
    fun rewrite(url: String, mirror: MirrorId): String = when (mirror) {
        MirrorId.OFFICIAL -> url
        MirrorId.BMCLAPI -> rewriteBmclapi(url)
        MirrorId.GITEE -> url
    }

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

        else -> url
    }
}
