package org.aclpe.aclpe.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// ACL 品牌绿（Material Green 系）。图标、品牌色统一由此派生，避免各页面散落硬编码。

internal val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF2E7D32),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFA5D6A7),
    onPrimaryContainer = Color(0xFF0B3D0F),
    secondary = Color(0xFF558B2F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCEDC8),
    onSecondaryContainer = Color(0xFF1B3000),
)

internal val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF81C784),
    onPrimary = Color(0xFF0B3D0F),
    primaryContainer = Color(0xFF1B5E20),
    onPrimaryContainer = Color(0xFFA5D6A7),
    secondary = Color(0xFFAED581),
    onSecondary = Color(0xFF1B3000),
    secondaryContainer = Color(0xFF33691E),
    onSecondaryContainer = Color(0xFFDCEDC8),
)
