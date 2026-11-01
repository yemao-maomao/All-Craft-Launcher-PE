package org.aclpe.aclpe.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aclpe.aclpe.R
import org.aclpe.aclpe.ui.home.EnvUiItem
import org.aclpe.aclpe.ui.home.EnvUiState
import org.aclpe.aclpe.ui.home.HomeViewModel
import org.aclpe.core.env.bootstrap.DownloadTaskState
import org.aclpe.core.env.fsm.EnvItemState

/**
 * 首页：环境卡片（core-env + core-download 驱动，Step 2 交付）。
 * 状态机：缺少 Java 17 →「下载」（真实下载+进度+暂停/继续）→「就绪」；
 * 未就绪时启动按钮置灰（LaunchGate 语义）；无下载规格组件（JRE21 待决策）诚实提示。
 */
@Composable
fun HomeScreen(viewModel: HomeViewModel) {
    val uiState: EnvUiState by viewModel.uiState.collectAsStateWithLifecycle()
    val launchMessage: String? by viewModel.launchMessage.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(launchMessage) {
        launchMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeLaunchMessage()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.home_env_card_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = statusLabel(uiState),
                        style = MaterialTheme.typography.labelMedium,
                        color = statusColor(uiState),
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                uiState.items.forEach { item ->
                    EnvItemRow(
                        item = item,
                        onDownload = { viewModel.download(item.component) },
                        onPause = { viewModel.pause(item.component) },
                        onResume = { viewModel.resume(item.component) },
                        onNoSpec = {
                            Toast.makeText(
                                context,
                                context.getString(R.string.env_no_spec_hint),
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.env_placeholder_rows),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = { viewModel.refresh() }) {
                    Text(text = stringResource(R.string.env_refresh))
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = { viewModel.launch() },
            enabled = uiState.overallReady,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text(
                text = stringResource(
                    if (uiState.overallReady) R.string.home_launch_ready else R.string.home_launch_disabled
                ),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.home_launch_gate_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EnvItemRow(
    item: EnvUiItem,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onNoSpec: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = item.label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = itemStatusLabel(item),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                item.task?.state == DownloadTaskState.RUNNING -> TextButton(onClick = onPause) {
                    Text(text = stringResource(R.string.env_action_pause))
                }
                item.task?.state == DownloadTaskState.PAUSED -> TextButton(onClick = onResume) {
                    Text(text = stringResource(R.string.env_action_resume))
                }
                item.state in ACTIONABLE_STATES -> TextButton(
                    onClick = if (item.hasSpec) onDownload else onNoSpec
                ) {
                    Text(text = stringResource(R.string.env_action_download))
                }
            }
        }
        val task = item.task
        if (task != null && task.state == DownloadTaskState.RUNNING) {
            val total: Long? = task.totalBytes
            if (total != null && total > 0L) {
                val fraction: Float = task.progressBytes.toFloat() / total
                LinearProgressIndicator(
                    progress = { fraction.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                )
            }
        }
    }
}

private val ACTIONABLE_STATES: Set<EnvItemState> = setOf(
    EnvItemState.MISSING, EnvItemState.CORRUPTED,
    EnvItemState.FAILED, EnvItemState.FAILED_PERMANENT,
)

@Composable
private fun itemStatusLabel(item: EnvUiItem): String = when {
    item.task?.state == DownloadTaskState.RUNNING -> stringResource(R.string.env_status_downloading)
    item.task?.state == DownloadTaskState.PAUSED -> stringResource(R.string.env_status_paused)
    item.state == EnvItemState.READY -> stringResource(R.string.env_status_ready)
    item.state == EnvItemState.MISSING && !item.hasSpec -> stringResource(R.string.env_status_spec_pending)
    item.state == EnvItemState.MISSING -> stringResource(R.string.env_status_missing)
    item.state == EnvItemState.CORRUPTED -> stringResource(R.string.env_status_corrupted)
    item.state in setOf(EnvItemState.QUEUED, EnvItemState.VERIFYING, EnvItemState.INSTALLING) ->
        stringResource(R.string.env_status_busy)
    item.state in setOf(EnvItemState.FAILED, EnvItemState.FAILED_PERMANENT) ->
        stringResource(R.string.env_status_failed)
    else -> stringResource(R.string.env_probing)
}

@Composable
private fun statusLabel(uiState: EnvUiState): String = when {
    uiState.probing -> stringResource(R.string.env_probing)
    uiState.overallReady -> stringResource(R.string.env_status_ready)
    uiState.items.any { it.state == EnvItemState.CORRUPTED } -> stringResource(R.string.env_status_corrupted)
    uiState.items.any { it.task?.state == DownloadTaskState.RUNNING } -> stringResource(R.string.env_status_downloading)
    uiState.items.any { it.state != null && it.state != EnvItemState.READY } -> stringResource(R.string.env_status_missing)
    else -> stringResource(R.string.env_probing)
}

@Composable
private fun statusColor(uiState: EnvUiState) = when {
    uiState.probing || uiState.items.any { it.state == null } -> MaterialTheme.colorScheme.onSurfaceVariant
    uiState.overallReady -> MaterialTheme.colorScheme.primary
    uiState.items.any { it.state == EnvItemState.CORRUPTED } -> MaterialTheme.colorScheme.error
    uiState.items.any { it.task?.state == DownloadTaskState.RUNNING } -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
