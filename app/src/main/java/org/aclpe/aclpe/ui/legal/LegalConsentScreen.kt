package org.aclpe.aclpe.ui.legal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.aclpe.aclpe.R
import org.aclpe.aclpe.core.legal.LegalDocuments

/**
 * 首次启动强制同意门禁（用户协议第 1 条）：
 * 必须**同时**同意《隐私政策》与《用户协议》两份文件方可进入；拒绝则退出应用（无法进入任何页面）。
 * 两份文件变更（版本号不同）时同样走本页重新同意。
 */
@Composable
fun LegalConsentScreen(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    val context = LocalContext.current
    var selectedTab: Int by remember { mutableStateOf(0) }
    var privacyChecked: Boolean by remember { mutableStateOf(false) }
    var agreementChecked: Boolean by remember { mutableStateOf(false) }

    val privacyText: String = remember { LegalDocuments.privacyPolicy(context) }
    val agreementText: String = remember { LegalDocuments.userAgreement(context) }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.legal_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.legal_intro, LegalDocuments.DEVELOPER, LegalDocuments.VERSION),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))

            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource(R.string.legal_tab_privacy)) },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource(R.string.legal_tab_agreement)) },
                )
            }

            // 文档正文（可滚动、可选中复制；纯文本渲染，含标题与条款编号）
            SelectionContainer(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
            ) {
                Text(
                    text = if (selectedTab == 0) privacyText else agreementText,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // 两份文件各自的同意勾选（必须同时勾选）
            ConsentCheckbox(
                checked = privacyChecked,
                onCheckedChange = { privacyChecked = it },
                label = stringResource(R.string.legal_agree_privacy, LegalDocuments.PRIVACY_TITLE),
            )
            ConsentCheckbox(
                checked = agreementChecked,
                onCheckedChange = { agreementChecked = it },
                label = stringResource(R.string.legal_agree_agreement, LegalDocuments.AGREEMENT_TITLE),
            )

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.legal_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDecline) {
                    Text(
                        text = stringResource(R.string.legal_decline_exit),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(
                    onClick = onAccept,
                    enabled = privacyChecked && agreementChecked,
                ) {
                    Text(stringResource(R.string.legal_accept_continue))
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.legal_contact, LegalDocuments.CONTACT_EMAIL),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ConsentCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}