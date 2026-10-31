package org.aclpe.aclpe.core

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// DataStore 单例：preferencesDataStore 委托必须置于顶层（文件级单例，进程内唯一实例）
private val Context.appSettingsDataStore by preferencesDataStore(name = "app_settings")

object AppSettingsKeys {
    val CRASH_CAPTURE_ENABLED: Preferences.Key<Boolean> = booleanPreferencesKey("crash_capture_enabled")
    val THEME_MODE: Preferences.Key<String> = stringPreferencesKey("theme_mode")
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

    suspend fun setCrashCaptureEnabled(value: Boolean) {
        context.appSettingsDataStore.edit { it[AppSettingsKeys.CRASH_CAPTURE_ENABLED] = value }
    }

    suspend fun setThemeMode(value: ThemeMode) {
        context.appSettingsDataStore.edit { it[AppSettingsKeys.THEME_MODE] = value.name }
    }
}
