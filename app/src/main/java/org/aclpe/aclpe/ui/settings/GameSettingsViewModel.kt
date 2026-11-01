package org.aclpe.aclpe.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.aclpe.aclpe.ACLApplication
import org.aclpe.aclpe.core.env.EnvDependencies
import org.aclpe.core.settings.launcher.LauncherSettings
import org.aclpe.core.settings.launcher.LauncherSettingsValidator
import org.aclpe.core.settings.options.GameOptionKeys
import org.aclpe.core.settings.options.GameOptionsValidator
import org.aclpe.core.settings.options.OptionsChange
import org.aclpe.core.settings.options.OptionsFile
import org.aclpe.core.settings.store.SettingsStores
import org.aclpe.core.settings.touch.TouchScheme

/**
 * 游戏外设置编辑器 ViewModel（Step 5）：
 * - options.txt 编辑（校验合法才接受、标脏）、保存（备份+原子写+运行中拒绝）、
 *   恢复默认/设为默认模板/对比差异/导入导出/回退备份；
 * - 启动器侧设置（内存档位/JVM 附加/镜像源/图形后端/性能总闸 L0~L5）。
 * 工作副本以 OptionsFile 全程传递：保存时保留原文件的注释/空行/未知键（模组项不丢）。
 * 骨架期 profileId 固定 "default"。
 */
class GameSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val deps: EnvDependencies = (application as ACLApplication).envDependencies
    private val stores: SettingsStores = deps.settingsStores

    data class UiState(
        val working: OptionsFile = OptionsFile(emptyList()),
        val launcher: LauncherSettings = LauncherSettings(),
        val dirty: Boolean = false,
        val diff: List<OptionsChange> = emptyList(),
        val touchSchemes: List<String> = emptyList(),
    ) {
        val options: Map<String, String> get() = working.entries()
        val keybindCount: Int get() = options.keys.count { it.startsWith(GameOptionKeys.KEYBIND_PREFIX) }
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    init {
        load()
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun load() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = UiState(
                working = stores.readOptions(PROFILE_ID),
                launcher = stores.readLauncher(PROFILE_ID),
                touchSchemes = stores.listTouchSchemes(PROFILE_ID),
            )
        }
    }

    /** 编辑 options 键：校验合法才接受（非法值丢弃并提示），随后标脏。 */
    fun updateOption(key: String, value: String) {
        val validated: String? = GameOptionsValidator.validate(key, value)
        if (validated == null) {
            _message.value = "非法值已忽略：$key = $value"
            return
        }
        val current: UiState = _uiState.value
        _uiState.value = current.copy(working = current.working.with(key, validated), dirty = true)
    }

    fun updateLauncher(transform: (LauncherSettings) -> LauncherSettings) {
        val current: UiState = _uiState.value
        _uiState.value = current.copy(launcher = transform(current.launcher), dirty = true)
    }

    /** 保存：options.txt 先备份再原子写（游戏运行中拒绝），启动器配置 JSON 同步写。 */
    fun save() {
        viewModelScope.launch(Dispatchers.IO) {
            val current: UiState = _uiState.value
            val validatedLauncher: LauncherSettings = LauncherSettingsValidator.validate(current.launcher)
                ?: run {
                    _message.value = "启动器设置非法，保存中止"
                    return@launch
                }
            val optionsResult = stores.writeOptions(PROFILE_ID, current.working, runningCheck = deps.gameRunningCheck)
            if (optionsResult.isErr) {
                _message.value = "保存失败：${optionsResult.errorOrNull()?.code}"
                return@launch
            }
            val launcherResult = stores.writeLauncher(PROFILE_ID, validatedLauncher)
            if (launcherResult.isErr) {
                _message.value = "启动器配置保存失败：${launcherResult.errorOrNull()?.code}"
                return@launch
            }
            _uiState.value = current.copy(launcher = validatedLauncher, dirty = false, diff = emptyList())
            _message.value = "已保存（原文件已备份为 options.txt.bak）"
        }
    }

    /** 恢复默认模板（进入编辑态，需再点保存才落盘）。 */
    fun restoreDefault() {
        viewModelScope.launch(Dispatchers.IO) {
            val (templateOptions, templateLauncher) = stores.loadDefaultTemplate()
            val current: UiState = _uiState.value
            _uiState.value = current.copy(working = templateOptions, launcher = templateLauncher, dirty = true)
            _message.value = "已载入默认模板（未落盘，点保存生效）"
        }
    }

    /** 当前配置设为默认模板。 */
    fun saveAsDefaultTemplate() {
        viewModelScope.launch(Dispatchers.IO) {
            val current: UiState = _uiState.value
            val result = stores.saveDefaultTemplate(current.working, current.launcher)
            _message.value = if (result.isOk) "已设为默认模板" else "失败：${result.errorOrNull()?.code}"
        }
    }

    /** 对比差异：当前编辑态 vs 已保存磁盘版本。 */
    fun compareWithDisk() {
        viewModelScope.launch(Dispatchers.IO) {
            val disk: OptionsFile = stores.readOptions(PROFILE_ID)
            val current: UiState = _uiState.value
            val changes: List<OptionsChange> = disk.diff(current.working)
            _uiState.value = current.copy(diff = changes)
            _message.value = if (changes.isEmpty()) "与已保存版本无差异" else "共 ${changes.size} 项差异"
        }
    }

    fun export() {
        viewModelScope.launch(Dispatchers.IO) {
            val result = stores.exportOptions(PROFILE_ID, _uiState.value.working)
            _message.value = if (result.isOk) "已导出：${result.getOrNull()!!.absolutePath}" else "导出失败"
        }
    }

    fun importOptions() {
        viewModelScope.launch(Dispatchers.IO) {
            val result = stores.importOptions(PROFILE_ID)
            if (result.isErr) {
                _message.value = "导入失败：${result.errorOrNull()?.code}"
                return@launch
            }
            val current: UiState = _uiState.value
            _uiState.value = current.copy(working = result.getOrNull()!!, dirty = true)
            _message.value = "已导入（未落盘，点保存生效）"
        }
    }

    fun rollbackBak() {
        viewModelScope.launch(Dispatchers.IO) {
            val result = stores.restoreOptionsBak(PROFILE_ID)
            _message.value = if (result.isOk) "已从 options.txt.bak 回退" else "回退失败：${result.errorOrNull()?.code}"
            load()
        }
    }

    fun saveDefaultTouchScheme() {
        viewModelScope.launch(Dispatchers.IO) {
            val result = stores.saveTouchScheme(PROFILE_ID, TouchScheme(name = "default"))
            _message.value = if (result.isOk) "触控方案已保存" else "失败：${result.errorOrNull()?.code}"
            _uiState.value = _uiState.value.copy(touchSchemes = stores.listTouchSchemes(PROFILE_ID))
        }
    }

    companion object {
        const val PROFILE_ID: String = "default"
    }
}
