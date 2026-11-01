package org.aclpe.aclpe.core.env

import android.content.Context
import org.aclpe.core.env.bootstrap.EnvDownloadSpec
import timber.log.Timber
import java.io.File
import java.security.MessageDigest

/**
 * 内置 JRE 资产缓存：APK assets/runtimes/<name> → files/env/.assets/<name>（首次复制，之后复用）。
 * 资产缺失（未随 fetch-jre-assets 构建的源码包）返回 null → 调度器自动回退网络下载。
 *
 * 审计中危#10：除尺寸外补流式 SHA-256 校验（网络路径一直有校验，资产路径原先只比大小），
 * 防止 APK 打包/复制过程损坏或被替换的资产直接进入安装（.build marker 不再是伪证明）。
 */
class AssetJreCache(private val context: Context) {

    private val cacheDir: File = File(context.filesDir, "env/.assets")

    fun fileFor(component: String): File? {
        val name: String = JreManifest.assetNameFor(component) ?: return null
        val spec: EnvDownloadSpec = JreManifest.specs()[component] ?: return null
        val cached: File = File(cacheDir, name)
        // 命中缓存：尺寸 + sha256 双校验（校验失败视为损坏 → 重新复制）
        if (cached.isFile && cached.length() == (spec.sizeBytes ?: -1L)) {
            if (sha256Of(cached).equals(spec.sha256, ignoreCase = true)) return cached
            Timber.w("内置资产缓存校验失败，重新复制: %s", name)
            cached.delete()
        }
        return try {
            cacheDir.mkdirs()
            val digest: MessageDigest = MessageDigest.getInstance("SHA-256")
            context.assets.open("runtimes/$name").use { input ->
                cached.outputStream().use { output ->
                    val buffer: ByteArray = ByteArray(BUFFER)
                    while (true) {
                        val n: Int = input.read(buffer)
                        if (n < 0) break
                        digest.update(buffer, 0, n)
                        output.write(buffer, 0, n)
                    }
                }
            }
            val hash: String = digest.digest().joinToString("") { "%02x".format(it) }
            if (hash.equals(spec.sha256, ignoreCase = true)) {
                cached
            } else {
                Timber.w("内置资产 sha256 不符（将回退网络）: %s", name)
                cached.delete()
                null
            }
        } catch (t: Throwable) {
            Timber.w("内置资产不可用（将回退网络）: %s", t.message)
            null
        }
    }

    private fun sha256Of(file: File): String {
        val digest: MessageDigest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer: ByteArray = ByteArray(BUFFER)
            while (true) {
                val n: Int = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val BUFFER: Int = 64 * 1024
    }
}
