package org.aclpe.aclpe.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
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
 * 设置页（2026-11 分类可折叠重构）：
 * 分类：界面 / 启动器 / 下载 / 网络 / 账号 / 游戏，每类可折叠。
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
    val downloadThreads: Int by appSettings.downloadThreads
        .collectAsStateWithLifecycle(initialValue = AppSettings.DEFAULT_DOWNLOAD_THREADS)
    val officialEnabled: Boolean by appSettings.officialSiteEnabled
        .collectAsStateWithLifecycle(initialValue = false)
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
        // ---- 界面 ----
        CollapsibleSection(title = stringResource(R.string.settings_cat_ui), initiallyExpanded = true) {
            Text(stringResource(R.string.settings_theme_mode), style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = themeMode == mode,
                        onClick = { scope.launch { appSettings.setThemeMode(mode) } },
                        label = { Text(stringResource(mode.labelRes())) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            val pickImage = rememberLauncherForActivityResult(
                ActivityResultContracts.PickVisualMedia()
            ) { uri ->
                if (uri != null) scope.launch { appSettings.setBackgroundImageUri(uri.toString()) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_background_image), style = MaterialTheme.typography.bodyLarge)
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
            val alphaLocal = remember { mutableStateOf(bgAlpha) }
            LaunchedEffect(bgAlpha) { alphaLocal.value = bgAlpha }
            Text(
                text = stringResource(R.string.settings_background_alpha, (alphaLocal.value * 100).toInt()),
                style = MaterialTheme.typography.bodyLarge,
            )
            Slider(
                value = alphaLocal.value,
                onValueChange = { alphaLocal.value = it },
                onValueChangeFinished = { scope.launch { appSettings.setBackgroundAlpha(alphaLocal.value) } },
                valueRange = 0f..1f,
            )

            SwitchRow(
                title = stringResource(R.string.settings_liquid_glass),
                desc = stringResource(R.string.settings_liquid_glass_desc),
                checked = glassEnabled,
                onCheckedChange = { scope.launch { appSettings.setLiquidGlassEnabled(it) } },
            )
        }

        // ---- 启动器 ----
        CollapsibleSection(title = stringResource(R.string.settings_section_launcher), initiallyExpanded = true) {
            SwitchRow(
                title = stringResource(R.string.settings_crash_capture),
                desc = stringResource(R.string.settings_crash_capture_desc),
                checked = crashCapture,
                onCheckedChange = { scope.launch { appSettings.setCrashCaptureEnabled(it) } },
            )
            Spacer(modifier = Modifier.height(12.dp))
            SwitchRow(
                title = stringResource(R.string.settings_wifi_only),
                desc = stringResource(R.string.settings_wifi_only_desc),
                checked = wifiOnly,
                onCheckedChange = { scope.launch { appSettings.setDownloadWifiOnly(it) } },
            )
            Spacer(modifier = Modifier.height(12.dp))
            SwitchRow(
                title = stringResource(R.string.settings_developer_mode),
                desc = stringResource(R.string.settings_developer_mode_desc),
                checked = developerMode,
                onCheckedChange = { scope.launch { appSettings.setDeveloperMode(it) } },
            )
        }

        // ---- 下载 ----
        CollapsibleSection(title = stringResource(R.string.settings_cat_download), initiallyExpanded = true) {
            Text(stringResource(R.string.settings_origin_mirror), style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("OFFICIAL" to "官方源", "BMCLAPI" to "BMCLAPI（推荐）").forEach { (id, label) ->
                    FilterChip(
                        selected = gameSettingsState.launcher.mirrorId == id,
                        onClick = { gameSettingsViewModel.setOriginMirror(id) },
                        label = { Text(label) },
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(stringResource(R.string.settings_community_mirror), style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("OFFICIAL" to "Modrinth 官方", "MCIMIRROR" to "MCMirror").forEach { (id, label) ->
                    FilterChip(
                        selected = gameSettingsState.launcher.communityMirrorId == id,
                        onClick = { gameSettingsViewModel.setCommunityMirror(id) },
                        label = { Text(label) },
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            val threadsLocal = remember { mutableStateOf(downloadThreads) }
            LaunchedEffect(downloadThreads) { threadsLocal.value = downloadThreads }
            Text(
                text = stringResource(R.string.settings_download_threads, threadsLocal.value),
                style = MaterialTheme.typography.bodyLarge,
            )
            Slider(
                value = threadsLocal.value.toFloat(),
                onValueChange = { threadsLocal.value = it.toInt() },
                onValueChangeFinished = { scope.launch { appSettings.setDownloadThreads(threadsLocal.value) } },
                valueRange = AppSettings.MIN_DOWNLOAD_THREADS.toFloat()..AppSettings.MAX_DOWNLOAD_THREADS.toFloat(),
                steps = AppSettings.MAX_DOWNLOAD_THREADS - AppSettings.MIN_DOWNLOAD_THREADS - 1,
            )
            Text(
                text = stringResource(R.string.settings_download_threads_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ---- 网络 ----
        CollapsibleSection(title = stringResource(R.string.settings_cat_network), initiallyExpanded = false) {
            SwitchRow(
                title = stringResource(R.string.settings_official_site),
                desc = stringResource(R.string.settings_official_site_desc),
                checked = officialEnabled,
                onCheckedChange = { scope.launch { appSettings.setOfficialSiteEnabled(it) } },
            )
        }

        // ---- 账号 ----
        CollapsibleSection(title = stringResource(R.string.settings_section_account), initiallyExpanded = false) {
            val account: Account? = activeAccount
            if (account == null) {
                Text(
                    text = stringResource(R.string.account_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(text = account.username, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
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
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showCreateDialog = true }) {
                    Text(stringResource(R.string.account_create_offline))
                }
                Button(onClick = {}, enabled = false) {
                    Text(stringResource(R.string.account_ms_login))
                }
            }
        }

        // ---- 游戏 ----
        CollapsibleSection(title = stringResource(R.string.settings_section_game), initiallyExpanded = false) {
            GameSettingsSection(gameSettingsViewModel)
        }
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

/** 可折叠分类标题。 */
@Composable
private fun CollapsibleSection(
    title: String,
    initiallyExpanded: Boolean,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (expanded) "▲" else "▼",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            content()
        }
    }
}

/** 开关行（标题 + 描述 + Switch）。 */
@Composable
private fun SwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@StringRes
private fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.settings_theme_system
    ThemeMode.LIGHT -> R.string.settings_theme_light
    ThemeMode.DARK -> R.string.settings_theme_dark
}