package org.aclpe.core.launch.process

import org.aclpe.core.common.result.AppError
import org.aclpe.core.common.result.AppResult
import java.io.File

/** 游戏进程句柄。 */
interface GameProcess {
    /** 阻塞等待退出，返回退出码。 */
    fun waitFor(): Int

    fun destroy()
}

/**
 * 游戏进程拉起契约（骨架期 JVM 测试不直接覆盖真实拉起——linux JRE 无法在本机执行；
 * 真机验证步骤见设计文档）。
 */
interface GameProcessStarter {
    fun start(command: LaunchCommand, stdoutLog: File, stderrLog: File): AppResult<GameProcess>
}

/**
 * 真实实现：ProcessBuilder 拉起 java；
 * 环境变量注入 JAVA_HOME/LD_LIBRARY_PATH/ANDROID_DATA/ANDROID_ROOT/TMPDIR/HOME；
 * stdout/stderr 双落盘（append，崩溃前最后输出可查）。
 */
class RealGameProcessStarter : GameProcessStarter {

    override fun start(command: LaunchCommand, stdoutLog: File, stderrLog: File): AppResult<GameProcess> =
        try {
            stdoutLog.parentFile?.mkdirs()
            stderrLog.parentFile?.mkdirs()
            val builder: ProcessBuilder = ProcessBuilder(listOf(command.javaBinary.absolutePath) + command.fullArgs)
            builder.environment().putAll(command.env)
            builder.directory(command.workingDir)
            builder.redirectOutput(stdoutLog)
            builder.redirectError(stderrLog)
            val process: Process = builder.start()
            AppResult.Ok(object : GameProcess {
                override fun waitFor(): Int = process.waitFor()
                override fun destroy() {
                    process.destroy()
                }
            })
        } catch (t: Throwable) {
            AppResult.Err(AppError.Io("game_process_start_failed", cause = t))
        }
}
