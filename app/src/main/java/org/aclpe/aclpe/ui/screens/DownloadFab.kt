package org.aclpe.aclpe.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 各下载源的活跃任务数。 */
data class DownloadActivity(
    val envInstalling: Int = 0,
    val versionInstalling: Int = 0,
    val loaderInstalling: Int = 0,
    val modDownloading: Int = 0,
) {
    val total: Int get() = envInstalling + versionInstalling + loaderInstalling + modDownloading
    val isActive: Boolean get() = total > 0
}

/**
 * 右下角悬浮球：显示下载状态（活跃任务数徽标），点击展开明细面板。
 */
@Composable
fun DownloadFab(activity: DownloadActivity, modifier: Modifier = Modifier) {
    var showPanel by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        FloatingActionButton(
            onClick = { showPanel = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) {
            if (activity.isActive) {
                BadgedBox(badge = { Badge { Text(activity.total.toString()) } }) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "下载状态")
                }
            } else {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "下载状态")
            }
        }
    }

    if (showPanel) {
        AlertDialog(
            onDismissRequest = { showPanel = false },
            title = { Text(if (activity.isActive) "下载进行中" else "当前无下载任务") },
            text = {
                Column {
                    ActivityRow("运行环境安装", activity.envInstalling)
                    ActivityRow("版本安装", activity.versionInstalling)
                    ActivityRow("模组加载器安装", activity.loaderInstalling)
                    ActivityRow("模组下载", activity.modDownloading)
                }
            },
            confirmButton = {
                TextButton(onClick = { showPanel = false }) { Text("关闭") }
            },
        )
    }
}

@Composable
private fun ActivityRow(label: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            text = if (count > 0) "$count 个任务" else "空闲",
            style = MaterialTheme.typography.bodySmall,
            color = if (count > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}