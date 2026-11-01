package org.aclpe.core.launch.model

import org.aclpe.core.download.json.VersionJson
import org.aclpe.core.download.resolve.ResolvedLibrary
import java.io.File

/** 账号信息（Step 4 契约落地前的过渡形态；offline 模式 token 为空串）。 */
data class LaunchAuth(
    val name: String,
    val uuid: String,
    val accessToken: String = "",
    val userType: String = "legacy",
    val xuid: String = "",
)

/** 内存档位（启动器侧配置，不进 options.txt）。 */
enum class MemoryTier(val heapMb: Int) {
    LIGHT(1024),
    MEDIUM(2048),
    HIGH(3072),
    EXTREME(4096),
}

/** 启动上下文：模板 + 平台信息 + 账号 + 资源路径（classpath 顺序敏感）。 */
data class LaunchContext(
    val version: VersionJson,
    val auth: LaunchAuth,
    val gameDir: File,
    val assetsDir: File,
    val assetsIndexId: String,
    val nativesDir: File,
    val libraries: List<ResolvedLibrary>,
    val clientJar: File,
    /** 实际运行的 JRE major（决定 -Xlog 等版本相关参数；槽位 21 实际为 25 时传 25）。 */
    val javaMajor: Int,
    val memoryTier: MemoryTier = MemoryTier.MEDIUM,
    /** 设备可用内存（MB），-Xmx 不超过其一半。 */
    val deviceMemoryMb: Int = 2048,
    val launcherName: String = "ACL-PE",
    val launcherVersion: String = "0.1.0",
)
