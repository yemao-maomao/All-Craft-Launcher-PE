package org.aclpe.core.launch.process

import java.io.File

/** 拼装完成的启动命令（进程拉起所需的一切；不含 token 明文日志——脱敏门面兜底）。 */
data class LaunchCommand(
    val javaBinary: File,
    val jvmArgs: List<String>,
    val gameArgs: List<String>,
    val env: Map<String, String>,
    val workingDir: File,
) {
    /** ProcessBuilder 用完整参数：jvm 参数 + 游戏参数。 */
    val fullArgs: List<String> get() = jvmArgs + gameArgs
}
