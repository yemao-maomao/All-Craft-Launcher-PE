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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aclpe.aclpe.R
import org.aclpe.aclpe.ui.versions.VersionCategory
import org.aclpe.aclpe.ui.versions.VersionsViewModel
import org.aclpe.core.download.install.VersionInstaller
import org.aclpe.core.download.json.ManifestVersion

/**
 * 版本页（V0.0.3：真实版本下载安装接入）：
 * 清单（BMCLAPI 优先）→ 搜索 → 列表（release）→ 安装（线性流水线，进度/失败直达 UI）。
 */
@Composable
fun VersionsScreen(viewModel: VersionsViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::updateQuery,
            label = { Text(stringResource(R.string.versions_search_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = when {
                state.loading -> stringResource(R.string.versions_loading)
                state.error != null -> stringResource(R.string.versions_error, state.error ?: "")
                state.latestRelease != null -> stringResource(R.string.versions_latest, state.latestRelease ?: "")
                else -> ""
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (state.loading) {
            Row(
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth(),
            ) { CircularProgressIndicator() }
            return@Column
        }

        val categorized: Map<VersionCategory, List<ManifestVersion>> = viewModel.categorizedVersions()
        var releaseExpanded by remember { mutableStateOf(true) }
        var aprilExpanded by remember { mutableStateOf(false) }
        var snapshotExpanded by remember { mutableStateOf(false) }
        // LazyColumn builder 非 @Composable，标题提前在此解析
        val releaseTitle: String = stringResource(R.string.versions_cat_release)
        val aprilTitle: String = stringResource(R.string.versions_cat_april)
        val snapshotTitle: String = stringResource(R.string.versions_cat_snapshot)

        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            categoryBlock(
                title = releaseTitle,
                expanded = releaseExpanded,
                onToggle = { releaseExpanded = !releaseExpanded },
                versions = categorized[VersionCategory.RELEASE].orEmpty(),
                state = state,
                onInstall = viewModel::install,
            )
            categoryBlock(
                title = aprilTitle,
                expanded = aprilExpanded,
                onToggle = { aprilExpanded = !aprilExpanded },
                versions = categorized[VersionCategory.APRIL_FOOLS].orEmpty(),
                state = state,
                onInstall = viewModel::install,
            )
            categoryBlock(
                title = snapshotTitle,
                expanded = snapshotExpanded,
                onToggle = { snapshotExpanded = !snapshotExpanded },
                versions = categorized[VersionCategory.SNAPSHOT].orEmpty(),
                state = state,
                onInstall = viewModel::install,
            )
        }
    }
}

/** 可折叠分类块（LazyListScope 内：标题行 + 展开时的版本列表）。 */
private fun LazyListScope.categoryBlock(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    versions: List<ManifestVersion>,
    state: VersionsViewModel.UiState,
    onInstall: (ManifestVersion) -> Unit,
) {
    item(key = "header-$title") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$title（${versions.size}）",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (expanded) "▲" else "▼",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (expanded) {
        items(versions, key = { it.id }) { version: ManifestVersion ->
            VersionCard(
                version = version,
                installed = version.id in state.installedIds,
                installState = state.installing[version.id],
                onInstall = { onInstall(version) },
            )
        }
    }
}

@Composable
private fun VersionCard(
    version: ManifestVersion,
    installed: Boolean,
    installState: VersionInstaller.InstallState?,
    onInstall: () -> Unit,
) {
    // PCL 风格：半透明圆角卡片，融入液态玻璃背景
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = version.id,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                when {
                    installed -> Text(
                        text = stringResource(R.string.env_status_ready),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    installState != null && installState.phase != VersionInstaller.Phase.FAILED ->
                        Text(
                            text = phaseLabel(installState),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    else -> TextButton(onClick = onInstall) {
                        Text(stringResource(R.string.env_action_install))
                    }
                }
            }
            // 安装进度：assets/库阶段显示文件计数，client 阶段显示字节
            if (installState != null && installState.phase !in setOf(
                    VersionInstaller.Phase.DONE,
                    VersionInstaller.Phase.FAILED,
                    VersionInstaller.Phase.IDLE,
                )
            ) {
                if (installState.filesTotal > 0) {
                    Text(
                        text = "${installState.filesDone}/${installState.filesTotal}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinearProgressIndicator(
                        progress = {
                            (installState.filesDone.toFloat() / installState.filesTotal).coerceIn(0f, 1f)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                    )
                } else if (installState.bytesTotal > 0L) {
                    LinearProgressIndicator(
                        progress = {
                            (installState.bytesDone.toFloat() / installState.bytesTotal).coerceIn(0f, 1f)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                    )
                }
            }
            if (installState?.phase == VersionInstaller.Phase.FAILED) {
                Text(
                    text = stringResource(R.string.env_status_failed) +
                        "（${installState.errorCode ?: "?"}）" +
                        stringResource(R.string.versions_retry_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Button(onClick = onInstall, modifier = Modifier.padding(top = 4.dp)) {
                    Text(stringResource(R.string.env_action_retry))
                }
            }
        }
    }
}

@Composable
private fun phaseLabel(state: VersionInstaller.InstallState): String = when (state.phase) {
    VersionInstaller.Phase.JSON -> stringResource(R.string.versions_phase_json)
    VersionInstaller.Phase.CLIENT -> stringResource(R.string.versions_phase_client)
    VersionInstaller.Phase.LIBRARIES -> stringResource(R.string.versions_phase_libraries)
    VersionInstaller.Phase.EXTRACTING -> stringResource(R.string.versions_phase_extracting)
    VersionInstaller.Phase.ASSET_INDEX -> stringResource(R.string.versions_phase_asset_index)
    VersionInstaller.Phase.ASSETS -> stringResource(R.string.versions_phase_assets)
    VersionInstaller.Phase.DONE -> stringResource(R.string.env_status_ready)
    else -> stringResource(R.string.versions_phase_json)
}
