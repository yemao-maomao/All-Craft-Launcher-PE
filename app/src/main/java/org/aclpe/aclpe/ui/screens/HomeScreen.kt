package org.aclpe.aclpe.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aclpe.aclpe.R
import org.aclpe.aclpe.core.env.SimpleRuntimeInstaller
import org.aclpe.aclpe.ui.home.HomeViewModel
import org.aclpe.core.account.model.Account
import org.aclpe.core.account.model.AccountType
import org.aclpe.core.env.store.VersionEntity

/**
 * PCL 风格左栏启动区（2026-11 重构）：
 * 账户头像 → 版本名（点击切换）→ 圆形大启动按钮 → 环境状态小字。
 * 占横屏左半部分（约 38% 宽）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel, onNavigateToVersions: () -> Unit, modifier: Modifier = Modifier) {
    val state: HomeViewModel.HomeUiState by viewModel.uiState.collectAsStateWithLifecycle()
    val launchMessage: String? by viewModel.launchMessage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showVersionPicker by remember { mutableStateOf(false) }

    LaunchedEffect(launchMessage) {
        launchMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.consumeLaunchMessage()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // 账户头像区
        AccountAvatar(account = state.account)
        Spacer(modifier = Modifier.height(16.dp))

        // 版本名（点击切换）
        TextButton(onClick = { showVersionPicker = true }) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = state.effectiveVersion?.id ?: stringResource(R.string.home_no_version),
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.home_tap_switch_version),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        // 圆形大启动按钮（PCL 风格）
        Button(
            onClick = { viewModel.launch() },
            enabled = state.overallReady,
            shape = CircleShape,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            modifier = Modifier.size(120.dp),
        ) {
            Text(
                text = stringResource(if (state.overallReady) R.string.home_launch_ready else R.string.home_launch_disabled),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))

        // 环境状态
        Text(
            text = if (state.overallReady) stringResource(R.string.env_status_ready) else envStatusLine(state),
            style = MaterialTheme.typography.bodySmall,
            color = if (state.overallReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (!state.overallReady) {
            TextButton(
                onClick = {
                    state.envItems
                        .filter { it.slot != 8 && it.phase != SimpleRuntimeInstaller.Phase.READY }
                        .forEach { viewModel.install(it.slot) }
                },
            ) {
                Text(stringResource(R.string.home_retry_env))
            }
        }
        // Java 8 选择性安装（独立按钮，需手动触发走网络下载）
        val java8: HomeViewModel.EnvUiItem? = state.envItems.firstOrNull { it.slot == 8 }
        if (java8 != null && java8.phase != SimpleRuntimeInstaller.Phase.READY) {
            TextButton(onClick = { viewModel.install(8) }) {
                Text(
                    text = if (java8.phase == SimpleRuntimeInstaller.Phase.INSTALLING) {
                        stringResource(R.string.home_java8_installing)
                    } else {
                        stringResource(R.string.home_java8_install)
                    },
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }

    if (showVersionPicker) {
        ModalBottomSheet(onDismissRequest = { showVersionPicker = false }) {
            VersionPickerSheet(
                state = state,
                onSelect = { id ->
                    viewModel.selectVersion(id)
                    showVersionPicker = false
                },
                onNavigateToVersions = {
                    showVersionPicker = false
                    onNavigateToVersions()
                },
            )
        }
    }
}

/** 账户圆形头像（占位：显示用户名首字符）。 */
@Composable
private fun AccountAvatar(account: Account?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = account?.username?.take(1)?.uppercase() ?: "?",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = account?.username ?: stringResource(R.string.home_account_none),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = when (account?.type) {
                AccountType.LOCAL_OFFLINE -> stringResource(R.string.account_type_offline)
                AccountType.MICROSOFT -> stringResource(R.string.account_type_microsoft)
                null -> stringResource(R.string.home_account_hint)
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun VersionPickerSheet(
    state: HomeViewModel.HomeUiState,
    onSelect: (String) -> Unit,
    onNavigateToVersions: () -> Unit,
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(text = stringResource(R.string.home_select_version), style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        if (state.installedVersions.isEmpty()) {
            Text(
                text = stringResource(R.string.home_no_installed_version),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn {
                items(state.installedVersions, key = { it.id }) { version: VersionEntity ->
                    val selected: Boolean = version.id == state.effectiveVersion?.id
                    Surface(
                        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .clickable { onSelect(version.id) },
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = version.id, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = version.type,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (selected) {
                                Text(
                                    text = stringResource(R.string.env_status_ready),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onNavigateToVersions, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.home_go_download_version))
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun envStatusLine(state: HomeViewModel.HomeUiState): String {
    val notReady: List<String> = state.envItems
        .filter { it.slot != 8 && it.phase != SimpleRuntimeInstaller.Phase.READY }
        .map { it.label }
    return if (notReady.isEmpty()) stringResource(R.string.env_status_ready)
    else "环境未就绪：${notReady.joinToString("、")}"
}