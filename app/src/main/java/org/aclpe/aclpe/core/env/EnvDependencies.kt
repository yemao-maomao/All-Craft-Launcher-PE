package org.aclpe.aclpe.core.env

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import org.aclpe.aclpe.core.AppSettings
import org.aclpe.core.download.install.JreInstaller
import org.aclpe.core.download.net.DownloadManager
import org.aclpe.core.download.sched.DownloadSchedulerImpl
import org.aclpe.core.env.bootstrap.EnvBootstrap
import org.aclpe.core.env.bootstrap.EnvDownloadTask
import org.aclpe.core.env.probe.EnvLayout
import org.aclpe.core.env.probe.EnvProbe
import org.aclpe.core.env.probe.RealProcessExecutor
import org.aclpe.core.env.store.EnvStore
import org.aclpe.core.env.store.LauncherDatabase
import org.aclpe.core.env.store.RoomEnvStore
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * core-env + core-download 依赖装配（Step 2 起为真实链路）：
 * DownloadManager（OkHttp 分片/续传/校验）→ DownloadSchedulerImpl（真实实现）→
 * 下载成功回调 → JreInstaller 原子安装 → 重新探测置 READY。
 * 网络策略：默认仅 Wi-Fi（用户决策 Q4），可经 AppSettings 切换。
 */
class EnvDependencies(context: Context, appScope: CoroutineScope, appSettings: AppSettings) {

    val layout: EnvLayout = EnvLayout(context.filesDir)

    val store: EnvStore = RoomEnvStore(LauncherDatabase.get(context).envItemDao())

    val probe: EnvProbe = EnvProbe(RealProcessExecutor())

    val manager: DownloadManager = DownloadManager(
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(600, TimeUnit.SECONDS)
            .build()
    )

    val scheduler: DownloadSchedulerImpl = DownloadSchedulerImpl(
        manager = manager,
        taskDao = LauncherDatabase.get(context).downloadTaskDao(),
        scope = appScope,
        downloadTmpDir = layout.downloadsTmp,
        networkPolicy = wifiPolicy(context, appSettings),
        onSucceeded = { task: EnvDownloadTask, file: File -> installJre(task, file) },
    )

    val bootstrap: EnvBootstrap = EnvBootstrap(probe, scheduler, store, layout)

    /** 网络策略：仅 Wi-Fi 开关（AppSettings，默认开）→ 当前网络是否 Wi-Fi/以太网。 */
    private fun wifiPolicy(context: Context, appSettings: AppSettings): suspend () -> Boolean = {
        val wifiOnly: Boolean = appSettings.downloadWifiOnly.first()
        if (!wifiOnly) true else isOnWifi(context)
    }

    private fun isOnWifi(context: Context): Boolean {
        val cm: ConnectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps: NetworkCapabilities = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /** 下载成功 → 原子安装（JreInstaller）→ 标记；随后由 UI 重新探测置 READY。 */
    private suspend fun installJre(task: EnvDownloadTask, file: File) {
        val major: Int = JreManifest.javaMajorFor(task.component) ?: return
        val buildId: String = JreManifest.buildIdFor(task.component) ?: "unknown"
        val result = JreInstaller.install(file, layout, major, buildId, task.sha256)
        if (result.isErr) {
            // 安装失败：EnvProbe 下次探测会判 CORRUPTED 并触发重下（自愈链路）
            Timber.w("JRE 安装失败: %s", result.errorOrNull()?.code)
        }
    }
}
