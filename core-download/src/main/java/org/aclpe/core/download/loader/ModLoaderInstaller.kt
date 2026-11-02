package org.aclpe.core.download.loader

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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.aclpe.core.common.result.AppError
import org.aclpe.core.common.result.AppResult
import org.aclpe.core.download.json.VersionJson
import org.aclpe.core.download.json.VersionJsonParser
import org.aclpe.core.download.mirror.MirrorId
import org.aclpe.core.download.mirror.MirrorRegistry
import org.aclpe.core.download.net.DownloadManager
import org.aclpe.core.download.net.DownloadRequest
import org.aclpe.core.download.resolve.LibraryResolver
import org.aclpe.core.download.resolve.ResolvedLibrary
import org.aclpe.core.download.rules.PlatformContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** 支持的模组加载器（Forge/NeoForge 需运行官方安装器，手机上不适用 → 明确不支持）。 */
enum class ModLoaderKind(val displayName: String) {
    FABRIC("Fabric"),
    QUILT("Quilt"),
    ;

    companion object {
        fun fromName(raw: String?): ModLoaderKind? = entries.firstOrNull { it.name == raw }
    }
}

/** 加载器版本条目。 */
data class ModLoaderVersion(
    val version: String,
    val stable: Boolean,
)

/**
 * 模组加载器安装（2026-11-02 上架：为**已有版本**添加加载器）：
 *
 * Fabric/Quilt 的安装方式 = 元数据拼接（无需运行安装器）：
 * 1. 从 Meta API 取 loader 列表；
 * 2. 取 `profile/json` —— 这是一份**完整的版本 json**（含 libraries/mainClass 与 `inheritsFrom` 指向原版）；
 * 3. 原样落盘为 `versions/<新 id>/<新 id>.json`，并下载其 libraries；
 * 4. 回调 [onInstalled]（app 写 versions 表；启动时的父子 json 合并随 JNI 里程碑实现）。
 *
 * 镜像：Fabric Meta 走 BMCLAPI 反代（实测 0.21s vs 官方 0.84s）；镜像不可用时回退官方。
 */
class ModLoaderInstaller(
    private val client: OkHttpClient,
    private val manager: DownloadManager,
    private val scope: CoroutineScope,
    private val platformContext: PlatformContext = PlatformContext.fromOsSpec(),
    /** 下载镜像（跟随设置的"下载镜像"：BMCLAPI 时使用 Fabric Meta 反代）。 */
    private val originMirror: suspend () -> MirrorId = { MirrorId.OFFICIAL },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    enum class Phase { IDLE, META, PROFILE, LIBRARIES, DONE, FAILED }

    data class InstallState(
        val id: String,
        val phase: Phase,
        val filesDone: Int = 0,
        val filesTotal: Int = 0,
        val errorCode: String? = null,
    )

    private val _states: MutableStateFlow<Map<String, InstallState>> = MutableStateFlow(emptyMap())
    val states: StateFlow<Map<String, InstallState>> = _states

    private val jobs: ConcurrentHashMap<String, Job> = ConcurrentHashMap()
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ---- Meta 查询（供 UI 选版本） ----

    /** 某游戏版本可用的 loader 版本列表（stable 优先展示）。 */
    suspend fun loaders(gameVersion: String, kind: ModLoaderKind): AppResult<List<ModLoaderVersion>> = try {
        val urls: List<String> = metaListUrls(kind, gameVersion)
        var parsed: List<ModLoaderVersion>? = null
        for (url in urls) {
            val text: String = fetchText(url).getOrNull() ?: continue
            parsed = parseLoaderList(kind, text)
            if (parsed.isNotEmpty()) break
        }
        when {
            parsed == null -> AppResult.Err(AppError.Network("loader_meta_failed"))
            else -> AppResult.Ok(parsed)
        }
    } catch (t: Throwable) {
        AppResult.Err(AppError.Network("loader_meta_failed", cause = t))
    }

    /** 安装：为 [gameVersion] 安装 [kind] 的 [loaderVersion]，新版本 id 形如 `fabric-loader-0.15.11-1.20.1`。 */
    fun install(
        kind: ModLoaderKind,
        gameVersion: String,
        loaderVersion: String,
        gameDir: File,
        onInstalled: suspend (versionId: String, versionJson: VersionJson) -> Unit,
    ) {
        val newId: String = versionIdOf(kind, loaderVersion, gameVersion)
        if (jobs.containsKey(newId)) return
        if (_states.value[newId]?.phase == Phase.DONE) return
        jobs.putIfAbsent(newId, scope.launch(ioDispatcher) {
            var state: InstallState = InstallState(newId, Phase.META)
            _states.update { it + (newId to state) }
            try {
                val mirror: MirrorId = originMirror()

                // 1. profile json（完整版本 json，含 inheritsFrom）
                state = state.copy(phase = Phase.PROFILE)
                _states.update { it + (newId to state) }
                val profileText: String = fetchProfileJson(kind, gameVersion, loaderVersion, mirror)
                val profileJson: VersionJson = VersionJsonParser.parseVersionJson(profileText).getOrNull()
                    ?: throw IllegalStateException("loader_profile_parse")

                // 2. 落盘版本 json（id 以我们生成的为准，保持与目录/DB 一致）
                val versionDir: File = File(gameDir, "versions/$newId")
                versionDir.mkdirs()
                File(versionDir, "$newId.json").writeText(profileText)
                // 父版本目录不存在时明确报错（安装加载器前需先装原版）
                val parentDir: File = File(gameDir, "versions/$gameVersion")
                if (!File(parentDir, "$gameVersion.json").isFile) {
                    throw IllegalStateException("parent_version_missing")
                }

                // 3. libraries（profile json 自带 loader 相关库；原版库由父版本提供）
                val libs: List<ResolvedLibrary> = LibraryResolver.resolve(profileJson.libraries, platformContext, mirror)
                val entries: List<Pair<String, String>> = libs.mapNotNull { lib ->
                    lib.artifact.path?.let { path -> path to lib.url }
                }
                state = state.copy(phase = Phase.LIBRARIES, filesDone = 0, filesTotal = entries.size)
                _states.update { it + (newId to state) }
                downloadAll(entries, File(gameDir, "libraries")) { done: Int ->
                    _states.update { it + (newId to state.copy(filesDone = done) ) }
                }

                _states.update { it + (newId to InstallState(newId, Phase.DONE)) }
                Timber.i("mod loader installed: %s (%s)", newId, kind.displayName)
                onInstalled(newId, profileJson)
            } catch (t: Throwable) {
                Timber.w(t, "mod loader install failed: %s", newId)
                _states.update { it + (newId to state.copy(phase = Phase.FAILED, errorCode = errorCodeOf(t))) }
            } finally {
                jobs.remove(newId)
            }
        })
    }

    fun stateOf(id: String): InstallState? = _states.value[id]

    fun versionIdOf(kind: ModLoaderKind, loaderVersion: String, gameVersion: String): String = when (kind) {
        ModLoaderKind.FABRIC -> "fabric-loader-$loaderVersion-$gameVersion"
        ModLoaderKind.QUILT -> "quilt-loader-$loaderVersion-$gameVersion"
    }

    // ---- 内部 ----

    private fun metaListUrls(kind: ModLoaderKind, gameVersion: String): List<String> = when (kind) {
        ModLoaderKind.FABRIC -> listOf(
            "https://bmclapi2.bangbang93.com/fabric-meta/v2/versions/loader/$gameVersion",
            "https://meta.fabricmc.net/v2/versions/loader/$gameVersion",
        )
        ModLoaderKind.QUILT -> listOf(
            "https://meta.quiltmc.org/v3/versions/loader/$gameVersion",
        )
    }

    private fun profileUrls(kind: ModLoaderKind, gameVersion: String, loaderVersion: String): List<String> = when (kind) {
        ModLoaderKind.FABRIC -> listOf(
            "https://bmclapi2.bangbang93.com/fabric-meta/v2/versions/loader/$gameVersion/$loaderVersion/profile/json",
            "https://meta.fabricmc.net/v2/versions/loader/$gameVersion/$loaderVersion/profile/json",
        )
        ModLoaderKind.QUILT -> listOf(
            "https://meta.quiltmc.org/v3/versions/loader/$gameVersion/$loaderVersion/profile/json",
        )
    }

    private suspend fun fetchProfileJson(
        kind: ModLoaderKind,
        gameVersion: String,
        loaderVersion: String,
        mirror: MirrorId,
    ): String {
        val urls: List<String> = profileUrls(kind, gameVersion, loaderVersion)
            .map { MirrorRegistry.rewrite(it, mirror) }
            .distinct()
        for (url in urls) {
            fetchText(url).getOrNull()?.let { if (it.isNotBlank()) return it }
        }
        throw IllegalStateException("loader_profile_fetch")
    }

    /** Fabric 列表：[{loader:{version,stable},...}]；Quilt 列表：[{loader:{version},...}]（结构同构）。 */
    private fun parseLoaderList(kind: ModLoaderKind, text: String): List<ModLoaderVersion> = try {
        val entries: List<FabricLoaderEntry> = json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(FabricLoaderEntry.serializer()), text)
        entries.mapNotNull { entry ->
            entry.loader?.version?.let { v -> ModLoaderVersion(version = v, stable = entry.loader.stable ?: true) }
        }
    } catch (t: Throwable) {
        Timber.w(t, "parse loader list failed: %s", kind)
        emptyList()
    }

    private suspend fun downloadAll(
        entries: List<Pair<String, String>>,
        baseDir: File,
        onFilesDone: (Int) -> Unit,
    ) = coroutineScope {
        var done: Int = 0
        val semaphore = Semaphore(CONCURRENCY)
        entries.map { (path: String, url: String) ->
            launch {
                semaphore.withPermit {
                    val dest: File = File(baseDir, path)
                    if (dest.isFile && dest.length() > 0L) return@withPermit
                    manager.download(
                        DownloadRequest(
                            id = "loader-lib:$path",
                            url = url,
                            mirrorUrls = if (url != officialLibraryUrl(path)) listOf(officialLibraryUrl(path)) else emptyList(),
                            destFile = dest,
                        )
                    ).getOrNull() ?: throw IllegalStateException("loader_library_download")
                }
                done++
                onFilesDone(done)
            }
        }.forEach { it.join() }
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

    @Serializable
    private data class FabricLoaderEntry(val loader: FabricLoaderInfo? = null)

    @Serializable
    private data class FabricLoaderInfo(val version: String? = null, val stable: Boolean? = null)

    companion object {
        const val UA: String = "ACL-PE/0.0.5 (github.com/yemao-maomao/All-Craft-Launcher-PE)"
        const val CONCURRENCY: Int = 4
    }
}