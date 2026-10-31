package org.aclpe.aclpe.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import org.aclpe.aclpe.core.ThemeMode

/**
 * 应用主题入口：由用户在设置中选择 [ThemeMode]（默认跟随系统）。
 * 不使用 dynamicColor：启动器品牌色需要稳定一致，且 dynamic 在部分 OEM 上不可用。
 */
@Composable
fun AclPeTheme(themeMode: ThemeMode, content: @Composable () -> Unit) {
    val darkTheme: Boolean = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AclPeTypography,
        content = content,
    )
}
