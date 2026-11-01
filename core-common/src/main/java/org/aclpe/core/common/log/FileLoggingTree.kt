package org.aclpe.core.common.log

import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件日志树：所有日志（含崩溃前最后动作）追加写入 `files/logs/launcher.log`。
 * - 大小轮转：超过 [maxFileBytes] 轮转为 .1/.2，保留 [keepFiles] 份；
 * - 写入前统一过 [LogRedactor]，token 绝不落盘；
 * - 写失败静默吞掉（runCatching）：日志系统绝不能拖垮业务；
 * - 不引用 android.util.Log（优先级用稳定常量 2..7），保证 JVM 单测可跑。
 *
 * open 仅为测试可桥接受保护的 [log]（见 FileLoggingTreeTest.TestableTree），
 * 生产代码不应继承本类。
 */
open class FileLoggingTree(
    logDir: File,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    private val keepFiles: Int = DEFAULT_KEEP_FILES,
    private val redactor: LogRedactor = LogRedactor(),
) : Timber.Tree() {

    private val logFile: File = File(logDir, LOG_FILE_NAME)
    private val lock: Any = Any()
    private val formatter: SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * 最低记录优先级（稳定常量：2=V 3=D 4=I 5=W 6=E，不引用 android.util.Log 以保 JVM 单测）。
     * 默认 INFO（4）：DEBUG 级埋点不落盘（性能与日志卫生）；
     * 开发者模式（设置页开关）把它降为 2 → 全量详尽日志（问题定位用）。
     * @Volatile：可在任意线程动态切换（ACLApplication 监听设置流）。
     */
    @Volatile
    var minPriority: Int = PRIORITY_INFO

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (priority < minPriority) return
        val level: String = when (priority) {
            2 -> "V"      // android.util.Log.VERBOSE
            3 -> "D"      // android.util.Log.DEBUG
            4 -> "I"      // android.util.Log.INFO
            5 -> "W"      // android.util.Log.WARN
            6, 7 -> "E"   // android.util.Log.ERROR / ASSERT
            else -> "?"
        }
        val line: StringBuilder = StringBuilder()
            .append(formatter.format(Date()))
            .append(' ').append(level).append('/').append(tag ?: "-").append(": ")
            .append(redactor.redact(message))
        if (t != null) {
            line.append('\n').append(redactor.redact(t.toString()))
        }
        line.append('\n')

        synchronized(lock) {
            runCatching {
                val parent: File? = logFile.parentFile
                if (parent == null || (!parent.exists() && !parent.mkdirs())) return@runCatching
                rotateIfNeeded()
                logFile.appendText(line.toString())
            }
        }
    }

    private fun rotateIfNeeded() {
        if (!logFile.exists() || logFile.length() <= maxFileBytes) return
        val dir: File = logFile.parentFile ?: return
        // 删除最老一份，逐级后移
        File(dir, "$LOG_FILE_NAME.${keepFiles - 1}").delete()
        for (i in keepFiles - 1 downTo 1) {
            val src: File = File(dir, "$LOG_FILE_NAME.${i - 1}")
            if (src.exists()) src.renameTo(File(dir, "$LOG_FILE_NAME.$i"))
        }
        logFile.renameTo(File(dir, "$LOG_FILE_NAME.1"))
    }

    companion object {
        const val LOG_FILE_NAME: String = "launcher.log"
        const val DEFAULT_MAX_FILE_BYTES: Long = 2L * 1024L * 1024L
        const val DEFAULT_KEEP_FILES: Int = 3

        /** 稳定优先级常量（与 android.util.Log 数值一致，但不引用它）。 */
        const val PRIORITY_VERBOSE: Int = 2
        const val PRIORITY_DEBUG: Int = 3
        const val PRIORITY_INFO: Int = 4
    }
}
