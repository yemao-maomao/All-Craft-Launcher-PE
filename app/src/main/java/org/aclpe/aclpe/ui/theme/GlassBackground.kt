package org.aclpe.aclpe.ui.theme

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * 液态玻璃背景层（PCL 风格，2026-11 重构）：
 * - 底层：用户自定义背景图（相册 URI）或内置深色渐变；
 * - 中层：毛玻璃模糊（API 31+ 真 blur；低版本自动降级为纯半透明，无真模糊）；
 * - 上层：[alpha] 控制背景明暗遮罩（1.0 = 背景完全不透明，0 = 完全透明）；
 * - 内容绘制于最上层（[content]）。
 */
@Composable
fun GlassBackground(
    imageUri: String?,
    alpha: Float,
    liquidGlassEnabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val context: Context = LocalContext.current
    val bitmap: ImageBitmap? = remember(imageUri) { loadBackground(context, imageUri) }

    Box(modifier = modifier.fillMaxSize()) {
        // 背景层
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (liquidGlassEnabled) Modifier.blur(BLUR_RADIUS) else Modifier
                    ),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF1A1D24), Color(0xFF0D1117)),
                        )
                    ),
            )
        }
        // 明暗遮罩（alpha 越大背景越清晰/越不透明）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0D1117).copy(alpha = (1f - alpha).coerceIn(0f, 1f))),
        )
        content()
    }
}

private fun loadBackground(context: Context, uri: String?): ImageBitmap? {
    if (uri.isNullOrBlank()) return null
    return try {
        val stream = context.contentResolver.openInputStream(Uri.parse(uri)) ?: return null
        BitmapFactory.decodeStream(stream)?.asImageBitmap()
    } catch (_: Throwable) {
        null
    }
}

private val BLUR_RADIUS = 24.dp
