package org.aclpe.aclpe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aclpe.aclpe.core.AbiGuard
import org.aclpe.aclpe.core.AppSettings
import org.aclpe.aclpe.core.ThemeMode
import org.aclpe.aclpe.ui.home.HomeViewModel
import org.aclpe.aclpe.ui.nav.AppNavHost
import org.aclpe.aclpe.ui.theme.AclPeTheme

/** 单 Activity 壳：ABI 守卫 + 主题 + 导航宿主。 */
class MainActivity : ComponentActivity() {

    private val appSettings: AppSettings by lazy { AppSettings(applicationContext) }
    private val homeViewModel: HomeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 运行时 ABI 守卫：abiFilters 已保证非 arm64 装不上，此处兜底 split 安装等边角场景
        if (!AbiGuard.isArm64Supported()) {
            AbiGuard.showUnsupportedDialog(this) { finish() }
            return
        }

        setContent {
            val themeMode: ThemeMode by appSettings.themeMode
                .collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            AclPeTheme(themeMode) {
                AppNavHost(appSettings = appSettings, homeViewModel = homeViewModel)
            }
        }
    }
}
