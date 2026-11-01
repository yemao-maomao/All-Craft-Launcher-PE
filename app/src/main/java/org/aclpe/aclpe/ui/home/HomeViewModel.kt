package org.aclpe.aclpe.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import org.aclpe.core.env.bootstrap.DownloadScheduler
import org.aclpe.core.env.bootstrap.DownloadTaskState
import org.aclpe.core.env.bootstrap.EnvDownloadSpec
import org.aclpe.core.env.bootstrap.EnvDownloadState
import org.aclpe.core.env.bootstrap.EnvDownloadTask
import org.aclpe.core.env.fsm.EnvItemState
import org.aclpe.core.env.probe.EnvScope
import org.aclpe.core.env.store.EnvItemEntity

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
    }

    fun refresh() {
        viewModelScope.launch {
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

    fun pause(component: String) = scheduler.pause(component)

    fun resume(component: String) = scheduler.resume(component)
}
