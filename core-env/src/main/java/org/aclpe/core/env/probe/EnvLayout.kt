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

    val envRoot: File = File(filesRoot, "env")
    val downloadsTmp: File = File(filesRoot, "downloads/tmp")
    val logsDir: File = File(filesRoot, "logs")

    fun jreDir(javaMajor: Int): File = File(File(envRoot, "jre/$javaMajor"), "arm64")

    fun nativesDir(hash: String): File = File(envRoot, "natives/$hash")

    fun jreJavaBinary(javaMajor: Int): File = File(jreDir(javaMajor), "bin/java")

    fun jreServerLib(javaMajor: Int): File = File(jreDir(javaMajor), "lib/arm64/server/libjvm.so")

    fun jreJliLib(javaMajor: Int): File = File(jreDir(javaMajor), "lib/arm64/libjli.so")

    fun jreReleaseFile(javaMajor: Int): File = File(jreDir(javaMajor), "release")

    /** JRE 就绪标记（.build）：内容 buildId=... sha256=... javaVersion=...，与 DB 双写互证。 */
    fun jreBuildMarker(javaMajor: Int): File = File(jreDir(javaMajor), ".build")

    fun nativesMarker(hash: String): File = File(nativesDir(hash), ".complete")

    fun assetsIndexFile(profileId: String, versionId: String, indexId: String): File =
        File(filesRoot, "runtimes/$profileId/versions/$versionId/assets/indexes/$indexId.json")
}
