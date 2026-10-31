package org.aclpe.aclpe

import android.app.Application
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.aclpe.aclpe.core.AppSettings
import org.aclpe.aclpe.core.CrashCatcher
import org.aclpe.core.common.log.FileLoggingTree
import org.aclpe.core.common.log.LogRedactor
import timber.log.Timber
import java.io.File

/** 应用入口：日志初始化（文件双写 + 脱敏）、崩溃捕获（默认关）。 */
class ACLApplication : Application() {

    // 应用级作用域：只承载与 App 同生命周期的后台任务；UI 逻辑一律使用 ViewModel 作用域（禁 GlobalScope）。
    private val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        setupLogging()
        installCrashCatcher()
        Timber.i(
            "ACL-PE starting: version=%s build=%s abis=%s",
            BuildConfig.VERSION_NAME,
            BuildConfig.BUILD_TYPE,
            Build.SUPPORTED_ABIS.joinToString(","),
        )
    }

    private fun setupLogging() {
        // 文件双写（恒开）：files/logs/launcher.log，落盘前统一脱敏
        val logDir: File = File(filesDir, "logs").apply { mkdirs() }
        Timber.plant(FileLoggingTree(logDir))
        // 控制台输出（仅 debug）：同样过脱敏，避免调试时把 token 刷进 logcat
        if (BuildConfig.DEBUG) {
            val redactor: LogRedactor = LogRedactor()
            Timber.plant(object : Timber.DebugTree() {
                override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                    super.log(priority, tag, redactor.redact(message), t)
                }
            })
        }
    }

    private fun installCrashCatcher() {
        CrashCatcher.install(this)
        // 崩溃捕获开关默认关闭；异步读取 DataStore，读取期间保持默认关（安全默认）
        applicationScope.launch {
            CrashCatcher.setEnabled(AppSettings(this@ACLApplication).crashCaptureEnabled.first())
        }
    }
}
