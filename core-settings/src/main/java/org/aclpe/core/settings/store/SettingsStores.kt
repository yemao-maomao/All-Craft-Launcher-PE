package org.aclpe.core.settings.store

import kotlinx.serialization.json.Json
import org.aclpe.core.common.result.AppError
import org.aclpe.core.common.result.AppResult
import org.aclpe.core.settings.launcher.LauncherSettings
import org.aclpe.core.settings.options.OptionsFile
import org.aclpe.core.settings.touch.TouchScheme
import java.io.File

/**
 * 设置存储（按 Profile 隔离；所有 I/O suspend 调用方负责调度）。
 * 布局：
 *   runtimes/<profileId>/options.txt / options.txt.bak
 *   runtimes/<profileId>/settings/launcher.json
 *   runtimes/<profileId>/touch/<name>.json
 *   settings/templates/default-options.txt + default-launcher.json  （默认模板，全局）
 */
class SettingsStores(private val filesRoot: File) {

    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun profileDir(profileId: String): File = File(filesRoot, "runtimes/$profileId")

    fun optionsFile(profileId: String): File = File(profileDir(profileId), "options.txt")

    // ---- options.txt ----

    suspend fun readOptions(profileId: String): OptionsFile {
        val file: File = optionsFile(profileId)
        return if (file.isFile) OptionsFile.parse(file.readText()) else OptionsFile(emptyList())
    }

    suspend fun writeOptions(profileId: String, options: OptionsFile, runningCheck: () -> Boolean = { false }): AppResult<Unit> =
        OptionsFile.writeAtomic(optionsFile(profileId), options, runningCheck)

    suspend fun restoreOptionsBak(profileId: String): AppResult<Unit> =
        OptionsFile.restoreBak(optionsFile(profileId))

    // ---- 启动器设置 ----

    private fun launcherJson(profileId: String): File = File(profileDir(profileId), "settings/launcher.json")

    suspend fun readLauncher(profileId: String): LauncherSettings {
        val file: File = launcherJson(profileId)
        if (!file.isFile) return LauncherSettings()
        return runCatching { json.decodeFromString(LauncherSettings.serializer(), file.readText()) }
            .getOrDefault(LauncherSettings())
    }

    suspend fun writeLauncher(profileId: String, settings: LauncherSettings): AppResult<Unit> =
        try {
            val file: File = launcherJson(profileId)
            file.parentFile?.mkdirs()
            val tmp: File = File(file.parentFile, "launcher.json.tmp")
            tmp.writeText(json.encodeToString(LauncherSettings.serializer(), settings))
            if (!tmp.renameTo(file)) {
                tmp.delete()
                return AppResult.Err(AppError.Io("launcher_write_failed"))
            }
            AppResult.Ok(Unit)
        } catch (t: Throwable) {
            AppResult.Err(AppError.Io("launcher_write_failed", cause = t))
        }

    // ---- 触控方案 ----

    private fun touchDir(profileId: String): File = File(profileDir(profileId), "touch")

    suspend fun listTouchSchemes(profileId: String): List<String> {
        val dir: File = touchDir(profileId)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles().orEmpty().filter { it.extension == "json" }.map { it.nameWithoutExtension }
    }

    suspend fun loadTouchScheme(profileId: String, name: String): TouchScheme? {
        val file: File = File(touchDir(profileId), "$name.json")
        if (!file.isFile) return null
        return runCatching { json.decodeFromString(TouchScheme.serializer(), file.readText()) }.getOrNull()
    }

    suspend fun saveTouchScheme(profileId: String, scheme: TouchScheme): AppResult<Unit> =
        try {
            val file: File = File(touchDir(profileId), "${scheme.name}.json")
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(TouchScheme.serializer(), scheme))
            AppResult.Ok(Unit)
        } catch (t: Throwable) {
            AppResult.Err(AppError.Io("touch_save_failed", cause = t))
        }

    suspend fun deleteTouchScheme(profileId: String, name: String): AppResult<Unit> =
        try {
            File(touchDir(profileId), "$name.json").delete()
            AppResult.Ok(Unit)
        } catch (t: Throwable) {
            AppResult.Err(AppError.Io("touch_delete_failed", cause = t))
        }

    // ---- 默认模板（全局，新 Profile 从此复制） ----

    private fun templateDir(): File = File(filesRoot, "settings/templates")

    suspend fun saveDefaultTemplate(options: OptionsFile, launcher: LauncherSettings): AppResult<Unit> =
        try {
            templateDir().mkdirs()
            File(templateDir(), "default-options.txt").writeText(options.serialize())
            File(templateDir(), "default-launcher.json")
                .writeText(json.encodeToString(LauncherSettings.serializer(), launcher))
            AppResult.Ok(Unit)
        } catch (t: Throwable) {
            AppResult.Err(AppError.Io("template_save_failed", cause = t))
        }

    suspend fun loadDefaultTemplate(): Pair<OptionsFile, LauncherSettings> {
        val optionsText: String? = File(templateDir(), "default-options.txt").takeIf { it.isFile }?.readText()
        val launcher: LauncherSettings = File(templateDir(), "default-launcher.json").takeIf { it.isFile }
            ?.readText()
            ?.let { runCatching { json.decodeFromString(LauncherSettings.serializer(), it) }.getOrNull() }
            ?: LauncherSettings()
        return (optionsText?.let { OptionsFile.parse(it) } ?: OptionsFile(emptyList())) to launcher
    }

    // ---- 导入/导出 ----

    fun exportDir(): File = File(filesRoot, "exports")

    suspend fun exportOptions(profileId: String, options: OptionsFile): AppResult<File> =
        try {
            exportDir().mkdirs()
            val file: File = File(exportDir(), "options-$profileId.txt")
            file.writeText(options.serialize())
            AppResult.Ok(file)
        } catch (t: Throwable) {
            AppResult.Err(AppError.Io("export_failed", cause = t))
        }

    /** 导入固定路径（SAF 文件选择器接入后扩展为用户任选文件）。 */
    suspend fun importOptions(profileId: String): AppResult<OptionsFile> =
        try {
            val file: File = File(exportDir(), "import-options.txt")
            if (!file.isFile) return AppResult.Err(AppError.Io("import_missing", "请先放置 import-options.txt"))
            AppResult.Ok(OptionsFile.parse(file.readText()))
        } catch (t: Throwable) {
            AppResult.Err(AppError.Io("import_failed", cause = t))
        }
}
