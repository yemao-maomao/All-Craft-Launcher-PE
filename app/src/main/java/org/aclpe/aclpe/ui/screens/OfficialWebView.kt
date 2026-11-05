package org.aclpe.aclpe.ui.screens

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 官网内嵌 WebView（PCL 风格「主页」标签页，2026-11 重构）：
 * 加载开发者官网 https://yemao.ct.ws/，在应用内直接浏览（不跳系统浏览器）。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun OfficialWebView(url: String, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                webViewClient = WebViewClient()
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                loadUrl(url)
            }
        },
        modifier = modifier,
    )
}
