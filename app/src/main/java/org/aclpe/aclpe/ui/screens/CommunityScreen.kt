package org.aclpe.aclpe.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aclpe.aclpe.R
import org.aclpe.aclpe.ui.community.CommunityViewModel
import org.aclpe.core.mods.modrinth.ModrinthProject
import org.aclpe.core.mods.modrinth.ModrinthVersion

/**
 * 社区资源页（Modrinth，2026-11-02 上架）：
 * 搜索栏 → 结果列表（标题/简介/下载量）→ 点击进版本列表（版本号/加载器/文件大小）。
 * 下载安装动作随版本安装流水线接入（当前选中态展示）。
 */
@Composable
fun CommunityScreen(viewModel: CommunityViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val selected: ModrinthProject? = state.selected   // 局部变量解锁 smart cast

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
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
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.community_source_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            when {
                state.searching -> Row(
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth(),
                ) { CircularProgressIndicator() }
                state.error != null -> Text(
                    text = stringResource(R.string.community_error, state.error ?: ""),
                    color = MaterialTheme.colorScheme.error,
                )
                state.hits.isEmpty() -> Text(
                    text = stringResource(R.string.community_empty_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.hits, key = { it.project_id ?: it.slug ?: it.title.orEmpty() }) { project ->
                        ProjectCard(project = project, onClick = { viewModel.select(project) })
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = selected.title ?: selected.slug ?: "",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = viewModel::clearSelection) {
                    Text(stringResource(R.string.community_back))
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = selected.description.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            when {
                state.loadingVersions -> Row(
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth(),
                ) { CircularProgressIndicator() }
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.versions, key = { it.id ?: it.version_number.orEmpty() }) { version ->
                        VersionCard(version = version)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectCard(project: ModrinthProject, onClick: () -> Unit) {
    Card(modifier = Modifier
        .fillMaxWidth()
        .clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = project.title ?: project.slug ?: "?",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = project.description.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
            Text(
                text = "↓ ${project.downloads ?: 0}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun VersionCard(version: ModrinthVersion) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = version.version_number ?: version.name ?: "?",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = version.version_type ?: "release",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = version.loaders.joinToString(" / ") + " · " +
                    (version.game_versions.firstOrNull() ?: "") + " · ↓ ${version.downloads ?: 0}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
