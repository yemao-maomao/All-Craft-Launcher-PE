package org.aclpe.aclpe.ui.versions

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.aclpe.aclpe.ACLApplication
import org.aclpe.aclpe.core.env.EnvDependencies
import org.aclpe.core.download.install.VersionInstaller
import org.aclpe.core.download.json.ManifestVersion
import org.aclpe.core.download.json.VersionManifestV2
import org.aclpe.core.env.store.VersionEntity

/**
 * 版本页 ViewModel（V0.0.3：真实版本下载安装接入）：
 * - 清单：BMCLAPI 优先拉 version_manifest_v2（release 过滤 + 搜索）；
 * - 安装：VersionInstaller 线性流水线（json → jar → 库 → natives → assets），
 *   进度/失败状态直达 UI，完成后写 versions 表 installed=true（启动链路随之可用）。
 */
/** 版本分类（2026-11 版本页折叠三块）。 */
enum class VersionCategory { RELEASE, APRIL_FOOLS, SNAPSHOT }

class VersionsViewModel(application: Application) : AndroidViewModel(application) {

    private val deps: EnvDependencies = (application as ACLApplication).envDependencies
    private val installer: VersionInstaller = deps.versionInstaller

    data class UiState(
        val loading: Boolean = false,
        val query: String = "",
        val versions: List<ManifestVersion> = emptyList(),
        val latestRelease: String? = null,
        val error: String? = null,
        /** 已安装版本 id 集（versions 表）。 */
        val installedIds: Set<String> = emptySet(),
        /** 安装进度（versionId → InstallState）。 */
        val installing: Map<String, VersionInstaller.InstallState> = emptyMap(),
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    init {
        loadManifest()
        // 合并：versions 表（installed 标记） + 安装进度流
        // 修复（2026-11-02 真机「点安装没反应」）：原 collect { } 空 lambda 丢弃了合并结果，
        // 安装状态永远写不回 _uiState → UI 无任何反应。
        viewModelScope.launch {
            combine(
                deps.versionDao.observeAll(),
                installer.states,
            ) { installed: List<VersionEntity>, installing: Map<String, VersionInstaller.InstallState> ->
                installed.filter { it.installed }.map { it.id }.toSet() to installing
            }.collect { (installedIds: Set<String>, installing: Map<String, VersionInstaller.InstallState>) ->
                _uiState.value = _uiState.value.copy(installedIds = installedIds, installing = installing)
            }
        }
    }

    fun loadManifest() {
        if (_uiState.value.loading) return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            val manifest: VersionManifestV2? = installer.loadManifest().getOrNull()
            _uiState.value = if (manifest != null) {
                _uiState.value.copy(
                    loading = false,
                    versions = manifest.versions,   // 全部版本类型（release/snapshot/old_beta/old_alpha）
                    latestRelease = manifest.latest.release,
                )
            } else {
                _uiState.value.copy(loading = false, error = "manifest_failed")
            }
        }
    }

    fun updateQuery(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
    }

    /** 搜索过滤后的列表（id 包含关键词，前 100 条）。 */
    fun filteredVersions(): List<ManifestVersion> {
        val state: UiState = _uiState.value
        val q: String = state.query.trim()
        return if (q.isEmpty()) {
            state.versions.take(100)
        } else {
            state.versions.filter { it.id.contains(q, ignoreCase = true) }.take(100)
        }
    }

    /** 分类后的版本列表（搜索过滤 + 三分类 + 正式版按版本号降序）。 */
    fun categorizedVersions(): Map<VersionCategory, List<ManifestVersion>> {
        val state: UiState = _uiState.value
        val q: String = state.query.trim()
        val filtered: List<ManifestVersion> = if (q.isEmpty()) {
            state.versions
        } else {
            state.versions.filter { it.id.contains(q, ignoreCase = true) }
        }
        return mapOf(
            VersionCategory.RELEASE to filtered
                .filter { it.type == "release" }
                .sortedWith { a, b -> versionCompare(a.id, b.id) },
            VersionCategory.APRIL_FOOLS to filtered.filter { it.id in APRIL_FOOLS_IDS },
            VersionCategory.SNAPSHOT to filtered.filter {
                it.type != "release" && it.id !in APRIL_FOOLS_IDS
            },
        )
    }

    /** Minecraft 版本号比较（按 . 分段转整数逐段比较，缺失补 0；返回降序）。 */
    private fun versionCompare(a: String, b: String): Int {
        val pa: List<Int> = a.split('.').mapNotNull { it.toIntOrNull() }
        val pb: List<Int> = b.split('.').mapNotNull { it.toIntOrNull() }
        val n: Int = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val va: Int = pa.getOrElse(i) { 0 }
            val vb: Int = pb.getOrElse(i) { 0 }
            if (va != vb) return vb - va
        }
        return 0
    }

    companion object {
        /** Minecraft 官方愚人节版本 id（type 均为 snapshot，靠 id 识别）。 */
        val APRIL_FOOLS_IDS: Set<String> = setOf(
            "2.0",                    // 2013 红蓝版本
            "15w14a",                 // 2015 The Love and Hugs Update
            "1.RV-Pre1",              // 2016 Trendy Update
            "3D Shareware v1.34",     // 2019 3D 共享版
            "20w14infinite",          // 2020 20w14∞
            "22w13oneBlockAtATime",   // 2022 One Block at a Time
            "23w13a_or_b",            // 2023 Vote Update
            "24w14potato",            // 2024 Poisonous Potato Update
        )
    }

    /** 安装版本：完成后写 versions 表 installed=true（javaMajor/资产索引随写，启动链路直接可用）。 */
    fun install(version: ManifestVersion) {
        installer.install(version, deps.versionsGameDir) { versionJson ->
            deps.versionDao.upsert(
                VersionEntity(
                    id = versionJson.id,
                    type = versionJson.type,
                    javaMajor = versionJson.javaVersion?.majorVersion,
                    assetsIndexId = versionJson.assetIndex?.id,
                    assetsIndexSha1 = versionJson.assetIndex?.sha1,
                    installed = true,
                    installedAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
    }
}
