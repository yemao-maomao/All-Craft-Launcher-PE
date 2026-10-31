package org.aclpe.core.env.probe

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

data class ExecResult(
    val exitCode: Int,
    val output: String,
    val timedOut: Boolean,
)

/**
 * 进程执行抽象：探测需要真实拉起 `java -version`（Android 上通过 execve 运行 JRE 二进制）。
 * 真实实现在 Android 上运行；JVM 单测注入伪造实现断言解析结果。
 */
interface ProcessExecutor {
    /**
     * 执行命令并捕获 stdout+stderr（合并输出），超时则强杀并置 [ExecResult.timedOut]。
     * 阻塞调用：由调用方置于 IO 调度器。
     */
    fun execute(command: List<String>, env: Map<String, String>, workingDir: File?, timeoutMs: Long): ExecResult
}

/** Android 真实实现（ProcessBuilder）。 */
class RealProcessExecutor : ProcessExecutor {

    override fun execute(command: List<String>, env: Map<String, String>, workingDir: File?, timeoutMs: Long): ExecResult {
        val process: Process = ProcessBuilder(command).apply {
            environment().putAll(env)
            if (workingDir != null) directory(workingDir)
        }.start()

        val stdout: StringBuilder = StringBuilder()
        val stderr: StringBuilder = StringBuilder()
        val outThread: Thread = thread(name = "exec-stdout") {
            process.inputStream.bufferedReader().forEachLine { stdout.appendLine(it) }
        }
        val errThread: Thread = thread(name = "exec-stderr") {
            process.errorStream.bufferedReader().forEachLine { stderr.appendLine(it) }
        }

        val finished: Boolean = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) process.destroyForcibly()
        outThread.join(2_000L)
        errThread.join(2_000L)
        process.inputStream.close()
        process.errorStream.close()

        return ExecResult(
            exitCode = if (finished) process.exitValue() else -1,
            output = stdout.toString() + stderr.toString(),
            timedOut = !finished,
        )
    }
}
