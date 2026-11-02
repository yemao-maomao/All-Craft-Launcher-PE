package org.aclpe.aclpe.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.aclpe.aclpe.ACLApplication
import org.aclpe.aclpe.core.env.EnvDependencies
import org.aclpe.aclpe.core.env.SimpleRuntimeInstaller
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
import org.aclpe.core.env.store.VersionEntity
import org.aclpe.core.launch.model.LaunchAuth
import org.aclpe.core.launch.model.LaunchContext
import org.aclpe.core.launch.model.MemoryTier
import timber.log.Timber
import java.io.File

/**
 * 首页环境 ViewModel（2026-11-02 重写，FCL 式线性部署）：
 * 不再走 状态机/reconcile/调度器观察 链路——SimpleRuntimeInstaller 完成即发 READY。
 * 启动即 ensure(17/21)；失败可重试；启动按钮门禁 = 两槽位 READY。
 */
class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val deps: EnvDependencies = (application as ACLApplication).envDependencies
    private val runtime: SimpleRuntimeInstaller = deps.runtimeInstaller

    /** 环境卡片条目（槽位语义：Java 17+/21+ 槽位实际装 JRE22/25）。 */
    data class EnvUiItem(
        val label: String,
        val slot: Int,
        val phase: SimpleRuntimeInstaller.Phase,
        val errorCode: String?,
    )

    data class EnvUiState(
        val items: List<EnvUiItem> = emptyList(),
    ) {
        val overallReady: Boolean
            get() = items.isNotEmpty() && items.all { it.phase == SimpleRuntimeInstaller.Phase.READY }
    }

    val uiState: StateFlow<EnvUiState> = runtime.states
        .map { states ->
            EnvUiState(
                items = listOf(17, 21).map { slot ->
                    val state: SimpleRuntimeInstaller.RuntimeState? = states[slot]
                    EnvUiItem(
                        label = "Java $slot+",
                        slot = slot,
                        phase = state?.phase ?: SimpleRuntimeInstaller.Phase.NOT_INSTALLED,
                        errorCode = state?.errorCode,
                    )
                },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EnvUiState())

    init {
        refresh()
    }

    /** 幂等：标记在立即 READY；未装则走线性部署。 */
    fun refresh() {
        runtime.ensure(17)
        runtime.ensure(21)
    }

    /** 安装/重试（用户指令：一键重来全流程）。 */
    fun install(slot: Int) {
        runtime.retry(slot)
    }

    // ---- 启动链路（Step 3 接线，保持不变） ----

    private val _launchMessage = MutableStateFlow<String?>(null)
    val launchMessage: StateFlow<String?> = _launchMessage

    fun consumeLaunchMessage() {
        _launchMessage.value = null
    }

    fun launch() {
        viewModelScope.launch(Dispatchers.IO) {
            if (!uiState.value.overallReady) {
                _launchMessage.value = "环境未就绪，无法启动游戏"
                return@launch
            }
            val account: Account = deps.accountRepository.activeAccount(AccountViewModel.PROFILE_ID).getOrNull()
                ?: run {
                    _launchMessage.value = "请先在「设置 → 账号」创建账号"
                    return@launch
                }
            val installed: VersionEntity? =
                deps.versionDao.observeAll().first().firstOrNull { it.installed }
            if (installed == null) {
                _launchMessage.value = "尚未安装游戏版本（版本安装流水线后续接入）"
                return@launch
            }
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
                org.aclpe.aclpe.core.env.GameProcessRegistry.markRunning(true)
                _launchMessage.value = "游戏进程已启动"
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
