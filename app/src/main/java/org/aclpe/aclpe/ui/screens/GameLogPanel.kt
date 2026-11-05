package org.aclpe.aclpe.ui.screens

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 运行日志面板（PCL 风格右侧栏，2026-11 重构）：
 * 启动游戏时右侧栏显示实时运行日志；退出后日志自动保存到系统「下载」目录。
 */
@Composable
fun GameLogPanel(logLines: List<String>, running: Boolean, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()

    // 自动滚动到底部（跟随最新日志）
    LaunchedEffect(logLines.size) {
        if (logLines.isNotEmpty()) listState.animateScrollToItem(logLines.size - 1)
    }

    Surface(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "运行日志",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (running) "● 运行中" else "○ 已结束",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (running) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            if (logLines.isEmpty()) {
                Text(
                    text = "暂无日志。点击左侧启动按钮开始。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(logLines) { line: String ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                            ),
                            modifier = Modifier.padding(vertical = 1.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 将运行日志保存到系统「下载」目录（MediaStore.Downloads，无需存储权限）。
 * @return 保存后的显示名；失败返回 null。
 */
fun saveLaunchLogToDownloads(context: Context, lines: List<String>): String? {
    if (lines.isEmpty()) return null
    return try {
        val ts: String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val displayName: String = "aclpe-launch-$ts.log"
        val values: ContentValues = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return null
        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(lines.joinToString("\n").toByteArray(Charsets.UTF_8))
        } ?: return null
        displayName
    } catch (_: Throwable) {
        null
    }
}