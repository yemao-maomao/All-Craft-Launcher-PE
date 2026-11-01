package org.aclpe.aclpe.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.aclpe.aclpe.ACLApplication
import org.aclpe.aclpe.core.env.EnvDependencies
import org.aclpe.aclpe.core.env.JreManifest
import org.aclpe.aclpe.ui.account.AccountViewModel
import org.aclpe.core.account.model.Account
import org.aclpe.core.account.model.AccountType
import org.aclpe.core.account.model.AuthSession
import org.aclpe.core.account.model.AuthTokens
import org.aclpe.core.download.json.VersionJson
import org.aclpe.core.download.json.VersionJsonParser
import org.aclpe.core.download.mirror.MirrorId
import org.aclpe.core.download.resolve.LibraryResolver
import org.aclpe.core.download.rules.PlatformContext
import org.aclpe.core.env.bootstrap.DownloadScheduler
import org.aclpe.core.env.bootstrap.DownloadTaskState
import org.aclpe.core.env.bootstrap.EnvDownloadSpec
import org.aclpe.core.env.bootstrap.EnvDownloadState
import org.aclpe.core.env.bootstrap.EnvDownloadTask
import org.aclpe.core.env.fsm.EnvItemState
import org.aclpe.core.env.probe.EnvScope
import org.aclpe.core.env.store.EnvItemEntity
import org.aclpe.core.env.store.VersionEntity
import org.aclpe.core.launch.model.LaunchAuth
import org.aclpe.core.launch.model.LaunchContext
import org.aclpe.core.launch.model.MemoryTier
import timber.log.Timber
import java.io.File

/** 环境卡片 UI 条目：组件状态 + 下载任务状态/进度。 */
data class EnvUiItem(
    val label: String,
    val component: String,
    val state: EnvItemState?,
    val errorCode: String?,
    val hasSpec: Boolean,
    val task: EnvDownloadState? = null,
)

data class EnvUiState(
    val items: List<EnvUiItem> = emptyList(),
    val probing: Boolean = false,
) {
    val overallReady: Boolean
        get() = items.isNotEmpty() && items.all { it.state == EnvItemState.READY }
}

/**
 * 首页环境卡片 ViewModel（Step 2：真实下载链路）：
 * - 启动即探测 + reconcile（幂等）；JRE 规格来自 [JreManifest]（内置 pin）；
 * - 下载进度/暂停/继续/重试经 [DownloadScheduler] 驱动，进度 Flow 供 UI 订阅；
 * - 无下载规格的组件（如待决策的 JRE21）显示「下载源待配置」，UI 诚实提示。
 */
class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val deps: EnvDependencies = (application as ACLApplication).envDependencies
    private val scheduler: DownloadScheduler = deps.scheduler
    private val specs: Map<String, EnvDownloadSpec> = JreManifest.specs()

    private val probing: MutableStateFlow<Boolean> = MutableStateFlow(false)

    val uiState: StateFlow<EnvUiState> = combine(
        deps.store.observe(),
        scheduler.states,
        probing,
    ) { items, tasks, isProbing ->
        val tasksByComponent: Map<String, EnvDownloadState> = tasks.associateBy { it.component }
        EnvUiState(
            items = listOf(17, 21).map { major ->
                val component: String = EnvItemEntity.jre(major)
                val item: EnvItemEntity? = items.firstOrNull { it.component == component }
                EnvUiItem(
                    label = "Java $major",
                    component = component,
                    state = item?.let { runCatching { EnvItemState.valueOf(it.state) }.getOrNull() },
                    errorCode = item?.errorCode,
                    hasSpec = specs.containsKey(component),
                    task = tasksByComponent[component],
                )
            },
            probing = isProbing,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EnvUiState())

    init {
        refresh()
        // 自动补齐路径的完成观察：reconcile 自动入队的任务没有 download() 的等待者，
        // 任何任务到达终态（成功/失败/取消）后重新探测刷新环境条目状态（幂等）。
        viewModelScope.launch {
            val handled: MutableSet<String> = mutableSetOf()
            scheduler.states.collect { states ->
                states.forEach { state ->
                    val key: String = "${state.component}:${state.state}"
                    if (state.state in TERMINAL_TASK_STATES && handled.add(key)) {
                        refresh()
                    }
                }
            }
        }
    }

    fun refresh() {
        // IO 调度器（关键）：reconcile → EnvProbe 会阻塞执行 java -version（15s 超时/槽位），
        // 跑在 viewModelScope 默认 Main 会冻结 UI 甚至 ANR（真机实证「处理中卡死」的一半根因）。
        viewModelScope.launch(Dispatchers.IO) {
            probing.value = true
            // 幂等：tmp 清理 + reconcile（重复调用安全，在途任务不被打断）；
            // jre:21 槽位为 atLeast 语义（实际安装社区 JRE25，方案 B）
            deps.bootstrap.cleanupTmp()
            deps.bootstrap.reconcile(
                EnvScope(jreMajors = setOf(17, 21), jreAtLeastMajors = JreManifest.atLeastMajors),
                specs,
            )
            probing.value = false
        }
    }

    /** 手动下载/重试：仅对有规格组件生效；成功后自动重新探测刷新 READY。 */
    fun download(component: String) {
        val spec: EnvDownloadSpec = specs[component] ?: return
        scheduler.enqueue(EnvDownloadTask(component, spec.url, spec.sha256, spec.sizeBytes))
        viewModelScope.launch {
            scheduler.states.first { states ->
                states.any { it.component == component && it.state == DownloadTaskState.SUCCEEDED }
            }
            refresh()
        }
    }

    companion object {
        private val TERMINAL_TASK_STATES: Set<DownloadTaskState> = setOf(
            DownloadTaskState.SUCCEEDED,
            DownloadTaskState.FAILED,
            DownloadTaskState.CANCELLED,
        )
    }

    fun pause(component: String) = scheduler.pause(component)

    fun resume(component: String) = scheduler.resume(component)

    // ---- Step 4：启动按钮接线（真实链路：门禁 → 账号 → 版本 → 拼装 → 拉起） ----

    private val _launchMessage = MutableStateFlow<String?>(null)
    val launchMessage: StateFlow<String?> = _launchMessage

    fun consumeLaunchMessage() {
        _launchMessage.value = null
    }

    /**
     * 启动游戏。每步失败给出诚实提示；版本安装流水线（Step 2 遗留）未接线前，
     * 实际会停在第 3 步提示（版本未安装）——链路结构已完整，接入安装后即通。
     */
    fun launch() {
        viewModelScope.launch(Dispatchers.IO) {
            // 1. 环境门禁（LaunchGate 语义）
            if (!uiState.value.overallReady) {
                _launchMessage.value = "环境未就绪，无法启动游戏"
                return@launch
            }
            // 2. 账号（离线会话 token 为空）
            val account: Account = deps.accountRepository.activeAccount(AccountViewModel.PROFILE_ID).getOrNull()
                ?: run {
                    _launchMessage.value = "请先在「设置 → 账号」创建账号"
                    return@launch
                }
            // 3. 已安装版本检查（诚实提示：版本安装流水线后续接入）
            val installed: VersionEntity? = deps.versionDao.observeAll().first().firstOrNull { it.installed }
            if (installed == null) {
                _launchMessage.value = "尚未安装游戏版本（版本安装流水线后续接入）"
                return@launch
            }
            // 4. 版本元数据 + 库解析 + 拼装 + 拉起
            val profileDir: File = File(deps.layout.root, "runtimes/${AccountViewModel.PROFILE_ID}")
            val versionJsonFile: File = File(profileDir, "versions/${installed.id}/${installed.id}.json")
            val versionJson: VersionJson = versionJsonFile.takeIf { it.isFile }
                ?.readText()
                ?.let { VersionJsonParser.parseVersionJson(it).getOrNull() }
                ?: run {
                    _launchMessage.value = "版本元数据缺失，请重新安装"
                    return@launch
                }
            val session: AuthSession = deps.accountRepository.activeSession(AccountViewModel.PROFILE_ID).getOrNull()
                ?: AuthSession(account, AuthTokens(accessToken = ""))

            val context: LaunchContext = LaunchContext(
                version = versionJson,
                auth = LaunchAuth(
                    name = account.username,
                    uuid = account.uuid,
                    accessToken = session.tokens.accessToken,
                    userType = if (account.type == AccountType.LOCAL_OFFLINE) "legacy" else "msa",
                ),
                gameDir = profileDir,
                assetsDir = File(profileDir, "assets"),
                assetsIndexId = versionJson.assetIndex?.id ?: "",
                nativesDir = File(profileDir, "natives"),
                libraries = LibraryResolver.resolve(versionJson.libraries, PlatformContext.fromOsSpec(), MirrorId.OFFICIAL),
                clientJar = File(profileDir, "versions/${installed.id}/${installed.id}.jar"),
                javaMajor = installed.javaMajor ?: 17,
                memoryTier = MemoryTier.MEDIUM,
                deviceMemoryMb = 2048,
            )
            val plan = deps.launchPlanBuilder.build(context)
            if (plan.isErr) {
                _launchMessage.value = "启动计划生成失败：${plan.errorOrNull()?.code}"
                return@launch
            }
            val start = deps.gameProcessStarter.start(
                plan.getOrNull()!!,
                stdoutLog = File(profileDir, "logs/game-stdout.log"),
                stderrLog = File(profileDir, "logs/game-stderr.log"),
            )
            if (start.isOk) {
                // 审计中危#14：游戏运行登记（「运行中禁止覆盖 options.txt」硬约束生效源）
                org.aclpe.aclpe.core.env.GameProcessRegistry.markRunning(true)
                _launchMessage.value = "游戏进程已启动"
                // 进程退出后解除登记（IO 等待不阻塞 UI）
                val process: org.aclpe.core.launch.process.GameProcess = start.getOrNull()!!
                viewModelScope.launch(Dispatchers.IO) {
                    val exit: Int = process.waitFor()
                    org.aclpe.aclpe.core.env.GameProcessRegistry.markRunning(false)
                    Timber.i("game exited: code=%d", exit)
                }
            } else {
                _launchMessage.value = "启动失败：${start.errorOrNull()?.code}"
            }
        }
    }
}
