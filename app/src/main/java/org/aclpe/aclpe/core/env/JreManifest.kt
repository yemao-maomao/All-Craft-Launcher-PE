package org.aclpe.aclpe.core.env

import org.aclpe.core.env.bootstrap.EnvDownloadSpec
import org.aclpe.core.env.store.EnvItemEntity

/**
 * JRE 下载清单（应用内置 pin，用户决策 Q2）：
 * - JRE17：Pojav GitHub Release 不可变资产，sha256 已本机实测 pin（38,065,004 B）。
 * - JRE21：**待用户决策**。调研结论（2026-11-01）：PojavLauncherTeam 组织已归档、其 JRE21
 *   Actions 工件过期不可下载；社区镜像站（next.foldcraftlauncher.cn 系）已用 JRE22/25 替换 JRE21。
 *   可选：a) 自托管一份（发布 sha256）；b) 用社区镜像 JRE22/25 替代（Java 21+ 字节码均可运行，需用户接受偏差）；
 *   c) AngelAuraMC 继任 fork 的 Actions 工件（CI 依赖，需实测）。
 */
object JreManifest {

    const val JRE17_URL: String =
        "https://github.com/PojavLauncherTeam/android-openjdk-build-multiarch/releases/download/jre17-ec28559/jre17-arm64-20210825-release.tar.xz"
    const val JRE17_SHA256: String = "C64583AC2E0EC8857E43456FA9ADCF482C6A8E454A7133173BF15692D2478B8D"
    const val JRE17_SIZE: Long = 38_065_004L
    const val JRE17_BUILD_ID: String = "pojav-20210825"

    /** 当前可用下载规格（缺失的组件 EnvBootstrap 会置 errorCode=no_download_spec，UI 诚实提示）。 */
    fun specs(): Map<String, EnvDownloadSpec> = buildMap {
        put(EnvItemEntity.jre(17), EnvDownloadSpec(JRE17_URL, JRE17_SHA256, JRE17_SIZE))
        // put(EnvItemEntity.jre(21), ...)  // JRE21 下载源待用户决策后补入
    }

    fun buildIdFor(component: String): String? = when (component) {
        EnvItemEntity.jre(17) -> JRE17_BUILD_ID
        else -> null
    }

    fun javaMajorFor(component: String): Int? = when (component) {
        EnvItemEntity.jre(17) -> 17
        EnvItemEntity.jre(21) -> 21
        else -> null
    }
}
