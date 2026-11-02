package org.aclpe.aclpe.core.env

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import org.aclpe.aclpe.core.AppSettings
import org.aclpe.core.account.AccountRepository
import org.aclpe.core.account.skin.SkinCache
import org.aclpe.core.account.store.AccountStore
import org.aclpe.core.account.store.EncryptedTokenStore
import org.aclpe.core.account.store.FileAccountStore
import org.aclpe.core.download.install.JreInstaller
import org.aclpe.core.download.net.DownloadManager
import org.aclpe.core.download.sched.DownloadSchedulerImpl
import org.aclpe.core.env.bootstrap.DownloadScheduler
import org.aclpe.core.env.bootstrap.EnvBootstrap
import org.aclpe.core.env.bootstrap.EnvDownloadSpec
import org.aclpe.core.env.bootstrap.EnvDownloadTask
import org.aclpe.core.env.probe.EnvLayout
import org.aclpe.core.env.probe.EnvProbe
import org.aclpe.core.env.probe.RealProcessExecutor
import org.aclpe.core.env.store.EnvStore
import org.aclpe.core.env.store.LauncherDatabase
import org.aclpe.core.env.store.RoomEnvStore
import org.aclpe.core.env.store.VersionDao
import org.aclpe.core.launch.process.GameProcessStarter
import org.aclpe.core.launch.process.LaunchPlanBuilder
import org.aclpe.core.launch.process.RealGameProcessStarter
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

    /** 共享 OkHttp 实例（审计低危#26：单连接池/线程池；下载与皮肤复用）。 */
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .build()

    val manager: DownloadManager = DownloadManager(httpClient)

    val scheduler: DownloadScheduler = AssetFirstScheduler(
        delegate = DownloadSchedulerImpl(
            manager = manager,
            taskDao = LauncherDatabase.get(context).downloadTaskDao(),
            scope = appScope,
            downloadTmpDir = layout.downloadsTmp,
            networkPolicy = wifiPolicy(context, appSettings),
            onSucceeded = { task: EnvDownloadTask, file: File -> installJre(task, file) },
            // 审计高危#3：任务终态回写 env_items，消除「QUEUED 永久死区」
            // （成功 → 等探测收敛 READY 由 HomeViewModel 观察者触发；失败 → FAILED 用户可见可重试）
            onTerminal = { component: String, succeeded: Boolean, errorCode: String? ->
                runCatching {
                    val current: org.aclpe.core.env.store.EnvItemEntity? = store.get(component)
                    if (current != null && succeeded.not()) {
                        store.upsert(current.copy(state = org.aclpe.core.env.fsm.EnvItemState.FAILED.name, errorCode = errorCode))
                    }
                }
            },
        ),
        scope = appScope,
        assetSource = AssetJreCache(context)::fileFor,
        installer = { component: String, file: File ->
            val spec: EnvDownloadSpec? = JreManifest.specs()[component]
            if (spec != null) {
                installJre(
                    EnvDownloadTask(component = component, url = "", sha256 = spec.sha256, sizeBytes = spec.sizeBytes),
                    file,
                )
            }
        },
    )

    val bootstrap: EnvBootstrap = EnvBootstrap(probe, scheduler, store, layout)

    /** FCL 式运行时部署（2026-11-02 用户指令重写）：线性流程，完成后状态直达 UI。 */
    val runtimeInstaller: SimpleRuntimeInstaller =
        SimpleRuntimeInstaller.create(context, appScope)

    /** 社区资源（Modrinth）客户端：与下载共享 OkHttp 实例。 */
    val modrinthClient: org.aclpe.core.mods.modrinth.ModrinthClient =
        org.aclpe.core.mods.modrinth.ModrinthClient(httpClient)

    /** 版本安装流水线（V0.0.3 接入）：manifest → json → jar → 库 → natives → assets → installed。 */
    val versionInstaller: org.aclpe.core.download.install.VersionInstaller =
        org.aclpe.core.download.install.VersionInstaller(httpClient, manager, appScope)

    /** 版本安装目录（与启动链路 runtimes/<profileId> 一致）。 */
    val versionsGameDir: File = File(context.filesDir, "runtimes/default")

    // ---- Step 4：账号契约装配（离线账户 + 加密 token 存储 + 皮肤缓存） ----
    val accountRepository: AccountRepository = AccountRepository(
        store = FileAccountStore.create(context.filesDir) as AccountStore,
        tokenStore = EncryptedTokenStore(context.filesDir),
    )

    val skinCache: SkinCache = SkinCache(httpClient, File(context.filesDir, "cache/skins"))

    val versionDao: VersionDao = LauncherDatabase.get(context).versionDao()

    // ---- Step 5：设置存储（Profile 隔离 + 默认模板 + 触控方案） ----
    val settingsStores: org.aclpe.core.settings.store.SettingsStores =
        org.aclpe.core.settings.store.SettingsStores(context.filesDir)

    /** 游戏运行中禁止覆盖 options.txt（硬性约束）。 */
    val gameRunningCheck: () -> Boolean = { GameProcessRegistry.isRunning }

    // ---- Step 3：启动链路装配 ----
    val launchPlanBuilder: LaunchPlanBuilder = LaunchPlanBuilder(layout)

    val gameProcessStarter: GameProcessStarter = RealGameProcessStarter()

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
