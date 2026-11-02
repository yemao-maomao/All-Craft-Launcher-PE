package org.aclpe.aclpe.core

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// DataStore 单例：preferencesDataStore 委托必须置于顶层（文件级单例，进程内唯一实例）
private val Context.appSettingsDataStore by preferencesDataStore(name = "app_settings")

object AppSettingsKeys {
    val CRASH_CAPTURE_ENABLED: Preferences.Key<Boolean> = booleanPreferencesKey("crash_capture_enabled")
    val THEME_MODE: Preferences.Key<String> = stringPreferencesKey("theme_mode")
    val DOWNLOAD_WIFI_ONLY: Preferences.Key<Boolean> = booleanPreferencesKey("download_wifi_only")
    val DEVELOPER_MODE: Preferences.Key<Boolean> = booleanPreferencesKey("developer_mode")

    /** 已同意的法务文件版本号（与 LegalDocuments.VERSION 比对；不一致 → 强制重新同意）。 */
    val LEGAL_ACCEPTED_VERSION: Preferences.Key<String> = stringPreferencesKey("legal_accepted_version")

    /** 同意时间戳（本机记录，作为同意事实凭证）。 */
    val LEGAL_ACCEPTED_AT: Preferences.Key<Long> = longPreferencesKey("legal_accepted_at")
}

enum class ThemeMode {
    SYSTEM, LIGHT, DARK;

    companion object {
        fun from(raw: String?): ThemeMode = entries.firstOrNull { it.name == raw } ?: SYSTEM
    }
}

/** 启动器全局设置（DataStore）。读写均为 suspend / Flow，主线程零 I/O。 */
class AppSettings(private val context: Context) {

    val crashCaptureEnabled: Flow<Boolean> =
        context.appSettingsDataStore.data.map { it[AppSettingsKeys.CRASH_CAPTURE_ENABLED] ?: false }

    val themeMode: Flow<ThemeMode> =
        context.appSettingsDataStore.data.map { ThemeMode.from(it[AppSettingsKeys.THEME_MODE]) }

    /** 仅 Wi-Fi 下载开关（用户决策 Q4：默认仅 Wi-Fi，可手动切任意网络）。 */
    val downloadWifiOnly: Flow<Boolean> =
        context.appSettingsDataStore.data.map { it[AppSettingsKeys.DOWNLOAD_WIFI_ONLY] ?: true }

    /** 开发者模式（默认关）：全量 DEBUG 日志落盘 + logcat 调试树 + 环境探测详情。 */
    val developerMode: Flow<Boolean> =
        context.appSettingsDataStore.data.map { it[AppSettingsKeys.DEVELOPER_MODE] ?: false }

    /** 已同意的法务文件版本号；null 表示从未同意（首次启动）→ 必须弹窗。 */
    val legalAcceptedVersion: Flow<String?> =
        context.appSettingsDataStore.data.map { it[AppSettingsKeys.LEGAL_ACCEPTED_VERSION] }

    suspend fun setCrashCaptureEnabled(value: Boolean) {
        context.appSettingsDataStore.edit { it[AppSettingsKeys.CRASH_CAPTURE_ENABLED] = value }
    }

    suspend fun setThemeMode(value: ThemeMode) {
        context.appSettingsDataStore.edit { it[AppSettingsKeys.THEME_MODE] = value.name }
    }

    suspend fun setDownloadWifiOnly(value: Boolean) {
        context.appSettingsDataStore.edit { it[AppSettingsKeys.DOWNLOAD_WIFI_ONLY] = value }
    }

    suspend fun setDeveloperMode(value: Boolean) {
        context.appSettingsDataStore.edit { it[AppSettingsKeys.DEVELOPER_MODE] = value }
    }

    /** 记录同意（两份文件同时同意，故只存一个共同版本号 + 时间戳）。 */
    suspend fun acceptLegalDocuments(version: String) {
        context.appSettingsDataStore.edit { prefs ->
            prefs[AppSettingsKeys.LEGAL_ACCEPTED_VERSION] = version
            prefs[AppSettingsKeys.LEGAL_ACCEPTED_AT] = System.currentTimeMillis()
        }
    }
}