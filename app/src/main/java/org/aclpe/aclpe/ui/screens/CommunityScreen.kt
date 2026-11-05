package org.aclpe.aclpe.ui.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aclpe.aclpe.R
import org.aclpe.aclpe.ui.community.CommunityTab
import org.aclpe.aclpe.ui.community.CommunityViewModel
import org.aclpe.core.download.loader.ModLoaderInstaller
import org.aclpe.core.download.loader.ModLoaderKind
import org.aclpe.core.download.loader.ModLoaderVersion
import org.aclpe.core.mods.modrinth.ModrinthProject
import org.aclpe.core.mods.modrinth.ModrinthVersion

/**
 * 社区资源页（2026-11-02 扩展）：
 * - 模组 Tab：搜索 → 项目 → 版本 → **安装进所选版本**（全局 mods 或 versions/<id>/mods）；
 * - 模组加载器 Tab：选父版本 → Fabric/Quilt → 加载器版本 → 安装（为已有版本添加加载器）。
 * 数据基址随"社区镜像"设置（设置页可切换）。
 */
@Composable
fun CommunityScreen(viewModel: CommunityViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val selected: ModrinthProject? = state.selected
    var selectedCategory by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.message) {
        state.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeMessage()
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        TabRow(selectedTabIndex = if (state.tab == CommunityTab.MODS) 0 else 1) {
            Tab(
                selected = state.tab == CommunityTab.MODS,
                onClick = { viewModel.switchTab(CommunityTab.MODS) },
                text = { Text(stringResource(R.string.community_tab_mods)) },
            )
            Tab(
                selected = state.tab == CommunityTab.LOADERS,
                onClick = { viewModel.switchTab(CommunityTab.LOADERS) },
                text = { Text(stringResource(R.string.community_tab_loaders)) },
            )
        }
        Spacer(modifier = Modifier.height(8.dp))

        if (state.tab == CommunityTab.MODS) {
            if (selected == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = viewModel::updateQuery,
                        label = { Text(stringResource(R.string.community_search_hint)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.padding(4.dp))
                    Button(onClick = viewModel::search, enabled = state.query.isNotBlank() && !state.searching) {
                        Text(stringResource(R.string.community_search_action))
                    }
                }
            }
            // 模组分类筛选（Modrinth categories）
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                MOD_CATEGORIES.forEach { (cat, label) ->
                    FilterChip(
                        selected = selectedCategory == cat,
                        onClick = { selectedCategory = cat },
                        label = { Text(label) },
                    )
                }
            }

            // 安装目标版本（模组装进哪里）
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.community_target_label),
                style = MaterialTheme.typography.labelMedium,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                FilterChip(
                    selected = state.targetVersion == null,
                    onClick = { viewModel.setTargetVersion(null) },
                    label = { Text(stringResource(R.string.community_target_global)) },
                )
                state.installedVersions.forEach { id: String ->
                    FilterChip(
                        selected = state.targetVersion == id,
                        onClick = { viewModel.setTargetVersion(id) },
                        label = { Text(id) },
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.community_source_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.error != null) {
                Text(
                    text = stringResource(R.string.community_error, state.error ?: ""),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            if (selected == null) {
                if (state.searching) {
                    Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) { CircularProgressIndicator() }
                } else {
                    val filteredHits = if (selectedCategory == null) state.hits
                    else state.hits.filter { p -> p.categories.contains(selectedCategory) }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(filteredHits, key = { it.project_id ?: it.slug ?: it.title.orEmpty() }) { project: ModrinthProject ->
                            ProjectCard(project = project, onClick = { viewModel.select(project) })
                        }
                    }
                }
            } else {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = selected.title ?: selected.slug ?: "", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = viewModel::clearSelection) { Text(stringResource(R.string.community_back)) }
                }
                Text(
                    text = selected.description.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(6.dp))
                if (state.loadingVersions) {
                    Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) { CircularProgressIndicator() }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.versions, key = { it.id ?: it.version_number.orEmpty() }) { version: ModrinthVersion ->
                            ModVersionCard(
                                version = version,
                                progress = state.modDownloads[version.files.firstOrNull { f -> f.primary == true }?.filename
                                    ?: version.files.firstOrNull()?.filename],
                                onInstall = { viewModel.downloadMod(version) },
                            )
                        }
                    }
                }
            }
        } else {
            LoaderTab(state = state, viewModel = viewModel)
        }
    }
}

@Composable
private fun LoaderTab(state: CommunityViewModel.UiState, viewModel: CommunityViewModel) {
    Text(
        text = stringResource(R.string.community_loader_hint),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(6.dp))

    Text(text = stringResource(R.string.community_loader_parent), style = MaterialTheme.typography.labelMedium)
    if (state.installedVersions.isEmpty()) {
        Text(
            text = stringResource(R.string.community_loader_no_versions),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        state.installedVersions.forEach { id: String ->
            FilterChip(
                selected = state.loaderParent == id,
                onClick = { viewModel.setLoaderParent(id) },
                label = { Text(id) },
            )
        }
    }

    Spacer(modifier = Modifier.height(8.dp))
    Text(text = stringResource(R.string.community_loader_kind), style = MaterialTheme.typography.labelMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ModLoaderKind.entries.forEach { kind: ModLoaderKind ->
            FilterChip(
                selected = state.loaderKind == kind,
                onClick = {
                    viewModel.setLoaderKind(kind)
                    state.loaderParent?.let { viewModel.setLoaderParent(it) }
                },
                label = { Text(kind.displayName) },
            )
        }
        FilterChip(selected = false, onClick = {}, label = { Text("Forge / NeoForge（暂不支持）") })
    }

    Spacer(modifier = Modifier.height(8.dp))
    if (state.loadingLoaders) {
        Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) { CircularProgressIndicator() }
    } else if (state.loaderVersions.isEmpty()) {
        Text(
            text = stringResource(R.string.community_loader_pick),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        val parentId: String = state.loaderParent.orEmpty()
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(state.loaderVersions.take(30), key = { it.version }) { loaderVersion: ModLoaderVersion ->
                val newId: String = viewModel.newVersionId(state.loaderKind, loaderVersion.version, parentId)
                val installState: ModLoaderInstaller.InstallState? = state.loaderInstallStates[newId]
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)),
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${state.loaderKind.displayName} ${loaderVersion.version}" +
                                        if (loaderVersion.stable) "（稳定）" else "",
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    text = newId,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            when {
                                installState?.phase == ModLoaderInstaller.Phase.DONE -> Text(
                                    text = stringResource(R.string.env_status_ready),
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                installState?.phase == ModLoaderInstaller.Phase.FAILED -> Text(
                                    text = stringResource(R.string.env_status_failed) + "（${installState.errorCode}）",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                installState != null -> Text(
                                    text = stringResource(R.string.env_status_installing),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                else -> Button(onClick = { viewModel.installLoader(loaderVersion.version) }) {
                                    Text(stringResource(R.string.env_action_install))
                                }
                            }
                        }
                        if (installState != null && installState.filesTotal > 0 && installState.phase != ModLoaderInstaller.Phase.DONE) {
                            Text(
                                text = "${installState.filesDone}/${installState.filesTotal}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            LinearProgressIndicator(
                                progress = { (installState.filesDone.toFloat() / installState.filesTotal).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectCard(project: ModrinthProject, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)),
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            // 图标占位（首字母圆形，无需网络图片加载）
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = (project.title ?: project.slug ?: "?").take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = project.title ?: project.slug ?: "?", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = project.description.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
                if (project.categories.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = project.categories.take(3).joinToString(" / "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                Text(
                    text = "↓ ${project.downloads ?: 0}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun ModVersionCard(version: ModrinthVersion, progress: Float?, onInstall: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = version.version_number ?: version.name ?: "?", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = version.loaders.joinToString(" / ") + " · " +
                            (version.game_versions.firstOrNull() ?: "") + " · ↓ ${version.downloads ?: 0}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (progress == null) {
                    Button(onClick = onInstall) {
                        Text(stringResource(R.string.community_install_mod))
                    }
                } else {
                    Text(
                        text = "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                )
            }
        }
    }
}

/** 模组分类（Modrinth categories → 中文，null = 全部）。 */
private val MOD_CATEGORIES: List<Pair<String?, String>> = listOf(
    null to "全部",
    "optimization" to "优化",
    "decoration" to "装饰",
    "magic" to "魔法",
    "technology" to "科技",
    "adventure" to "冒险",
    "utility" to "实用",
    "equipment" to "装备",
    "food" to "食物",
    "worldgen" to "世界生成",
)