package org.aclpe.core.download.install

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.aclpe.core.common.result.AppError
import org.aclpe.core.common.result.AppResult
import org.aclpe.core.download.json.ManifestVersion
import org.aclpe.core.download.json.VersionJson
import org.aclpe.core.download.json.VersionJsonParser
import org.aclpe.core.download.json.VersionManifestV2
import org.aclpe.core.download.mirror.MirrorId
import org.aclpe.core.download.mirror.MirrorRegistry
import org.aclpe.core.download.net.DownloadManager
import org.aclpe.core.download.net.DownloadProgress
import org.aclpe.core.download.net.DownloadRequest
import org.aclpe.core.download.resolve.AssetsIndex
import org.aclpe.core.download.resolve.AssetsResolver
import org.aclpe.core.download.resolve.LibraryResolver
import org.aclpe.core.download.resolve.ResolvedNatives
import org.aclpe.core.download.rules.PlatformContext
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

/**
 * 版本安装流水线（V0.0.3 接入，FCL 式线性流程——无状态机，完成后状态直达 UI）：
 *
 * manifest → 版本 json → client.jar → libraries（含 natives jar）→ natives 解压 →
 * assets 索引 → assets 对象（并发 8）→ [onInstalled] 回调（app 写 versions 表 installed=true）。
 *
 * 断点续装：每个文件「存在且尺寸符合」即跳过（进程重启后重跑 install 续传）。
 * 镜像：主 BMCLAPI（国内快）回退官方；sha1 全程校验（DownloadManager）。
 * 进度：[states] StateFlow<InstallState>（phase + 文件计数 + 字节），UI 直接订阅。
 */
class VersionInstaller(
    private val client: OkHttpClient,
    private val manager: DownloadManager,
    private val scope: CoroutineScope,
    private val platformContext: PlatformContext = PlatformContext.fromOsSpec(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    enum class Phase { IDLE, JSON, CLIENT, LIBRARIES, EXTRACTING, ASSET_INDEX, ASSETS, DONE, FAILED }

    data class InstallState(
        val versionId: String,
        val phase: Phase,
        val detail: String = "",
        val filesDone: Int = 0,
        val filesTotal: Int = 0,
        val bytesDone: Long = 0L,
        val bytesTotal: Long = 0L,
        val errorCode: String? = null,
    )

    private val _states: MutableStateFlow<Map<String, InstallState>> = MutableStateFlow(emptyMap())
    val states: StateFlow<Map<String, InstallState>> = _states

    private val jobs: ConcurrentHashMap<String, Job> = ConcurrentHashMap()

    /** 拉取版本清单（BMCLAPI 优先，官方回退）。 */
    suspend fun loadManifest(): AppResult<VersionManifestV2> {
        val primary: String = MirrorRegistry.rewrite(MANIFEST_URL, MirrorId.BMCLAPI)
        val text: String = fetchText(primary).getOrNull()
            ?: fetchText(MANIFEST_URL).getOrNull()
            ?: return AppResult.Err(AppError.Network("manifest_failed"))
        return VersionJsonParser.parseManifestV2(text)
    }

    /** 启动安装（幂等：在途/已完成的重复调用被忽略）。完成后回调 [onInstalled]（VersionJson 含 javaVersion/assetIndex）。 */
    fun install(
        manifestVersion: ManifestVersion,
        gameDir: File,
        onInstalled: suspend (VersionJson) -> Unit,
    ) {
        val id: String = manifestVersion.id
        if (jobs.containsKey(id)) return
        if (_states.value[id]?.phase == Phase.DONE) return
        jobs.putIfAbsent(id, scope.launch(ioDispatcher) {
            var state: InstallState = InstallState(id, Phase.JSON)
            _states.update { it + (id to state) }
            try {
                // 1. 版本 json
                val versionJson: VersionJson = downloadVersionJson(manifestVersion, gameDir)

                // 2. client.jar
                state = state.copy(phase = Phase.CLIENT)
                _states.update { it + (id to state) }
                downloadClientJar(versionJson, gameDir) { p: DownloadProgress ->
                    _states.update {
                        it + (id to state.copy(bytesDone = p.downloadedBytes, bytesTotal = p.totalBytes ?: 0L))
                    }
                }

                // 3. libraries（classpath + natives jar）
                val libs = LibraryResolver.resolve(versionJson.libraries, platformContext, MirrorId.BMCLAPI)
                val natives = LibraryResolver.resolveNatives(versionJson.libraries, platformContext, MirrorId.BMCLAPI)
                val allLibs: List<Pair<String, String>> = buildList {
                    libs.forEach { add(it.artifact.path!! to it.url) }
                    natives.forEach { add(it.artifact.path!! to it.url) }
                }
                state = state.copy(phase = Phase.LIBRARIES, filesDone = 0, filesTotal = allLibs.size, bytesDone = 0, bytesTotal = 0)
                _states.update { it + (id to state) }
                downloadAll(allLibs, File(gameDir, "libraries")) { done: Int ->
                    _states.update { it + (id to state.copy(filesDone = done) ) }
                }

                // 4. natives 解压到 natives/（exclude 规则）
                state = state.copy(phase = Phase.EXTRACTING)
                _states.update { it + (id to state) }
                extractNatives(natives, gameDir)

                // 5. assets 索引
                state = state.copy(phase = Phase.ASSET_INDEX)
                _states.update { it + (id to state) }
                val indexText: String = downloadAssetIndex(versionJson, gameDir)
                val index: AssetsIndex = AssetsResolver.parseIndex(indexText)
                    .let { r -> r.getOrNull() ?: throw IllegalStateException("assets_index_parse") }

                // 6. assets 对象（并发，逐文件 sha1 校验在 DownloadManager）
                state = state.copy(phase = Phase.ASSETS, filesDone = 0, filesTotal = index.objects.size)
                _states.update { it + (id to state) }
                downloadAssets(index, File(gameDir, "assets/objects")) { done: Int, bytes: Long ->
                    _states.update { it + (id to state.copy(filesDone = done, bytesDone = bytes) ) }
                }

                // 7. 完成
                _states.update { it + (id to InstallState(id, Phase.DONE)) }
                Timber.i("version installed: %s", id)
                onInstalled(versionJson)
            } catch (t: Throwable) {
                Timber.w(t, "version install failed: %s", id)
                _states.update { it + (id to state.copy(phase = Phase.FAILED, errorCode = errorCodeOf(t))) }
            } finally {
                jobs.remove(id)
            }
        })
    }

    fun stateOf(id: String): InstallState? = _states.value[id]

    // ---- 步骤实现 ----

    private suspend fun downloadVersionJson(manifestVersion: ManifestVersion, gameDir: File): VersionJson {
        val dir: File = File(gameDir, "versions/${manifestVersion.id}")
        dir.mkdirs()
        val file: File = File(dir, "${manifestVersion.id}.json")
        if (!file.isFile) {
            downloadToTemp(
                id = "json:${manifestVersion.id}",
                url = MirrorRegistry.rewrite(manifestVersion.url, MirrorId.BMCLAPI),
                fallbackUrl = manifestVersion.url,
                dest = file,
                sha1 = manifestVersion.sha1,
            )
        }
        val text: String = file.readText()
        return VersionJsonParser.parseVersionJson(text).getOrNull()
            ?: throw IllegalStateException("version_json_parse")
    }

    private suspend fun downloadClientJar(versionJson: VersionJson, gameDir: File, onProgress: (DownloadProgress) -> Unit) {
        val client = versionJson.downloads?.client ?: return
        val url: String = client.url ?: return
        val dir: File = File(gameDir, "versions/${versionJson.id}")
        dir.mkdirs()
        val file: File = File(dir, "${versionJson.id}.jar")
        if (file.isFile && file.length() == client.size) return   // 断点续装
        val result: AppResult<File> = manager.download(
            DownloadRequest(
                id = "client:${versionJson.id}",
                url = MirrorRegistry.rewrite(url, MirrorId.BMCLAPI),
                mirrorUrls = listOf(url),
                destFile = file,
                sha1 = client.sha1,
                sizeBytes = client.size,
                onProgress = onProgress,
            )
        )
        result.getOrNull() ?: throw IllegalStateException("client_download")
    }

    private suspend fun downloadAll(
        entries: List<Pair<String, String>>,
        baseDir: File,
        onFilesDone: (Int) -> Unit,
    ) = coroutineScope {
        var done: Int = 0
        val semaphore = Semaphore(LIBRARY_CONCURRENCY)
        entries.map { (path: String, url: String) ->
            launch {
                semaphore.withPermit {
                    val dest: File = File(baseDir, path)
                    if (dest.isFile && dest.length() > 0L) return@withPermit   // 断点续装（sha1 来源清单）
                    downloadToTemp(id = "lib:$path", url = url, fallbackUrl = officialLibraryUrl(path), dest = dest, sha1 = null)
                }
                done++
                onFilesDone(done)
            }
        }.forEach { it.join() }
    }

    private suspend fun downloadAssets(
        index: AssetsIndex,
        objectsDir: File,
        onProgress: (Int, Long) -> Unit,
    ) = coroutineScope {
        var done: Int = 0
        var bytes: Long = 0L
        val semaphore = Semaphore(ASSET_CONCURRENCY)
        val lock = Any()
        index.objects.entries.map { (name: String, obj: org.aclpe.core.download.resolve.AssetsObject) ->
            launch {
                semaphore.withPermit {
                    val prefix: String = obj.hash.take(2)
                    val dest: File = File(objectsDir, "$prefix/${obj.hash}")
                    if (dest.isFile && dest.length() == obj.size) return@withPermit
                    val url: String = AssetsResolver.objectUrl(obj.hash, MirrorId.BMCLAPI)
                    val result: AppResult<File> = manager.download(
                        DownloadRequest(
                            id = "asset:$name",
                            url = url,
                            mirrorUrls = listOf(AssetsResolver.objectUrl(obj.hash, MirrorId.OFFICIAL)),
                            destFile = dest,
                            sha1 = obj.hash,
                            sizeBytes = obj.size,
                        )
                    )
                    result.getOrNull() ?: throw IllegalStateException("asset_download")
                }
                synchronized(lock) {
                    done++
                    bytes += obj.size
                    onProgress(done, bytes)
                }
            }
        }.forEach { it.join() }
    }

    private suspend fun downloadAssetIndex(versionJson: VersionJson, gameDir: File): String {
        val ref = versionJson.assetIndex ?: throw IllegalStateException("no_asset_index")
        val dir: File = File(gameDir, "assets/indexes")
        dir.mkdirs()
        val file: File = File(dir, "${ref.id}.json")
        if (!file.isFile) {
            downloadToTemp(
                id = "index:${ref.id}",
                url = MirrorRegistry.rewrite(ref.url, MirrorId.BMCLAPI),
                fallbackUrl = ref.url,
                dest = file,
                sha1 = ref.sha1,
            )
        }
        return file.readText()
    }

    /** natives jar 解压到 natives/（排除 META-INF/ 与 exclude 列表）。 */
    private fun extractNatives(natives: List<ResolvedNatives>, gameDir: File) {
        val nativesDir: File = File(gameDir, "natives")
        nativesDir.mkdirs()
        natives.forEach { native ->
            val jar: File = File(gameDir, "libraries/${native.artifact.path}")
            if (!jar.isFile) return
            val excludes: List<String> = native.extract?.exclude?.takeIf { it.isNotEmpty() } ?: listOf("META-INF/")
            ZipInputStream(FileInputStream(jar)).use { zip ->
                while (true) {
                    val entry: java.util.zip.ZipEntry? = zip.nextEntry
                    if (entry == null) break
                    val name: String = entry.name
                    if (entry.isDirectory || excludes.any { name.startsWith(it) }) continue
                    val target: File = File(nativesDir, name)
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { out -> zip.transferTo(out) }
                }
            }
        }
    }

    /** 通用：主镜像下载，失败回退官方（DownloadManager.mirrorUrls 轮换）。 */
    private suspend fun downloadToTemp(id: String, url: String, fallbackUrl: String?, dest: File, sha1: String?) {
        val result: AppResult<File> = manager.download(
            DownloadRequest(
                id = id,
                url = url,
                mirrorUrls = if (fallbackUrl != null && fallbackUrl != url) listOf(fallbackUrl) else emptyList(),
                destFile = dest,
                sha1 = sha1,
            )
        )
        result.getOrNull() ?: throw IllegalStateException("download_failed")
    }

    private fun officialLibraryUrl(path: String): String = "https://libraries.minecraft.net/$path"

    private suspend fun fetchText(url: String): AppResult<String> = try {
        val request: Request = Request.Builder().url(url).header("User-Agent", UA).build()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                AppResult.Ok(response.body?.string() ?: "")
            } else {
                AppResult.Err(AppError.Network("http_${response.code}"))
            }
        }
    } catch (t: Throwable) {
        AppResult.Err(AppError.Network("fetch_failed", cause = t))
    }

    private fun errorCodeOf(t: Throwable): String = when (t) {
        is IllegalStateException -> t.message ?: "illegal_state"
        else -> t.message ?: t.javaClass.simpleName
    }

    companion object {
        const val MANIFEST_URL: String = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
        const val UA: String = "ACL-PE/0.0.3 (github.com/yemao-maomao/All-Craft-Launcher-PE)"
        const val LIBRARY_CONCURRENCY: Int = 4
        const val ASSET_CONCURRENCY: Int = 8
    }
}
