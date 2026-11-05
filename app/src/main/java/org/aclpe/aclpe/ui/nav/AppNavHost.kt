package org.aclpe.aclpe.ui.nav

import android.widget.Toast
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aclpe.aclpe.core.AppSettings
import org.aclpe.aclpe.core.ThemeMode
import org.aclpe.aclpe.core.env.SimpleRuntimeInstaller
import org.aclpe.aclpe.ui.account.AccountViewModel
import org.aclpe.aclpe.ui.community.CommunityViewModel
import org.aclpe.aclpe.ui.home.HomeViewModel
import org.aclpe.aclpe.ui.screens.AboutScreen
import org.aclpe.aclpe.ui.screens.CommunityScreen
import org.aclpe.aclpe.ui.screens.DownloadActivity
import org.aclpe.aclpe.ui.screens.DownloadFab
import org.aclpe.aclpe.ui.screens.GameLogPanel
import org.aclpe.aclpe.ui.screens.HomeScreen
import org.aclpe.aclpe.ui.screens.OfficialWebView
import org.aclpe.aclpe.ui.screens.SettingsScreen
import org.aclpe.aclpe.ui.screens.VersionsScreen
import org.aclpe.aclpe.ui.screens.saveLaunchLogToDownloads
import org.aclpe.aclpe.ui.settings.GameSettingsViewModel
import org.aclpe.aclpe.ui.theme.GlassBackground
import org.aclpe.aclpe.ui.versions.VersionsViewModel
import org.aclpe.core.download.install.VersionInstaller
import org.aclpe.core.download.loader.ModLoaderInstaller

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
 * - 液态玻璃背景（背景图不透明度 + 毛玻璃，深浅色主题适配）；
 * - 右栏「主页」标签：官网开关开启时才加载 WebView（默认关闭，启动不加载网页）。
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
    val officialEnabled by appSettings.officialSiteEnabled.collectAsStateWithLifecycle(initialValue = false)
    val themeMode by appSettings.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
    val darkTheme: Boolean = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    var tab by remember { mutableStateOf(PclTab.HOME) }

    // 聚合各下载源活跃任务数（悬浮球显示）
    val homeUi by homeViewModel.uiState.collectAsStateWithLifecycle()
    val versionsUi by versionsViewModel.uiState.collectAsStateWithLifecycle()
    val communityUi by communityViewModel.uiState.collectAsStateWithLifecycle()
    val downloadActivity = DownloadActivity(
        envInstalling = homeUi.envItems.count { it.phase == SimpleRuntimeInstaller.Phase.INSTALLING },
        versionInstalling = versionsUi.installing.count { (_, s) ->
            s.phase !in setOf(VersionInstaller.Phase.IDLE, VersionInstaller.Phase.DONE, VersionInstaller.Phase.FAILED)
        },
        loaderInstalling = communityUi.loaderInstallStates.count { (_, s) ->
            s.phase !in setOf(ModLoaderInstaller.Phase.IDLE, ModLoaderInstaller.Phase.DONE, ModLoaderInstaller.Phase.FAILED)
        },
        modDownloading = communityUi.modDownloads.count { it.value < 1f },
    )

    GlassBackground(
        imageUri = bgUri,
        imageAlpha = bgAlpha,
        liquidGlassEnabled = glassEnabled,
        darkTheme = darkTheme,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
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
                    officialEnabled = officialEnabled,
                    modifier = Modifier.weight(0.62f),
                )
            }
            DownloadFab(activity = downloadActivity)
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
    officialEnabled: Boolean,
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
                PclTab.HOME -> if (officialEnabled) {
                    OfficialWebView(url = "https://yemao.ct.ws/", modifier = Modifier.fillMaxSize())
                } else {
                    OfficialSiteDisabledPlaceholder()
                }
                PclTab.VERSIONS -> VersionsScreen(versionsViewModel)
                PclTab.COMMUNITY -> CommunityScreen(communityViewModel)
                PclTab.SETTINGS -> SettingsScreen(appSettings, accountViewModel, gameSettingsViewModel)
                PclTab.ABOUT -> AboutScreen()
            }
        }
    }
}

/** 官网未启用时的占位（默认状态，不加载网页）。 */
@Composable
private fun OfficialSiteDisabledPlaceholder() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Text(
            text = "官网未启用",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "可在「设置 → 网络」中开启「连接官网」",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}