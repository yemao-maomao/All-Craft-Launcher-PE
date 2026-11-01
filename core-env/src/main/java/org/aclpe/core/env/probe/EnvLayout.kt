package org.aclpe.core.env.probe

import java.io.File

/**
 * 存储布局契约（用户规格强制）：所有环境相关路径由此派生，禁止散落硬编码。
 * 以 filesRoot = /data/user/0/<pkg>/files 为根：
 *   env/jre/<javaVer>/arm64/            JRE 多版本共存
 *   env/natives/<hash>/                 全局池化 native 库
 *   runtimes/<profileId>/versions/<id>/assets/indexes/<indexId>.json  assets 索引
 *   downloads/tmp/<component>.part|.staging  下载中间产物（启动时幂等清理）
 */
class EnvLayout(private val filesRoot: File) {

    /** files 根（ANDROID_DATA 等环境注入用）。 */
    val root: File get() = filesRoot

    val envRoot: File = File(filesRoot, "env")
    val downloadsTmp: File = File(filesRoot, "downloads/tmp")
    val logsDir: File = File(filesRoot, "logs")

    fun jreDir(javaMajor: Int): File = File(File(envRoot, "jre/$javaMajor"), "arm64")

    fun nativesDir(hash: String): File = File(envRoot, "natives/$hash")

    fun jreJavaBinary(javaMajor: Int): File = File(jreDir(javaMajor), "bin/java")

    /** Pojav 构建实际布局：lib/server/libjvm.so（无架构子目录）。 */
    fun jreServerLib(javaMajor: Int): File = File(jreDir(javaMajor), "lib/server/libjvm.so")

    fun jreJliLib(javaMajor: Int): File = File(jreDir(javaMajor), "lib/libjli.so")

    /** 兼容候选：部分构建带架构子目录（aarch64 / arm64）。 */
    fun jreServerLibCandidates(javaMajor: Int): List<File> = listOf(
        jreServerLib(javaMajor),
        File(jreDir(javaMajor), "lib/aarch64/server/libjvm.so"),
        File(jreDir(javaMajor), "lib/arm64/server/libjvm.so"),
    )

    fun jreJliLibCandidates(javaMajor: Int): List<File> = listOf(
        jreJliLib(javaMajor),
        File(jreDir(javaMajor), "lib/aarch64/libjli.so"),
        File(jreDir(javaMajor), "lib/arm64/libjli.so"),
    )

    /** LD_LIBRARY_PATH 候选目录（动态探测与 Step 3 启动共用）。 */
    fun jreLibPathCandidates(javaMajor: Int): String {
        val dir: File = jreDir(javaMajor)
        return listOf("lib", "lib/server", "lib/aarch64", "lib/aarch64/server", "lib/arm64", "lib/arm64/server")
            .joinToString(":") { File(dir, it).absolutePath }
    }

    fun jreReleaseFile(javaMajor: Int): File = File(jreDir(javaMajor), "release")

    /** JRE 就绪标记（.build）：内容 buildId=... sha256=... javaVersion=...，与 DB 双写互证。 */
    fun jreBuildMarker(javaMajor: Int): File = File(jreDir(javaMajor), ".build")

    fun nativesMarker(hash: String): File = File(nativesDir(hash), ".complete")

    fun assetsIndexFile(profileId: String, versionId: String, indexId: String): File =
        File(filesRoot, "runtimes/$profileId/versions/$versionId/assets/indexes/$indexId.json")
}
