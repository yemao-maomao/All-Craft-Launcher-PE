package org.aclpe.aclpe.ui.nav

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aclpe.aclpe.core.AppSettings
import org.aclpe.aclpe.ui.account.AccountViewModel
import org.aclpe.aclpe.ui.community.CommunityViewModel
import org.aclpe.aclpe.ui.home.HomeViewModel
import org.aclpe.aclpe.ui.screens.AboutScreen
import org.aclpe.aclpe.ui.screens.CommunityScreen
import org.aclpe.aclpe.ui.screens.GameLogPanel
import org.aclpe.aclpe.ui.screens.HomeScreen
import org.aclpe.aclpe.ui.screens.OfficialWebView
import org.aclpe.aclpe.ui.screens.SettingsScreen
import org.aclpe.aclpe.ui.screens.VersionsScreen
import org.aclpe.aclpe.ui.screens.saveLaunchLogToDownloads
import org.aclpe.aclpe.ui.settings.GameSettingsViewModel
import org.aclpe.aclpe.ui.theme.GlassBackground
import org.aclpe.aclpe.ui.versions.VersionsViewModel

/** 右侧标签页。 */
enum class PclTab(val label: String) {
    HOME("主页"),
    VERSIONS("版本"),
    COMMUNITY("社区"),
    SETTINGS("设置"),
    ABOUT("关于"),
}

/**
 * PCL 风格导航宿主（2026-11 推倒重做）：
 * - 横屏布局：左栏启动区（约 38%）+ 右栏标签页（约 62%）；
 * - 液态玻璃背景（用户自定义背景图 + 透明度 + 毛玻璃）；
 * - 右栏「主页」标签内嵌官网 WebView；启动游戏时自动切到「运行日志」，退出后日志存 Downloads。
 */
@Composable
fun AppNavHost(
    appSettings: AppSettings,
    homeViewModel: HomeViewModel,
    accountViewModel: AccountViewModel,
    gameSettingsViewModel: GameSettingsViewModel,
    communityViewModel: CommunityViewModel,
    versionsViewModel: VersionsViewModel,
) {
    val bgUri by appSettings.backgroundImageUri.collectAsStateWithLifecycle(initialValue = null)
    val bgAlpha by appSettings.backgroundAlpha.collectAsStateWithLifecycle(initialValue = AppSettings.DEFAULT_BACKGROUND_ALPHA)
    val glassEnabled by appSettings.liquidGlassEnabled.collectAsStateWithLifecycle(initialValue = true)
    var tab by remember { mutableStateOf(PclTab.HOME) }

    GlassBackground(imageUri = bgUri, alpha = bgAlpha, liquidGlassEnabled = glassEnabled) {
        Row(modifier = Modifier.fillMaxSize()) {
            HomeScreen(
                viewModel = homeViewModel,
                onNavigateToVersions = { tab = PclTab.VERSIONS },
                modifier = Modifier.weight(0.38f),
            )
            PclRightPanel(
                tab = tab,
                onTabChange = { tab = it },
                homeViewModel = homeViewModel,
                versionsViewModel = versionsViewModel,
                communityViewModel = communityViewModel,
                accountViewModel = accountViewModel,
                gameSettingsViewModel = gameSettingsViewModel,
                appSettings = appSettings,
                modifier = Modifier.weight(0.62f),
            )
        }
    }
}

@Composable
private fun PclRightPanel(
    tab: PclTab,
    onTabChange: (PclTab) -> Unit,
    homeViewModel: HomeViewModel,
    versionsViewModel: VersionsViewModel,
    communityViewModel: CommunityViewModel,
    accountViewModel: AccountViewModel,
    gameSettingsViewModel: GameSettingsViewModel,
    appSettings: AppSettings,
    modifier: Modifier = Modifier,
) {
    val running by homeViewModel.running.collectAsStateWithLifecycle()
    val logLines by homeViewModel.logLines.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 退出后保存日志到 Downloads（running true→false 过渡）
    var wasRunning by remember { mutableStateOf(false) }
    LaunchedEffect(running) {
        if (wasRunning && !running) {
            val saved: String? = saveLaunchLogToDownloads(context, logLines)
            if (saved != null) {
                Toast.makeText(context, "日志已保存到下载目录：$saved", Toast.LENGTH_LONG).show()
            }
        }
        wasRunning = running
    }

    Column(modifier = modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab.ordinal, containerColor = Color.Transparent) {
            PclTab.entries.forEach { t ->
                Tab(
                    selected = tab == t,
                    onClick = { onTabChange(t) },
                    text = { Text(t.label, style = MaterialTheme.typography.labelLarge) },
                )
            }
        }
        // 内容区：运行时强制日志；否则按 tab
        if (running) {
            GameLogPanel(logLines = logLines, running = true, modifier = Modifier.fillMaxSize())
        } else {
            when (tab) {
                PclTab.HOME -> OfficialWebView(url = "https://yemao.ct.ws/", modifier = Modifier.fillMaxSize())
                PclTab.VERSIONS -> VersionsScreen(versionsViewModel)
                PclTab.COMMUNITY -> CommunityScreen(communityViewModel)
                PclTab.SETTINGS -> SettingsScreen(appSettings, accountViewModel, gameSettingsViewModel)
                PclTab.ABOUT -> AboutScreen()
            }
        }
    }
}