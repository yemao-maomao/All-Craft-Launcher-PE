package org.aclpe.aclpe.core

import android.app.Application
import org.aclpe.core.common.log.LogRedactor
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃捕获：默认关闭（硬性约束「崩溃捕获开关（默认关）」）。
 * 开启后：未捕获异常先写 files/logs/crash-<时间>.txt（已脱敏），再交给系统默认处理器（不吞崩溃）。
 * 注意：崩溃路径为「尽力而为」的文件写入，允许在崩溃线程同步 I/O（全项目唯一豁免点）。
 */
object CrashCatcher {

    @Volatile
    private var enabled: Boolean = false

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun install(app: Application) {
        val default: Thread.UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread: Thread, throwable: Throwable ->
            if (enabled) {
                runCatching { writeCrashReport(app, thread, throwable) }
            }
            default?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrashReport(app: Application, thread: Thread, throwable: Throwable) {
        val dir: File = File(app.filesDir, "logs").apply { mkdirs() }
        val file: File = File(dir, "crash-${System.currentTimeMillis()}.txt")
        val time: String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val content: String = buildString {
            append("time=").append(time).append('\n')
            append("thread=").append(thread.name).append('\n')
            append(LogRedactor().redact(throwable.stackTraceToString()))
            append('\n')
        }
        file.writeText(content)
    }
}
