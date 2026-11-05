package org.aclpe.aclpe.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aclpe.aclpe.R
import org.aclpe.aclpe.ui.settings.GameSettingsViewModel
import org.aclpe.core.settings.launcher.GraphicsBackend
import org.aclpe.core.settings.launcher.LauncherSettings
import org.aclpe.core.settings.launcher.PerformanceSwitches
import org.aclpe.core.settings.options.GameOptionKeys
import org.aclpe.core.settings.options.OptionsChange

/**
 * 游戏外设置编辑器（Step 5 UI）：
 * 画面 / 声音 / 其他 / 控制 / 启动器侧 五组 + 保存/恢复默认/模板/差异/导入导出/回退。
 */
@Composable
fun GameSettingsSection(viewModel: GameSettingsViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val options: Map<String, String> = state.options

    Column(modifier = Modifier.fillMaxWidth()) {
        SectionTitle(stringResource(R.string.gs_group_video))
        IntField("渲染距离（2-32）", options[GameOptionKeys.RENDER_DISTANCE] ?: "12") { viewModel.updateOption(GameOptionKeys.RENDER_DISTANCE, it) }
        ChipRow(
            options = listOf("auto", "0", "1", "2", "3", "4"),
            selected = options[GameOptionKeys.GUI_SCALE] ?: "auto",
            onSelect = { viewModel.updateOption(GameOptionKeys.GUI_SCALE, it) },
        )
        SwitchRow("垂直同步", options[GameOptionKeys.VSYNC] == "true") { viewModel.updateOption(GameOptionKeys.VSYNC, it.toString()) }
        SwitchRow("全屏", options[GameOptionKeys.FULLSCREEN] == "true") { viewModel.updateOption(GameOptionKeys.FULLSCREEN, it.toString()) }
        IntField("最大帧率（0-260）", options[GameOptionKeys.MAX_FPS] ?: "120") { viewModel.updateOption(GameOptionKeys.MAX_FPS, it) }
        ChipRow(
            options = listOf("0", "1", "2", "3", "4"),
            selected = options[GameOptionKeys.MIPMAP_LEVELS] ?: "4",
            label = "Mipmap 级别",
            onSelect = { viewModel.updateOption(GameOptionKeys.MIPMAP_LEVELS, it) },
        )
        ChipRow(
            options = GraphicsBackend.entries.map { it.name },
            selected = state.launcher.graphicsBackend.name,
            label = "图形后端（启动器配置）",
            onSelect = { name ->
                viewModel.updateLauncher { it.copy(graphicsBackend = GraphicsBackend.valueOf(name)) }
            },
        )

        SectionTitle(stringResource(R.string.gs_group_other))
        TextFieldRow("语言", options[GameOptionKeys.LANG] ?: "zh_cn") { viewModel.updateOption(GameOptionKeys.LANG, it) }
        SwitchRow("强制 Unicode 字体", options[GameOptionKeys.FORCE_UNICODE_FONT] == "true") { viewModel.updateOption(GameOptionKeys.FORCE_UNICODE_FONT, it.toString()) }
        ChipRow(
            options = listOf("0", "1", "2"),
            selected = options[GameOptionKeys.CHAT_VISIBILITY] ?: "0",
            label = "聊天可见性",
            onSelect = { viewModel.updateOption(GameOptionKeys.CHAT_VISIBILITY, it) },
        )
        SwitchRow("精简调试信息", options[GameOptionKeys.REDUCED_DEBUG_INFO] == "true") { viewModel.updateOption(GameOptionKeys.REDUCED_DEBUG_INFO, it.toString()) }

        SectionTitle(stringResource(R.string.gs_group_control))
        Text(
            text = "按键映射：${state.keybindCount} 项（触控方案：${state.touchSchemes.joinToString { it }})",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { viewModel.saveDefaultTouchScheme() }) {
            Text("保存默认触控方案")
        }

        SectionTitle(stringResource(R.string.gs_group_launcher))
        ChipRow(
            options = listOf("LIGHT", "MEDIUM", "HIGH", "EXTREME"),
            selected = state.launcher.memoryTier,
            label = "内存档位",
            onSelect = { tier -> viewModel.updateLauncher { it.copy(memoryTier = tier) } },
        )
        TextFieldRow("额外 JVM 参数（逗号分隔）", state.launcher.extraJvmArgs) {
            viewModel.updateLauncher { l -> l.copy(extraJvmArgs = it.take(LauncherSettingsValidatorMax)) }
        }
        ChipRow(
            options = listOf("OFFICIAL", "BMCLAPI"),
            selected = state.launcher.mirrorId,
            label = "镜像源",
            onSelect = { m -> viewModel.updateLauncher { it.copy(mirrorId = m) } },
        )
        val perf: PerformanceSwitches = state.launcher.performanceSwitches
        listOf(
            "L0 基线" to perf.l0Baseline,
            "L1 AppCDS" to perf.l1AppCds,
            "L2 JIT 预热" to perf.l2JitWarmup,
            "L3 PipelineCache" to perf.l3PipelineCache,
            "L4 ADPF" to perf.l4Adpf,
            "L5 线程亲和" to perf.l5ThreadAffinity,
        ).forEach { (label, enabled) ->
            SwitchRow(label, enabled) { v ->
                viewModel.updateLauncher { l ->
                    val p: PerformanceSwitches = l.performanceSwitches
                    l.copy(
                        performanceSwitches = when (label.substringBefore(' ')) {
                            "L0" -> p.copy(l0Baseline = v)
                            "L1" -> p.copy(l1AppCds = v)
                            "L2" -> p.copy(l2JitWarmup = v)
                            "L3" -> p.copy(l3PipelineCache = v)
                            "L4" -> p.copy(l4Adpf = v)
                            else -> p.copy(l5ThreadAffinity = v)
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { viewModel.save() }) { Text("保存" + if (state.dirty) " ●" else "") }
            TextButton(onClick = { viewModel.restoreDefault() }) { Text("恢复默认") }
            TextButton(onClick = { viewModel.saveAsDefaultTemplate() }) { Text("设为默认模板") }
            TextButton(onClick = { viewModel.compareWithDisk() }) { Text("对比差异") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { viewModel.export() }) { Text("导出") }
            TextButton(onClick = { viewModel.importOptions() }) { Text("导入") }
            TextButton(onClick = { viewModel.rollbackBak() }) { Text("回退备份") }
        }
        if (state.diff.isNotEmpty()) {
            Text(
                text = state.diff.take(5).joinToString("\n") { c: OptionsChange ->
                    "${c.key}: ${c.oldValue ?: "（无）"} → ${c.newValue ?: "（删除）"}"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val LauncherSettingsValidatorMax: Int = 512

@Composable
private fun SectionTitle(title: String) {
    Spacer(modifier = Modifier.height(12.dp))
    Text(text = title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun IntField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    )
}

@Composable
private fun TextFieldRow(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ChipRow(
    options: List<String>,
    selected: String,
    label: String? = null,
    onSelect: (String) -> Unit,
) {
    if (label != null) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 2.dp)) {
        options.forEach { option ->
            FilterChip(
                selected = selected == option,
                onClick = { onSelect(option) },
                label = { Text(option) },
            )
        }
    }
}

@Composable
private fun SliderRow(label: String, value: Float, onChange: (Float) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Text(
                text = "${(value * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(value = value, onValueChange = onChange, valueRange = 0f..1f)
    }
}
