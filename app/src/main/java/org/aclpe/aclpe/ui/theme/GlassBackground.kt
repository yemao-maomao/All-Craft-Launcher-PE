package org.aclpe.aclpe.ui.theme

import android.content.Context
import android.graphics.Bitmap
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
 * - 底层：用户自定义背景图（相册 URI）或内置渐变（随深浅色主题切换明暗）；
 * - [imageAlpha] 是**背景图片本身的不透明度**（0=完全透明，1=完全不透明）；
 * - 中层：毛玻璃模糊（API 31+ 真 blur；低版本自动降级为半透明）；
 * - 上层：固定明暗遮罩（保证前景文字可读，不再由用户滑块控制）；
 * - 内容绘制于最上层（[content]）。
 */
@Composable
fun GlassBackground(
    imageUri: String?,
    imageAlpha: Float,
    liquidGlassEnabled: Boolean,
    darkTheme: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val context: Context = LocalContext.current
    val bitmap: ImageBitmap? = remember(imageUri) { loadBackground(context, imageUri) }

    val gradient: List<Color> = if (darkTheme) {
        listOf(Color(0xFF1A1D24), Color(0xFF0D1117))
    } else {
        listOf(Color(0xFFFFFFFF), Color(0xFFE4E8EE))
    }
    val overlay: Color = if (darkTheme) Color(0xFF0D1117) else Color(0xFFF7F9FB)

    Box(modifier = modifier.fillMaxSize()) {
        // 背景层（图片或渐变；imageAlpha 控制图片不透明度）
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = imageAlpha.coerceIn(0f, 1f),
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (liquidGlassEnabled) Modifier.blur(BLUR_RADIUS) else Modifier),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(gradient)),
            )
        }
        // 固定明暗遮罩（保证文字可读；不再由用户调节）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(overlay.copy(alpha = OVERLAY_ALPHA)),
        )
        content()
    }
}

private fun loadBackground(context: Context, path: String?): ImageBitmap? {
    if (path.isNullOrBlank()) return null
    return try {
        val bitmap: Bitmap? = if (path.startsWith("/")) {
            // 本地文件路径（应用私有目录，权限持久）
            val f = java.io.File(path)
            if (f.isFile) BitmapFactory.decodeFile(path) else null
        } else {
            // content URI（旧数据或复制失败回退）
            val stream = context.contentResolver.openInputStream(Uri.parse(path)) ?: return null
            BitmapFactory.decodeStream(stream)
        }
        bitmap?.asImageBitmap()
    } catch (_: Throwable) {
        null
    }
}

private val BLUR_RADIUS = 24.dp

/** 固定遮罩不透明度：保证前景文字在任意背景图上都可读。 */
private const val OVERLAY_ALPHA = 0.30f
