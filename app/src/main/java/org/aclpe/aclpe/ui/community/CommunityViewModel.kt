package org.aclpe.aclpe.ui.community

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.aclpe.aclpe.ACLApplication
import org.aclpe.aclpe.core.env.EnvDependencies
import org.aclpe.core.download.loader.ModLoaderInstaller
import org.aclpe.core.download.loader.ModLoaderKind
import org.aclpe.core.download.loader.ModLoaderVersion
import org.aclpe.core.download.net.DownloadRequest
import org.aclpe.core.env.store.VersionEntity
import org.aclpe.core.mods.modrinth.ModrinthFile
import org.aclpe.core.mods.modrinth.ModrinthProject
import org.aclpe.core.mods.modrinth.ModrinthVersion
import java.io.File

/** 社区页主 Tab。 */
enum class CommunityTab { MODS, LOADERS }

/**
 * 社区资源 ViewModel（2026-11-02 扩展）：
 * - 模组：搜索 → 项目 → 版本 → **下载安装进所选版本**（版本隔离 `versions/<id>/mods/` 或全局 `mods/`）；
 * - 模组加载器：为**已有版本**安装 Fabric/Quilt（元数据拼接，无需安装器；Forge/NeoForge 需官方安装器，暂不支持）；
 * - 数据源基址随"社区镜像"设置动态变化（MCMirror 反代 Modrinth API）。
 */
class CommunityViewModel(application: Application) : AndroidViewModel(application) {

    private val deps: EnvDependencies = (application as ACLApplication).envDependencies
    private val api = deps.modrinthClient
    private val loaderInstaller: ModLoaderInstaller = deps.modLoaderInstaller

    data class UiState(
        val tab: CommunityTab = CommunityTab.MODS,
        // 模组
        val query: String = "",
        val searching: Boolean = false,
        val hits: List<ModrinthProject> = emptyList(),
        val selected: ModrinthProject? = null,
        val versions: List<ModrinthVersion> = emptyList(),
        val loadingVersions: Boolean = false,
        /** 已安装版本 id（供"安装到哪个版本"选择 + 加载器父版本选择）。 */
        val installedVersions: List<String> = emptyList(),
        /** 模组安装目标版本（null = 全局 mods）。 */
        val targetVersion: String? = null,
        /** 模组下载进度：文件名 → 百分比（0..1）。 */
        val modDownloads: Map<String, Float> = emptyMap(),
        // 加载器
        val loaderKind: ModLoaderKind = ModLoaderKind.FABRIC,
        val loaderParent: String? = null,
        val loaderVersions: List<ModLoaderVersion> = emptyList(),
        val loadingLoaders: Boolean = false,
        val loaderInstallStates: Map<String, ModLoaderInstaller.InstallState> = emptyMap(),
        val message: String? = null,
        val error: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    init {
        // 已安装版本 + 加载器安装状态：持续合并回 UI（写回 _uiState——勿再犯空 collect 的坑）
        viewModelScope.launch {
            combine(
                deps.versionDao.observeAll(),
                loaderInstaller.states,
            ) { installed: List<VersionEntity>, loaderStates: Map<String, ModLoaderInstaller.InstallState> ->
                installed.filter { it.installed }.map { it.id }.sorted() to loaderStates
            }.collect { (ids: List<String>, loaderStates: Map<String, ModLoaderInstaller.InstallState>) ->
                _uiState.value = _uiState.value.copy(
                    installedVersions = ids,
                    loaderInstallStates = loaderStates,
                )
            }
        }
    }

    fun consumeMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    fun switchTab(tab: CommunityTab) {
        _uiState.value = _uiState.value.copy(tab = tab)
    }

    // ---- 模组 ----

    fun updateQuery(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
    }

    fun setTargetVersion(versionId: String?) {
        _uiState.value = _uiState.value.copy(targetVersion = versionId)
    }

    fun search() {
        val query: String = _uiState.value.query.trim()
        if (query.isEmpty() || _uiState.value.searching) return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(searching = true, error = null)
            val result = api.search(query = query, gameVersion = GAME_VERSION)
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
            val result = api.versions(slug, gameVersion = GAME_VERSION)
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

    /** 下载模组文件：装进 [targetVersion]（null = 全局 mods），sha1 校验，进度回写 UI。 */
    fun downloadMod(version: ModrinthVersion) {
        val file: ModrinthFile = version.files.firstOrNull { it.primary == true } ?: version.files.firstOrNull() ?: return
        val url: String = file.url ?: return
        val filename: String = file.filename ?: "mod.jar"
        val target: String? = _uiState.value.targetVersion
        viewModelScope.launch(Dispatchers.IO) {
            val dir: File = deps.modsDir(target)
            dir.mkdirs()
            _uiState.value = _uiState.value.copy(modDownloads = _uiState.value.modDownloads + (filename to 0f))
            val result = deps.manager.download(
                DownloadRequest(
                    id = "mod:$filename",
                    url = url,
                    destFile = File(dir, filename),
                    sha1 = file.sha1,
                    sizeBytes = file.size,
                    onProgress = { p ->
                        val total: Long = p.totalBytes ?: 0L
                        val fraction: Float = if (total > 0) (p.downloadedBytes.toFloat() / total) else 0f
                        _uiState.value = _uiState.value.copy(modDownloads = _uiState.value.modDownloads + (filename to fraction))
                    },
                )
            )
            _uiState.value = if (result.isOk) {
                val where: String = target ?: "全局 mods"
                _uiState.value.copy(
                    modDownloads = _uiState.value.modDownloads - filename,
                    message = "已安装 $filename → $where",
                )
            } else {
                _uiState.value.copy(
                    modDownloads = _uiState.value.modDownloads - filename,
                    error = result.errorOrNull()?.code,
                )
            }
        }
    }

    // ---- 模组加载器 ----

    fun setLoaderKind(kind: ModLoaderKind) {
        _uiState.value = _uiState.value.copy(loaderKind = kind, loaderVersions = emptyList())
    }

    /** 选父版本 → 拉取该游戏版本可用的加载器版本列表。 */
    fun setLoaderParent(gameVersion: String) {
        _uiState.value = _uiState.value.copy(loaderParent = gameVersion, loadingLoaders = true, loaderVersions = emptyList())
        viewModelScope.launch(Dispatchers.IO) {
            val kind: ModLoaderKind = _uiState.value.loaderKind
            val result = loaderInstaller.loaders(gameVersion, kind)
            _uiState.value = if (result.isOk) {
                _uiState.value.copy(loadingLoaders = false, loaderVersions = result.getOrNull()!!)
            } else {
                _uiState.value.copy(loadingLoaders = false, error = result.errorOrNull()?.code)
            }
        }
    }

    fun reloadLoaderVersions() {
        _uiState.value.loaderParent?.let { setLoaderParent(it) }
    }

    /** 安装后的新版本 id（UI 展示与状态查询共用同一规则）。 */
    fun newVersionId(kind: ModLoaderKind, loaderVersion: String, gameVersion: String): String =
        loaderInstaller.versionIdOf(kind, loaderVersion, gameVersion)

    /** 安装加载器：为 [loaderParent] 创建带加载器的新版本（完成后写入 versions 表）。 */
    fun installLoader(loaderVersion: String) {
        val parent: String = _uiState.value.loaderParent ?: return
        val kind: ModLoaderKind = _uiState.value.loaderKind
        loaderInstaller.install(
            kind = kind,
            gameVersion = parent,
            loaderVersion = loaderVersion,
            gameDir = deps.versionsGameDir,
        ) { newId: String, profileJson ->
            deps.versionDao.upsert(
                VersionEntity(
                    id = newId,
                    type = kind.name.lowercase(),
                    javaMajor = null,
                    assetsIndexId = profileJson.assetIndex?.id,
                    assetsIndexSha1 = profileJson.assetIndex?.sha1,
                    installed = true,
                    installedAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    /** 取消加载器安装（2026-11：下载取消选项）。 */
    fun cancelLoader(newId: String) {
        loaderInstaller.cancel(newId)
    }

    companion object {
        /** 骨架期固定目标游戏版本（后续随"当前 Profile 所选版本"联动）。 */
        const val GAME_VERSION: String = "1.20.1"
    }
}