package org.aclpe.aclpe.core.env

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.aclpe.aclpe.core.AppSettings
import org.aclpe.core.account.AccountRepository
import org.aclpe.core.account.skin.SkinCache
import org.aclpe.core.account.store.AccountStore
import org.aclpe.core.account.store.EncryptedTokenStore
import org.aclpe.core.account.store.FileAccountStore
import org.aclpe.core.download.install.JreInstaller
import org.aclpe.core.download.net.DownloadManager
import org.aclpe.core.download.net.DownloadRequest
import org.aclpe.core.download.sched.DownloadSchedulerImpl
import org.aclpe.core.env.bootstrap.DownloadScheduler
import org.aclpe.core.env.bootstrap.EnvBootstrap
import org.aclpe.core.env.bootstrap.EnvDownloadSpec
import org.aclpe.core.env.bootstrap.EnvDownloadTask
import org.aclpe.core.env.probe.EnvLayout
import org.aclpe.core.env.probe.EnvProbe
import org.aclpe.core.env.probe.RealProcessExecutor
import org.aclpe.core.env.store.EnvStore
import org.aclpe.core.env.store.EnvItemEntity
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

    /** 全局设置（暴露给 ViewModel：选中版本、主题等）。 */
    val appSettings: AppSettings = appSettings

    val layout: EnvLayout = EnvLayout(context.filesDir)

    val store: EnvStore = RoomEnvStore(LauncherDatabase.get(context).envItemDao())

    val probe: EnvProbe = EnvProbe(RealProcessExecutor())

    /**
     * 共享 OkHttp 实例（审计低危#26：单连接池/线程池；下载与皮肤复用）。
     *
     * 下载吞吐优化（2026-11-03，真机"像单线程"根因）：
     * - OkHttp 默认 [okhttp3.Dispatcher.maxRequestsPerHost] = **5**：无论上层信号量设多大，
     *   同一域名实际最多 5 个请求在飞 —— 这正是"一次只有一个在下载"的观感来源；
     * - 默认 [okhttp3.ConnectionPool] 仅保留 5 个空闲连接：并发一高就反复 TLS 建连；
     * - 小文件下载由延迟主导（实测单个 781B 文件 0.87-3.9s），必须靠并发重叠 RTT 提速。
     */
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .dispatcher(
            okhttp3.Dispatcher().apply {
                maxRequests = MAX_REQUESTS
                maxRequestsPerHost = MAX_REQUESTS_PER_HOST
            }
        )
        .connectionPool(okhttp3.ConnectionPool(MAX_IDLE_CONNECTIONS, 5, TimeUnit.MINUTES))
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

    /** FCL 式运行时部署（2026-11-02 用户指令重写）：线性流程，完成后状态直达 UI。
     *  Java 8 槽位走网络下载（选择性安装，不打包进 APK）。 */
    val runtimeInstaller: SimpleRuntimeInstaller =
        SimpleRuntimeInstaller.create(
            context, appScope,
            networkProvider = { slot: Int ->
                val component: String = EnvItemEntity.jre(slot)
                val spec: EnvDownloadSpec? = JreManifest.specs()[component]
                if (spec == null) {
                    null
                } else {
                    val tmp: File = File(layout.downloadsTmp, "jre-$slot.tar.xz")
                    manager.download(
                        DownloadRequest(
                            id = "jre:$slot",
                            url = spec.url,
                            destFile = tmp,
                            sha1 = spec.sha256,
                            sizeBytes = spec.sizeBytes,
                        )
                    ).getOrNull()
                }
            },
        )

    /** 原版下载镜像（设置项 → 每次安装开始时取一次）：切换后版本下载立即使用所选镜像。 */
    private val originMirrorProvider: suspend () -> org.aclpe.core.download.mirror.MirrorId = {
        org.aclpe.core.download.mirror.MirrorRegistry.fromName(
            settingsStores.readLauncher(PROFILE_ID).mirrorId
        )
    }

    /** 社区资源镜像（设置项 → 每次请求时取一次）：切换后社区搜索立即使用所选镜像。 */
    private val communityMirrorProvider: suspend () -> String = {
        val mirror: org.aclpe.core.download.mirror.MirrorId =
            org.aclpe.core.download.mirror.MirrorRegistry.fromName(
                settingsStores.readLauncher(PROFILE_ID).communityMirrorId
            )
        org.aclpe.core.download.mirror.MirrorRegistry.modrinthBaseUrl(mirror)
    }

    /** 社区资源（Modrinth）客户端：与下载共享 OkHttp 实例（基址随"社区镜像"设置动态变化）。 */
    val modrinthClient: org.aclpe.core.mods.modrinth.ModrinthClient =
        org.aclpe.core.mods.modrinth.ModrinthClient(httpClient, baseUrlProvider = communityMirrorProvider)

    /** 下载线程数（设置页滑块，1~32）：内存缓存，设置变化时后台同步，下载阶段实时读取。 */
    @Volatile
    private var currentDownloadThreads: Int = AppSettings.DEFAULT_DOWNLOAD_THREADS

    init {
        appScope.launch {
            appSettings.downloadThreads.collect { currentDownloadThreads = it }
        }
    }

    private val downloadThreadsProvider: () -> Int = { currentDownloadThreads }

    /** 版本安装流水线：manifest → json → jar → 库 → natives → assets → installed（镜像+线程数随设置）。 */
    val versionInstaller: org.aclpe.core.download.install.VersionInstaller =
        org.aclpe.core.download.install.VersionInstaller(
            httpClient, manager, appScope,
            originMirror = originMirrorProvider,
            downloadThreads = downloadThreadsProvider,
        )

    /** 模组加载器安装（Fabric/Quilt，为已有版本添加；镜像+线程数随设置）。 */
    val modLoaderInstaller: org.aclpe.core.download.loader.ModLoaderInstaller =
        org.aclpe.core.download.loader.ModLoaderInstaller(
            httpClient, manager, appScope,
            originMirror = originMirrorProvider,
            downloadThreads = downloadThreadsProvider,
        )

    /** 版本安装目录（与启动链路 runtimes/<profileId> 一致）。 */
    val versionsGameDir: File = File(context.filesDir, "runtimes/default")

    /** 组件（模组等）安装根目录：游戏根 mods + 各版本 mods（版本隔离）。 */
    fun modsDir(versionId: String?): File =
        if (versionId.isNullOrBlank()) File(versionsGameDir, "mods")
        else File(versionsGameDir, "versions/$versionId/mods")

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

    companion object {
        /** 骨架期固定 Profile 标识（与 AccountViewModel.PROFILE_ID 一致）。 */
        const val PROFILE_ID: String = "default"

        /** 全局最大并发请求（OkHttp 默认 64 即此值，显式声明便于调优）。 */
        const val MAX_REQUESTS: Int = 64

        /** 单域名最大并发请求：**默认 5 → 32**（下载提速的关键项）。 */
        const val MAX_REQUESTS_PER_HOST: Int = 32

        /** 空闲连接池容量：**默认 5 → 32**（避免并发高时反复 TLS 建连）。 */
        const val MAX_IDLE_CONNECTIONS: Int = 32
    }

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
