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
import org.aclpe.aclpe.core.env.SimpleRuntimeInstaller
import org.aclpe.aclpe.ui.home.HomeViewModel

/**
 * 首页：环境卡片（FCL 式线性部署，2026-11-02 重写）。
 * 每行四态：未安装 / 安装中（进度条）/ ✓ 就绪 / 失败（错误码 + 重试按钮）。
 * 完成即 READY——不再有「处理中」。
 */
@Composable
fun HomeScreen(viewModel: HomeViewModel) {
    val uiState: HomeViewModel.EnvUiState by viewModel.uiState.collectAsStateWithLifecycle()
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
                        text = if (uiState.overallReady) {
                            stringResource(R.string.env_status_ready)
                        } else {
                            stringResource(R.string.env_status_missing)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (uiState.overallReady) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                uiState.items.forEach { item: HomeViewModel.EnvUiItem ->
                    RuntimeRow(item = item, onInstall = { viewModel.install(item.slot) })
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.env_runtime_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
private fun RuntimeRow(item: HomeViewModel.EnvUiItem, onInstall: () -> Unit) {
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
            when (item.phase) {
                SimpleRuntimeInstaller.Phase.READY ->
                    Text(
                        text = stringResource(R.string.env_status_ready),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                SimpleRuntimeInstaller.Phase.INSTALLING -> {
                    Text(
                        text = stringResource(R.string.env_status_installing),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = {}) {
                        Text(text = stringResource(R.string.env_status_installing))
                    }
                }
                SimpleRuntimeInstaller.Phase.FAILED -> {
                    Text(
                        text = stringResource(R.string.env_status_failed) +
                            (item.errorCode?.let { "（$it）" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = onInstall) {
                        Text(text = stringResource(R.string.env_action_retry))
                    }
                }
                SimpleRuntimeInstaller.Phase.NOT_INSTALLED -> {
                    Text(
                        text = stringResource(R.string.env_status_missing),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onInstall) {
                        Text(text = stringResource(R.string.env_action_install))
                    }
                }
            }
        }
        if (item.phase == SimpleRuntimeInstaller.Phase.INSTALLING) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
            )
        }
    }
}
