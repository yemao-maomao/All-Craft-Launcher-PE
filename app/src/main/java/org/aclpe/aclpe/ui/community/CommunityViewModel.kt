package org.aclpe.aclpe.ui.community

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.aclpe.aclpe.ACLApplication
import org.aclpe.core.mods.modrinth.ModrinthProject
import org.aclpe.core.mods.modrinth.ModrinthVersion

/**
 * 社区资源页 ViewModel（Modrinth，2026-11-02 上架）：
 * 搜索 → 选项目 → 版本列表（下载安装随版本安装流水线接入，当前先展示与选中）。
 */
class CommunityViewModel(application: Application) : AndroidViewModel(application) {

    private val api = (application as ACLApplication).envDependencies.modrinthClient

    data class UiState(
        val query: String = "",
        val searching: Boolean = false,
        val hits: List<ModrinthProject> = emptyList(),
        val selected: ModrinthProject? = null,
        val versions: List<ModrinthVersion> = emptyList(),
        val loadingVersions: Boolean = false,
        val error: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    fun updateQuery(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
    }

    fun search() {
        val query: String = _uiState.value.query.trim()
        if (query.isEmpty() || _uiState.value.searching) return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(searching = true, error = null)
            val result = api.search(query = query, gameVersion = "1.20.1")
            _uiState.value = if (result.isOk) {
                _uiState.value.copy(searching = false, hits = result.getOrNull()!!.hits)
            } else {
                _uiState.value.copy(searching = false, error = result.errorOrNull()?.code)
            }
        }
    }

    fun select(project: ModrinthProject) {
        val slug: String = project.slug ?: project.project_id ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(selected = project, loadingVersions = true, versions = emptyList())
            val result = api.versions(slug, gameVersion = "1.20.1")
            _uiState.value = if (result.isOk) {
                _uiState.value.copy(loadingVersions = false, versions = result.getOrNull()!!.versions)
            } else {
                _uiState.value.copy(loadingVersions = false, error = result.errorOrNull()?.code)
            }
        }
    }

    fun clearSelection() {
        _uiState.value = _uiState.value.copy(selected = null, versions = emptyList())
    }
}
