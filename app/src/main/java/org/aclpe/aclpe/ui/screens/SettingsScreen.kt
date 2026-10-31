package org.aclpe.aclpe.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.aclpe.aclpe.R
import org.aclpe.aclpe.core.AppSettings
import org.aclpe.aclpe.core.ThemeMode

/**
 * 设置页（骨架期）：主题模式 + 崩溃捕获开关。
 * 写操作全部走 [AppSettings] 的 suspend API（DataStore），协程作用域为 rememberCoroutineScope
 * （随组合生命周期取消），不引入 GlobalScope。
 */
@Composable
fun SettingsScreen(appSettings: AppSettings) {
    val themeMode: ThemeMode by appSettings.themeMode
        .collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
    val crashCapture: Boolean by appSettings.crashCaptureEnabled
        .collectAsStateWithLifecycle(initialValue = false)
    val scope = rememberCoroutineScope()

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
    }
}

@StringRes
private fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.settings_theme_system
    ThemeMode.LIGHT -> R.string.settings_theme_light
    ThemeMode.DARK -> R.string.settings_theme_dark
}
