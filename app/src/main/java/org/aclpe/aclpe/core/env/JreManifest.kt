package org.aclpe.aclpe.core.env

import org.aclpe.core.env.bootstrap.EnvDownloadSpec
import org.aclpe.core.env.store.EnvItemEntity

/**
 * JRE 下载清单（应用内置 pin，用户决策 Q2；JRE21 槽位方案 B 已确认 2026-11-01）：
 * - JRE17：Pojav GitHub Release 不可变资产，sha256 本机实测 pin（38,065,004 B）。
 * - 「Java 21+」槽位（组件键 jre:21）：社区镜像 JRE25（Pojav 系 JRE21 已随上游组织归档不可下载；
 *   社区镜像站现提供 22/25）。JRE25 可运行 Java 21 字节码，sha256 本机实测 pin（37,751,348 B）。
 *   探测用 atLeast 语义（EnvScope.jreAtLeastMajors），安装目录仍为 env/jre/21/arm64（槽位目录）。
 *   已知风险：mcddos 为 http 且文件名不带日期（内容可被替换）——sha256 pin 兜底：替换即校验失败拒装。
 */
object JreManifest {

    const val JRE17_URL: String =
        "https://github.com/PojavLauncherTeam/android-openjdk-build-multiarch/releases/download/jre17-ec28559/jre17-arm64-20210825-release.tar.xz"
    const val JRE17_SHA256: String = "C64583AC2E0EC8857E43456FA9ADCF482C6A8E454A7133173BF15692D2478B8D"
    const val JRE17_SIZE: Long = 38_065_004L
    const val JRE17_BUILD_ID: String = "pojav-20210825"

    /** Java 21+ 槽位实际使用社区 JRE25（方案 B）。 */
    const val JRE25_URL: String = "http://oss.mcddos.top/fcl/jre/jre25-arm64.tar.xz"
    const val JRE25_SHA256: String = "94BCC9C654793F1B443BB6A0FC92A6DF0619C06CF7187236638BDAEF2EE9C4C2"
    const val JRE25_SIZE: Long = 37_751_348L
    const val JRE25_BUILD_ID: String = "mcddos-20260729"

    /** 当前可用下载规格（缺失的组件 EnvBootstrap 会置 errorCode=no_download_spec，UI 诚实提示）。 */
    fun specs(): Map<String, EnvDownloadSpec> = buildMap {
        put(EnvItemEntity.jre(17), EnvDownloadSpec(JRE17_URL, JRE17_SHA256, JRE17_SIZE))
        put(EnvItemEntity.jre(21), EnvDownloadSpec(JRE25_URL, JRE25_SHA256, JRE25_SIZE))
    }

    fun buildIdFor(component: String): String? = when (component) {
        EnvItemEntity.jre(17) -> JRE17_BUILD_ID
        EnvItemEntity.jre(21) -> JRE25_BUILD_ID
        else -> null
    }

    /** 槽位 javaMajor：jre:21 槽位实际安装 JRE25，探测用 atLeast（见 EnvScope）。 */
    fun javaMajorFor(component: String): Int? = when (component) {
        EnvItemEntity.jre(17) -> 17
        EnvItemEntity.jre(21) -> 21
        else -> null
    }

    /** atLeast 语义的槽位集合（Java 21+ 槽位装 JRE25）。 */
    val atLeastMajors: Set<Int> = setOf(21)
}
