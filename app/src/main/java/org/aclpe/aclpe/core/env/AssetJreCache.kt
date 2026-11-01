package org.aclpe.aclpe.core.env

import android.content.Context
import timber.log.Timber
import java.io.File

/**
 * 内置 JRE 资产缓存：APK assets/runtimes/<name> → files/env/.assets/<name>（首次复制，之后复用）。
 * 资产缺失（未随 fetch-jre-assets 构建的源码包）返回 null → 调度器自动回退网络下载。
 */
class AssetJreCache(private val context: Context) {

    private val cacheDir: File = File(context.filesDir, "env/.assets")

    fun fileFor(component: String): File? {
        val name: String = JreManifest.assetNameFor(component) ?: return null
        val cached: File = File(cacheDir, name)
        val expectedSize: Long = JreManifest.specs()[component]?.sizeBytes ?: return null
        if (cached.isFile && cached.length() == expectedSize) return cached
        return try {
            cacheDir.mkdirs()
            context.assets.open("runtimes/$name").use { input ->
                cached.outputStream().use { output -> input.copyTo(output) }
            }
            if (cached.length() == expectedSize) cached else {
                cached.delete()
                null
            }
        } catch (t: Throwable) {
            Timber.w("内置资产不可用（将回退网络）: %s", t.message)
            null
        }
    }
}
