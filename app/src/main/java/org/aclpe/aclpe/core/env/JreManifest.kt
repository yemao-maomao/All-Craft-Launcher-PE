package org.aclpe.aclpe.core.env

import org.aclpe.core.env.bootstrap.EnvDownloadSpec
import org.aclpe.core.env.store.EnvItemEntity

/**
 * JRE 下载清单（应用内置 pin，用户决策 Q2）。
 * 2026-11-01 提速整合（用户反馈默认下载慢）：两个槽位全部换国内社区镜像 mcddos
 * （实测 1.6MB/s+；GitHub 直连与常见 ghproxy 均不可用/过慢）：
 * - 「Java 17+」槽位（组件键 jre:17）：社区 JRE22（Java 22 兼容 Java 17 字节码，用户已批准同思路的方案 B）；
 * - 「Java 21+」槽位（组件键 jre:21）：社区 JRE25（同上）。
 * 探测用 atLeast 语义（EnvScope.jreAtLeastMajors），安装目录仍为槽位目录 env/jre/<17|21>/arm64。
 * 已知风险：mcddos 为 http 且文件名不带日期（内容可被替换）——sha256 pin 兜底：替换即校验失败拒装。
 */
object JreManifest {

    /**
     * Java 8 槽位（2026-11-05 新增：MC 1.16.5 及更早版本必需）。
     * **选择性安装**：默认不打包进 APK（[assetNameFor] 返回 null），由用户在启动器内手动触发网络下载。
     * 来源：PojavLauncherTeam/android-openjdk-build-multiarch `jre8-40df388`。
     */
    const val JRE8_URL: String = "https://github.com/PojavLauncherTeam/android-openjdk-build-multiarch/releases/download/jre8-40df388/jre8-arm64-20220811-release.tar.xz"
    const val JRE8_SHA256: String = "A60BD146D4340441D44B9AD384B801004A6471DAB5ABD0219008604695E0160C"
    const val JRE8_SIZE: Long = 27_509_232L
    const val JRE8_BUILD_ID: String = "pojav-jre8-20220811"

    /** Java 17+ 槽位实际使用社区 JRE22（方案 B·提速整合）。 */
    const val JRE17_SLOT_URL: String = "http://oss.mcddos.top/fcl/jre/jre22-arm64.tar.xz"
    const val JRE17_SLOT_SHA256: String = "96C5A2A5E99E5800738FEB1627A01D5F30712B1F8EED02296F57D6F2B71B9DB7"
    const val JRE17_SLOT_SIZE: Long = 54_974_776L
    const val JRE17_SLOT_BUILD_ID: String = "mcddos-jre22-20260729"

    /** Java 21+ 槽位实际使用社区 JRE25（方案 B；即"Java 25"，已打包进 APK）。 */
    const val JRE25_URL: String = "http://oss.mcddos.top/fcl/jre/jre25-arm64.tar.xz"
    const val JRE25_SHA256: String = "94BCC9C654793F1B443BB6A0FC92A6DF0619C06CF7187236638BDAEF2EE9C4C2"
    const val JRE25_SIZE: Long = 37_751_348L
    const val JRE25_BUILD_ID: String = "mcddos-20260729"

    /** 当前可用下载规格（缺失的组件 EnvBootstrap 会置 errorCode=no_download_spec，UI 诚实提示）。 */
    fun specs(): Map<String, EnvDownloadSpec> = buildMap {
        put(EnvItemEntity.jre(8), EnvDownloadSpec(JRE8_URL, JRE8_SHA256, JRE8_SIZE))
        put(EnvItemEntity.jre(17), EnvDownloadSpec(JRE17_SLOT_URL, JRE17_SLOT_SHA256, JRE17_SLOT_SIZE))
        put(EnvItemEntity.jre(21), EnvDownloadSpec(JRE25_URL, JRE25_SHA256, JRE25_SIZE))
    }

    fun buildIdFor(component: String): String? = when (component) {
        EnvItemEntity.jre(8) -> JRE8_BUILD_ID
        EnvItemEntity.jre(17) -> JRE17_SLOT_BUILD_ID
        EnvItemEntity.jre(21) -> JRE25_BUILD_ID
        else -> null
    }

    /** 槽位 javaMajor。 */
    fun javaMajorFor(component: String): Int? = when (component) {
        EnvItemEntity.jre(8) -> 8
        EnvItemEntity.jre(17) -> 17
        EnvItemEntity.jre(21) -> 21
        else -> null
    }

    /** 槽位集合：Java 8（选择性）/ 17+（JRE22）/ 21+（JRE25，即 Java 25）。 */
    val atLeastMajors: Set<Int> = setOf(8, 17, 21)

    /**
     * 内置 APK 资产名（参考 FCL：运行时打进 APK，环境补齐不走网络、秒级安装）。
     * - Java 8 槽位返回 null：**选择性安装，不打包进 APK**，走网络下载；
     * - 资产缺失时（未执行 fetch-jre-assets 的源码构建）自动回退网络下载，双路径都可用。
     */
    fun assetNameFor(component: String): String? = when (component) {
        EnvItemEntity.jre(17) -> "jre22-arm64.tar.xz"
        EnvItemEntity.jre(21) -> "jre25-arm64.tar.xz"
        else -> null   // Java 8 不打包，走网络下载
    }
}
