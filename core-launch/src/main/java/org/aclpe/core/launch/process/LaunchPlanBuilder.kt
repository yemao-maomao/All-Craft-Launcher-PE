package org.aclpe.core.launch.process

import org.aclpe.core.common.result.AppError
import org.aclpe.core.common.result.AppResult
import org.aclpe.core.env.probe.EnvLayout
import org.aclpe.core.launch.args.LaunchArgsBuilder
import org.aclpe.core.launch.model.LaunchContext

/**
 * 启动计划装配：LaunchContext + EnvLayout → LaunchCommand。
 * 环境变量（Android 上运行 JRE 的必需注入）：
 * - LD_LIBRARY_PATH：JRE lib 候选目录（Pojav 布局 + 架构变体，与 EnvProbe 同一常量源）；
 * - ANDROID_DATA/ANDROID_ROOT：JVM 需要的最小 Android 运行环境；
 * - HOME/TMPDIR：游戏与 JVM 的临时/配置目录。
 */
class LaunchPlanBuilder(private val layout: EnvLayout) {

    fun build(ctx: LaunchContext): AppResult<LaunchCommand> = try {
        val javaBinary: java.io.File = layout.jreJavaBinary(ctx.javaMajor)
        if (!javaBinary.isFile) {
            return AppResult.Err(AppError.Env("jre_binary_missing", "java 二进制不存在: ${javaBinary.absolutePath}"))
        }
        AppResult.Ok(
            LaunchCommand(
                javaBinary = javaBinary,
                jvmArgs = LaunchArgsBuilder.buildJvmArgs(ctx),
                gameArgs = LaunchArgsBuilder.buildGameArgs(ctx),
                env = mapOf(
                    "JAVA_HOME" to layout.jreDir(ctx.javaMajor).absolutePath,
                    // 加 natives 目录：让 LWJGL 能 dlopen gl4es(libGL.so.1) 与 lwjgl natives
                    "LD_LIBRARY_PATH" to (layout.jreLibPathCandidates(ctx.javaMajor) + ":" + ctx.nativesDir.absolutePath),
                    "ANDROID_DATA" to layout.root.absolutePath,
                    "ANDROID_ROOT" to "/system",
                    "HOME" to ctx.gameDir.absolutePath,
                    "TMPDIR" to ctx.gameDir.absolutePath,
                ),
                workingDir = ctx.gameDir,
            )
        )
    } catch (t: Throwable) {
        AppResult.Err(AppError.Unknown(cause = t))
    }
}
