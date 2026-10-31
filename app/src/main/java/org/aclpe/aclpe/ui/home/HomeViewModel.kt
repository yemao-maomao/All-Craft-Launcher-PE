package org.aclpe.aclpe.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.aclpe.aclpe.ACLApplication
import org.aclpe.aclpe.core.env.EnvDependencies
import org.aclpe.core.env.fsm.EnvItemState
import org.aclpe.core.env.probe.EnvScope
import org.aclpe.core.env.store.EnvItemEntity

/** 环境卡片 UI 条目。 */
data class EnvUiItem(
    val label: String,
    val component: String,
    val state: EnvItemState?,
    val errorCode: String?,
)

data class EnvUiState(
    val items: List<EnvUiItem> = emptyList(),
    val probing: Boolean = false,
) {
    /** 全部组件就绪才允许启动（门禁由 core-env LaunchGate 保证语义一致）。 */
    val overallReady: Boolean
        get() = items.isNotEmpty() && items.all { it.state == EnvItemState.READY }
}

/**
 * 首页环境卡片 ViewModel：
 * - 启动即探测 + reconcile（幂等，进程被杀后重启自动续跑语义由 core-env 保证）；
 * - 骨架期探测范围 = JRE 17/21（未安装版本 → natives/assets 无依赖）；
 * - 下载规格由 Step 2 提供，此前 EnvBootstrap 将组件置 MISSING(no_download_spec)，按钮诚实提示。
 */
class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val deps: EnvDependencies = (application as ACLApplication).envDependencies

    private val probing: MutableStateFlow<Boolean> = MutableStateFlow(false)

    val uiState: StateFlow<EnvUiState> = combine(deps.store.observe(), probing) { items, isProbing ->
        EnvUiState(
            items = listOf(17, 21).map { major ->
                val item: EnvItemEntity? = items.firstOrNull { it.component == EnvItemEntity.jre(major) }
                EnvUiItem(
                    label = "Java $major",
                    component = EnvItemEntity.jre(major),
                    state = item?.let { runCatching { EnvItemState.valueOf(it.state) }.getOrNull() },
                    errorCode = item?.errorCode,
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
            // 幂等：tmp 清理 + reconcile（重复调用安全，在途任务不被打断）
            deps.bootstrap.cleanupTmp()
            deps.bootstrap.reconcile(EnvScope(jreMajors = setOf(17, 21)), emptyMap())
            probing.value = false
        }
    }
}
