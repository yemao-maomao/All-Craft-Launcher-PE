package org.aclpe.aclpe.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.aclpe.aclpe.R
import org.aclpe.aclpe.core.AppSettings
import org.aclpe.aclpe.core.ThemeMode
import org.aclpe.aclpe.ui.account.AccountViewModel
import org.aclpe.aclpe.ui.settings.GameSettingsViewModel
import org.aclpe.core.account.model.Account
import org.aclpe.core.account.model.AccountType

/**
 * 设置页：启动器（主题/崩溃捕获/仅 Wi-Fi 下载）+ 账号区（Step 4）+ 游戏设置区（Step 5）。
 * 离线账户限制明确标注（无皮肤同步、无法联机、可能违反 EULA），绝不做成默认推荐。
 */
@Composable
fun SettingsScreen(
    appSettings: AppSettings,
    accountViewModel: AccountViewModel,
    gameSettingsViewModel: GameSettingsViewModel,
) {
    val themeMode: ThemeMode by appSettings.themeMode
        .collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
    val crashCapture: Boolean by appSettings.crashCaptureEnabled
        .collectAsStateWithLifecycle(initialValue = false)
    val wifiOnly: Boolean by appSettings.downloadWifiOnly
        .collectAsStateWithLifecycle(initialValue = true)
    val developerMode: Boolean by appSettings.developerMode
        .collectAsStateWithLifecycle(initialValue = false)
    val bgUri: String? by appSettings.backgroundImageUri
        .collectAsStateWithLifecycle(initialValue = null)
    val bgAlpha: Float by appSettings.backgroundAlpha
        .collectAsStateWithLifecycle(initialValue = AppSettings.DEFAULT_BACKGROUND_ALPHA)
    val glassEnabled: Boolean by appSettings.liquidGlassEnabled
        .collectAsStateWithLifecycle(initialValue = true)
    val activeAccount: Account? by accountViewModel.activeAccount.collectAsStateWithLifecycle()
    val accountMessage: String? by accountViewModel.message.collectAsStateWithLifecycle()
    val gameSettingsState by gameSettingsViewModel.uiState.collectAsStateWithLifecycle()
    val gameSettingsMessage: String? by gameSettingsViewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showCreateDialog by remember { mutableStateOf(false) }
    var nameInput by remember { mutableStateOf("") }

    LaunchedEffect(accountMessage) {
        accountMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            accountViewModel.consumeMessage()
        }
    }
    LaunchedEffect(gameSettingsMessage) {
        gameSettingsMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            gameSettingsViewModel.consumeMessage()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_section_launcher),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.settings_theme_mode),
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { mode ->
                FilterChip(
                    selected = themeMode == mode,
                    onClick = { scope.launch { appSettings.setThemeMode(mode) } },
                    label = { Text(stringResource(mode.labelRes())) },
                )
            }
        }

        // 界面自定义（PCL 风格液态玻璃背景，2026-11 重构）
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.settings_ui_custom),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))

        val pickImage = rememberLauncherForActivityResult(
            ActivityResultContracts.PickVisualMedia()
        ) { uri ->
            if (uri != null) scope.launch { appSettings.setBackgroundImageUri(uri.toString()) }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_background_image),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = if (bgUri == null) stringResource(R.string.settings_background_default)
                    else stringResource(R.string.settings_background_custom),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = {
                pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) {
                Text(stringResource(R.string.settings_background_pick))
            }
        }
        if (bgUri != null) {
            TextButton(onClick = { scope.launch { appSettings.setBackgroundImageUri(null) } }) {
                Text(stringResource(R.string.settings_background_reset))
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.settings_background_alpha, (bgAlpha * 100).toInt()),
            style = MaterialTheme.typography.bodyLarge,
        )
        Slider(
            value = bgAlpha,
            onValueChange = { scope.launch { appSettings.setBackgroundAlpha(it) } },
            valueRange = 0f..1f,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_liquid_glass),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.settings_liquid_glass_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = glassEnabled,
                onCheckedChange = { scope.launch { appSettings.setLiquidGlassEnabled(it) } },
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_crash_capture),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.settings_crash_capture_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = crashCapture,
                onCheckedChange = { scope.launch { appSettings.setCrashCaptureEnabled(it) } },
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_wifi_only),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.settings_wifi_only_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = wifiOnly,
                onCheckedChange = { scope.launch { appSettings.setDownloadWifiOnly(it) } },
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_developer_mode),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.settings_developer_mode_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = developerMode,
                onCheckedChange = { scope.launch { appSettings.setDeveloperMode(it) } },
            )
        }

        // ---- 下载与镜像（2026-11-02 新增：切换后下载立即使用所选镜像） ----
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.settings_section_mirror),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.settings_origin_mirror),
            style = MaterialTheme.typography.bodyLarge,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("OFFICIAL" to "官方源（慢）", "BMCLAPI" to "BMCLAPI 镜像（推荐）").forEach { (id, label) ->
                FilterChip(
                    selected = gameSettingsState.launcher.mirrorId == id,
                    onClick = { gameSettingsViewModel.setOriginMirror(id) },
                    label = { Text(label) },
                )
            }
        }
        Text(
            text = stringResource(R.string.settings_origin_mirror_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.settings_community_mirror),
            style = MaterialTheme.typography.bodyLarge,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("OFFICIAL" to "Modrinth 官方", "MCIMIRROR" to "MCMirror 镜像").forEach { (id, label) ->
                FilterChip(
                    selected = gameSettingsState.launcher.communityMirrorId == id,
                    onClick = { gameSettingsViewModel.setCommunityMirror(id) },
                    label = { Text(label) },
                )
            }
        }
        Text(
            text = stringResource(R.string.settings_community_mirror_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.settings_section_account),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))

        val account: Account? = activeAccount
        if (account == null) {
            Text(
                text = stringResource(R.string.account_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = account.username,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(
                        if (account.type == AccountType.LOCAL_OFFLINE) R.string.account_type_offline else R.string.account_type_microsoft
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (account.type == AccountType.LOCAL_OFFLINE) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.account_offline_limitations),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { showCreateDialog = true }) {
                Text(text = stringResource(R.string.account_create_offline))
            }
            Button(onClick = {}, enabled = false) {
                Text(text = stringResource(R.string.account_ms_login))
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.account_ms_reserved),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.settings_section_game),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        GameSettingsSection(gameSettingsViewModel)
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text(stringResource(R.string.account_create_offline)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.account_offline_limitations),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.account_name_hint)) },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        accountViewModel.createOfflineAccount(nameInput)
                        showCreateDialog = false
                        nameInput = ""
                    },
                ) {
                    Text(stringResource(R.string.account_create_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text(stringResource(R.string.account_cancel))
                }
            },
        )
    }
}

@StringRes
private fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.settings_theme_system
    ThemeMode.LIGHT -> R.string.settings_theme_light
    ThemeMode.DARK -> R.string.settings_theme_dark
}
