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
 * 版本安装流水线（FCL 式线性流程——无状态机，完成后状态直达 UI）：
 *
 * manifest → 版本 json → client.jar → libraries（含 natives jar）→ natives 解压 →
 * assets 索引 → assets 对象（并发 8）→ [onInstalled] 回调（app 写 versions 表 installed=true）。
 *
 * **镜像（2026-11-02 接入设置）**：[originMirror] 在**每次安装开始时取一次**，
 * 用户在设置中切换镜像（官方/BMCLAPI）后，后续下载（清单、json、jar、库、资源）全部直接使用所选镜像；
 * 主镜像失败时按 [MirrorRegistry.originMirrors] 顺序回退其余镜像（含官方）。
 *
 * 断点续装：每个文件「存在且尺寸符合」即跳过（进程重启后重跑 install 续传）。
 * 进度：[states] StateFlow<InstallState>（phase + 文件计数 + 字节），UI 直接订阅。
 */
class VersionInstaller(
    private val client: OkHttpClient,
    private val manager: DownloadManager,
    private val scope: CoroutineScope,
    private val platformContext: PlatformContext = PlatformContext.fromOsSpec(),
    /** 下载镜像来源（设置项）：每次安装开始时取一次。 */
    private val originMirror: suspend () -> MirrorId = { MirrorId.OFFICIAL },
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

    /** 拉取版本清单：主用所选镜像，失败按其他可用镜像回退。 */
    suspend fun loadManifest(): AppResult<VersionManifestV2> {
        val preferred: MirrorId = originMirror()
        val candidates: List<String> = (listOf(preferred) + MirrorRegistry.originMirrors().map { it.id })
            .distinct()
            .map { MirrorRegistry.rewrite(MANIFEST_URL, it) }
            .distinct()
        for (url in candidates) {
            val text: String? = fetchText(url).getOrNull()
            if (text != null) {
                val parsed: AppResult<VersionManifestV2> = VersionJsonParser.parseManifestV2(text)
                if (parsed.isOk) return parsed
            }
        }
        return AppResult.Err(AppError.Network("manifest_failed"))
    }

    /** 启动安装（幂等：在途/已完成的重复调用被忽略）。 */
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
                // 安装开始即锁定镜像（用户此刻的选择作用于整个安装过程）
                val mirror: MirrorId = originMirror()
                Timber.i("version install start: %s mirror=%s", id, mirror)

                // 1. 版本 json
                val versionJson: VersionJson = downloadVersionJson(manifestVersion, gameDir, mirror)

                // 2. client.jar
                state = state.copy(phase = Phase.CLIENT)
                _states.update { it + (id to state) }
                downloadClientJar(versionJson, gameDir, mirror) { p: DownloadProgress ->
                    _states.update {
                        it + (id to state.copy(bytesDone = p.downloadedBytes, bytesTotal = p.totalBytes ?: 0L))
                    }
                }

                // 3. libraries（classpath + natives jar）
                val libs = LibraryResolver.resolve(versionJson.libraries, platformContext, mirror)
                val natives = LibraryResolver.resolveNatives(versionJson.libraries, platformContext, mirror)
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
                val indexText: String = downloadAssetIndex(versionJson, gameDir, mirror)
                val index: AssetsIndex = AssetsResolver.parseIndex(indexText)
                    .let { r -> r.getOrNull() ?: throw IllegalStateException("assets_index_parse") }

                // 6. assets 对象（并发，逐文件 sha1 校验在 DownloadManager）
                state = state.copy(phase = Phase.ASSETS, filesDone = 0, filesTotal = index.objects.size)
                _states.update { it + (id to state) }
                downloadAssets(index, File(gameDir, "assets/objects"), mirror) { done: Int, bytes: Long ->
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

    private suspend fun downloadVersionJson(manifestVersion: ManifestVersion, gameDir: File, mirror: MirrorId): VersionJson {
        val dir: File = File(gameDir, "versions/${manifestVersion.id}")
        dir.mkdirs()
        val file: File = File(dir, "${manifestVersion.id}.json")
        if (!file.isFile) {
            downloadToTemp(
                id = "json:${manifestVersion.id}",
                url = MirrorRegistry.rewrite(manifestVersion.url, mirror),
                fallbacks = fallbackUrls(manifestVersion.url, mirror),
                dest = file,
                sha1 = manifestVersion.sha1,
            )
        }
        val text: String = file.readText()
        return VersionJsonParser.parseVersionJson(text).getOrNull()
            ?: throw IllegalStateException("version_json_parse")
    }

    private suspend fun downloadClientJar(
        versionJson: VersionJson,
        gameDir: File,
        mirror: MirrorId,
        onProgress: (DownloadProgress) -> Unit,
    ) {
        val clientArtifact = versionJson.downloads?.client ?: return
        val url: String = clientArtifact.url ?: return
        val dir: File = File(gameDir, "versions/${versionJson.id}")
        dir.mkdirs()
        val file: File = File(dir, "${versionJson.id}.jar")
        if (file.isFile && file.length() == clientArtifact.size) return   // 断点续装
        val result: AppResult<File> = manager.download(
            DownloadRequest(
                id = "client:${versionJson.id}",
                url = MirrorRegistry.rewrite(url, mirror),
                mirrorUrls = fallbackUrls(url, mirror),
                destFile = file,
                sha1 = clientArtifact.sha1,
                sizeBytes = clientArtifact.size,
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
                    if (dest.isFile && dest.length() > 0L) return@withPermit   // 断点续装
                    // url 已经镜像重写；回退链补官方（镜像缺件时）
                    manager.download(
                        DownloadRequest(
                            id = "lib:$path",
                            url = url,
                            mirrorUrls = if (url != officialLibraryUrl(path)) listOf(officialLibraryUrl(path)) else emptyList(),
                            destFile = dest,
                        )
                    ).getOrNull() ?: throw IllegalStateException("library_download")
                }
                done++
                onFilesDone(done)
            }
        }.forEach { it.join() }
    }

    private suspend fun downloadAssets(
        index: AssetsIndex,
        objectsDir: File,
        mirror: MirrorId,
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
                    val url: String = AssetsResolver.objectUrl(obj.hash, mirror)
                    val result: AppResult<File> = manager.download(
                        DownloadRequest(
                            id = "asset:$name",
                            url = url,
                            mirrorUrls = fallbackUrls(AssetsResolver.objectUrl(obj.hash, MirrorId.OFFICIAL), mirror),
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

    private suspend fun downloadAssetIndex(versionJson: VersionJson, gameDir: File, mirror: MirrorId): String {
        val ref = versionJson.assetIndex ?: throw IllegalStateException("no_asset_index")
        val dir: File = File(gameDir, "assets/indexes")
        dir.mkdirs()
        val file: File = File(dir, "${ref.id}.json")
        if (!file.isFile) {
            downloadToTemp(
                id = "index:${ref.id}",
                url = MirrorRegistry.rewrite(ref.url, mirror),
                fallbacks = fallbackUrls(ref.url, mirror),
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

    /** 通用：主镜像下载 + 其余可用镜像回退（DownloadManager 内部轮换）。 */
    private suspend fun downloadToTemp(
        id: String,
        url: String,
        fallbacks: List<String>,
        dest: File,
        sha1: String?,
    ) {
        val result: AppResult<File> = manager.download(
            DownloadRequest(
                id = id,
                url = url,
                mirrorUrls = fallbacks,
                destFile = dest,
                sha1 = sha1,
            )
        )
        result.getOrNull() ?: throw IllegalStateException("download_failed")
    }

    /** 回退链：其余已启用镜像重写后的地址（去重、排除主地址本身）。 */
    private fun fallbackUrls(originalUrl: String, primary: MirrorId): List<String> =
        MirrorRegistry.originMirrors().map { it.id }
            .filter { it != primary }
            .map { MirrorRegistry.rewrite(originalUrl, it) }
            .distinct()
            .filter { it != MirrorRegistry.rewrite(originalUrl, primary) }

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
        const val UA: String = "ACL-PE/0.0.5 (github.com/yemao-maomao/All-Craft-Launcher-PE)"
        const val LIBRARY_CONCURRENCY: Int = 4
        const val ASSET_CONCURRENCY: Int = 8
    }
}