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
 * 首页 ViewModel（2026-11-05 仿 FCL 重构）：
 * - FCL 式主界面：账户 + 当前版本（可切换）+ 大启动按钮；
 * - 版本选择：已安装版本列表（来自 versions 表）+ 用户选中的版本（DataStore 持久化）；
 * - 启动：用**用户选中的版本**（无则取第一个已装版本），全程环境/账号/版本门禁。
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

    data class HomeUiState(
        val envItems: List<EnvUiItem> = emptyList(),
        val overallReady: Boolean = false,
        /** 已安装版本（含加载器版本），按 id 排序。 */
        val installedVersions: List<VersionEntity> = emptyList(),
        /** 用户当前选中的版本 id（null = 尚未选择，启动时回退到第一个已装版本）。 */
        val selectedVersionId: String? = null,
        /** 当前活跃账户（离线或微软）。 */
        val account: Account? = null,
    ) {
        /** 实际用于启动的版本：优先用户选中，否则第一个已装。 */
        val effectiveVersion: VersionEntity?
            get() = installedVersions.firstOrNull { it.id == selectedVersionId }
                ?: installedVersions.firstOrNull()
    }

    val uiState: StateFlow<HomeUiState> = combine(
        runtime.states,
        deps.versionDao.observeAll(),
        deps.appSettings.selectedVersionId,
        accountFlow(),
    ) { states, versions, selectedId, account ->
        HomeUiState(
            envItems = listOf(17, 21).map { slot ->
                val s = states[slot]
                EnvUiItem(
                    label = "Java $slot+",
                    slot = slot,
                    phase = s?.phase ?: SimpleRuntimeInstaller.Phase.NOT_INSTALLED,
                    errorCode = s?.errorCode,
                )
            },
            overallReady = states[17]?.phase == SimpleRuntimeInstaller.Phase.READY &&
                states[21]?.phase == SimpleRuntimeInstaller.Phase.READY,
            installedVersions = versions.filter { it.installed }.sortedBy { it.id },
            selectedVersionId = selectedId,
            account = account,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init {
        refresh()
    }

    /** 幂等：标记在立即 READY；未装则走线性部署。 */
    fun refresh() {
        runtime.ensure(17)
        runtime.ensure(21)
    }

    /** 安装/重试。 */
    fun install(slot: Int) {
        runtime.retry(slot)
    }

    /** 切换当前启动版本（FCL：点击版本名 → 选择）。 */
    fun selectVersion(versionId: String) {
        viewModelScope.launch { deps.appSettings.setSelectedVersionId(versionId) }
    }

    private fun accountFlow(): kotlinx.coroutines.flow.Flow<Account?> =
        kotlinx.coroutines.flow.flow {
            // 账户变化主要来自登录/登出，简单起见每次组合时读一次活跃账户
            emit(deps.accountRepository.activeAccount(AccountViewModel.PROFILE_ID).getOrNull())
        }

    // ---- 启动链路 ----

    private val _launchMessage = MutableStateFlow<String?>(null)
    val launchMessage: StateFlow<String?> = _launchMessage

    fun consumeLaunchMessage() {
        _launchMessage.value = null
    }

    fun launch() {
        viewModelScope.launch(Dispatchers.IO) {
            if (!uiState.value.overallReady) {
                _launchMessage.value = "环境未就绪：请先完成 Java 运行环境安装"
                return@launch
            }
            val account: Account = deps.accountRepository.activeAccount(AccountViewModel.PROFILE_ID).getOrNull()
                ?: run {
                    _launchMessage.value = "请先在「设置 → 账号」创建账号"
                    return@launch
                }
            val installed: VersionEntity? = uiState.value.effectiveVersion
            if (installed == null) {
                _launchMessage.value = "尚未安装游戏版本：请到「版本」页下载"
                return@launch
            }
            val profileDir: File = File(deps.layout.root, "runtimes/${AccountViewModel.PROFILE_ID}")
            val versionJsonFile: File = File(profileDir, "versions/${installed.id}/${installed.id}.json")
            val versionJson: VersionJson = versionJsonFile.takeIf { it.isFile }
                ?.readText()
                ?.let { VersionJsonParser.parseVersionJson(it).getOrNull() }
                ?: run {
                    _launchMessage.value = "版本元数据缺失，请重新安装「${installed.id}」"
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
                // 真机 W^X：ProcessBuilder 执行应用数据目录内 java 二进制会被系统拒绝（IOException）。
                // 这是已知设备级限制，友好提示而非抛出难懂错误码。
                val code: String = start.errorOrNull()?.code ?: "unknown"
                _launchMessage.value = if (code == "game_process_start_failed") {
                    "环境/版本/账号均已就绪，但当前 Android 系统安全策略禁止直接运行 Java（W^X）。" +
                        "完整启动引擎（JNI 进程内 JVM，FCL 同款）开发中——此为本应用最后一块待接入能力。"
                } else {
                    "启动失败：$code"
                }
            }
        }
    }
}