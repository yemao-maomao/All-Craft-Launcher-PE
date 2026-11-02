package org.aclpe.aclpe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.aclpe.aclpe.core.AbiGuard
import org.aclpe.aclpe.core.AppSettings
import org.aclpe.aclpe.core.ThemeMode
import org.aclpe.aclpe.core.legal.LegalDocuments
import org.aclpe.aclpe.ui.account.AccountViewModel
import org.aclpe.aclpe.ui.community.CommunityViewModel
import org.aclpe.aclpe.ui.home.HomeViewModel
import org.aclpe.aclpe.ui.legal.LegalConsentScreen
import org.aclpe.aclpe.ui.nav.AppNavHost
import org.aclpe.aclpe.ui.settings.GameSettingsViewModel
import org.aclpe.aclpe.ui.theme.AclPeTheme
import org.aclpe.aclpe.ui.versions.VersionsViewModel

/** 单 Activity 壳：ABI 守卫 + **法务同意门禁** + 主题 + 导航宿主。 */
class MainActivity : ComponentActivity() {

    private val appSettings: AppSettings by lazy { AppSettings(applicationContext) }
    private val homeViewModel: HomeViewModel by viewModels()
    private val accountViewModel: AccountViewModel by viewModels()
    private val gameSettingsViewModel: GameSettingsViewModel by viewModels()
    private val communityViewModel: CommunityViewModel by viewModels()
    private val versionsViewModel: VersionsViewModel by viewModels()

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
            // 法务同意门禁（用户协议第 1 条）：未同意（或版本变更）→ 只渲染同意页，无法进入任何功能
            val acceptedVersion: String? by appSettings.legalAcceptedVersion
                .collectAsStateWithLifecycle(initialValue = LegalDocuments.NOT_LOADED)
            val scope = rememberCoroutineScope()
            val context = LocalContext.current

            AclPeTheme(themeMode) {
                when (acceptedVersion) {
                    LegalDocuments.NOT_LOADED -> LegalCheckingScreen()
                    LegalDocuments.VERSION -> AppNavHost(
                        appSettings = appSettings,
                        homeViewModel = homeViewModel,
                        accountViewModel = accountViewModel,
                        gameSettingsViewModel = gameSettingsViewModel,
                        communityViewModel = communityViewModel,
                        versionsViewModel = versionsViewModel,
                    )
                    else -> LegalConsentScreen(
                        onAccept = {
                            scope.launch {
                                appSettings.acceptLegalDocuments(LegalDocuments.VERSION)
                            }
                        },
                        onDecline = {
                            // 拒绝：不允许进入任何功能 → 结束应用
                            (context as? ComponentActivity)?.finish()
                        },
                    )
                }
            }
        }
    }
}

/** 读取同意状态期间的中间态（避免首帧误判为"已同意"而闪过主界面）。 */
@Composable
private fun LegalCheckingScreen() {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        ) {
            CircularProgressIndicator()
            Text(
                text = stringResource(R.string.legal_checking),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}