package com.pocketllm.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketllm.domain.inference.BackendType
import com.pocketllm.ui.components.SliderWithLabel
import com.pocketllm.ui.components.SwitchRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val cfg = ui.config

    Scaffold(topBar = { TopAppBar(title = { Text("设置") }) }) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp)
        ) {
            // ===== 外观 =====
            Section("外观", icon = "🎨")
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SwitchRow(
                        title = "Material You 动态取色",
                        subtitle = "跟随系统壁纸自动配色（仅 Android 12+）",
                        checked = ui.dynamicColor,
                        onCheckedChange = vm::onDynamicColorChange
                    )
                    HorizontalDivider()
                    Text("主题模式", style = MaterialTheme.typography.bodyLarge)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        val opts = listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色")
                        opts.forEachIndexed { i, (id, label) ->
                            SegmentedButton(
                                selected = ui.darkTheme == id,
                                onClick = { vm.onDarkThemeChange(id) },
                                shape = SegmentedButtonDefaults.itemShape(i, opts.size)
                            ) { Text(label) }
                        }
                    }
                }
            }

            // ===== 后端 =====
            Section("后端", icon = "🚀")
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    BackendSelector(cfg.backend, vm::onBackendChange, ui)
                    Subtitle("仅 Vulkan / NPU / OpenCL 后端可调节 GPU 层数。不可用的后端会自动回退到 CPU。")
                    SliderWithLabel(
                        title = "GPU/NPU 层数",
                        value = cfg.nGpuLayers.toFloat(),
                        valueRange = 0f..100f,
                        valueText = if (cfg.nGpuLayers == 0) "自动（NPU=全部）" else cfg.nGpuLayers.toString(),
                        onValueChange = { vm.update(cfg.copy(nGpuLayers = it.toInt())) }
                    )
                }
            }

            // ===== 性能 =====
            Section("性能", icon = "⚡")
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SliderWithLabel(
                        title = "CPU 线程数",
                        value = cfg.cpuThreads.toFloat(),
                        valueRange = 0f..8f,
                        steps = 7,
                        valueText = if (cfg.cpuThreads == 0) "自动（按大核数）" else cfg.cpuThreads.toString(),
                        onValueChange = { vm.update(cfg.copy(cpuThreads = it.toInt())) }
                    )
                    SliderWithLabel(
                        title = "物理批处理 (physical batch)",
                        value = cfg.physicalBatch.toFloat(),
                        valueRange = 64f..4096f,
                        valueText = cfg.physicalBatch.toString(),
                        onValueChange = { vm.update(cfg.copy(physicalBatch = it.toInt())) }
                    )
                    SliderWithLabel(
                        title = "逻辑批处理 (batch)",
                        value = cfg.batch.toFloat(),
                        valueRange = 256f..8192f,
                        valueText = cfg.batch.toString(),
                        onValueChange = { vm.update(cfg.copy(batch = it.toInt())) }
                    )
                    Text("KV Cache 量化", style = MaterialTheme.typography.bodyLarge)
                    KvQuantSelector(cfg.kvQuant, vm::onKvQuantChange)
                    Subtitle("更高级别量化 → 内存占用↓、带宽↓、温度↓；质量略降")
                    SliderWithLabel(
                        title = "上下文长度",
                        value = cfg.contextLength.toFloat(),
                        valueRange = 512f..32768f,
                        valueText = cfg.contextLength.toString(),
                        onValueChange = { vm.update(cfg.copy(contextLength = it.toInt())) }
                    )
                }
            }

            // ===== 热管理（危险区） =====
            Section("热管理", icon = "🔥")
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    SwitchRow(
                        title = "防回退（Anti-Rollback）",
                        subtitle = "开：保持 GPU/NPU 频率不被热降档，性能优先但发热更明显。\n关：允许热感知软降（推荐，发烫治理）",
                        checked = cfg.antiRollback,
                        onCheckedChange = { vm.update(cfg.copy(antiRollback = it)) }
                    )
                }
            }

            // ===== 采样 =====
            Section("采样", icon = "🎲")
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SliderWithLabel(
                        title = "Temperature",
                        value = cfg.temperature,
                        valueRange = 0.01f..2f,
                        valueText = "%.2f".format(cfg.temperature),
                        onValueChange = { vm.update(cfg.copy(temperature = it)) }
                    )
                    SliderWithLabel(
                        title = "Top-K",
                        value = cfg.topK.toFloat(),
                        valueRange = 1f..200f,
                        valueText = cfg.topK.toString(),
                        onValueChange = { vm.update(cfg.copy(topK = it.toInt())) }
                    )
                    SliderWithLabel(
                        title = "Top-P",
                        value = cfg.topP,
                        valueRange = 0.1f..1f,
                        valueText = "%.2f".format(cfg.topP),
                        onValueChange = { vm.update(cfg.copy(topP = it)) }
                    )
                    SliderWithLabel(
                        title = "Repeat Penalty",
                        value = cfg.repeatPenalty,
                        valueRange = 1f..2f,
                        valueText = "%.2f".format(cfg.repeatPenalty),
                        onValueChange = { vm.update(cfg.copy(repeatPenalty = it)) }
                    )
                }
            }

            // ===== 启动行为 =====
            Section("启动行为", icon = "🔄")
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(16.dp)) {
                    SwitchRow(
                        title = "启动时自动加载上次模型",
                        subtitle = "下次打开 App 自动恢复推理状态",
                        checked = cfg.autoLoadModel,
                        onCheckedChange = { vm.update(cfg.copy(autoLoadModel = it)) }
                    )
                }
            }

            // ===== 对话 =====
            Section("对话", icon = "💬")
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = cfg.systemPrompt,
                        onValueChange = { vm.update(cfg.copy(systemPrompt = it)) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("系统提示词（System Prompt）") },
                        placeholder = { Text("例：你是一个严谨的助手，用中文简洁回答。留空则不附加。") },
                        minLines = 2,
                        maxLines = 5
                    )
                }
            }

            // ===== 日志 =====
            Section("日志", icon = "📝")
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SwitchRow(
                        title = "启用文件日志",
                        subtitle = "写入外部存储 ${ui.logFilePath ?: "(未初始化)"}，便于排查问题",
                        checked = ui.loggingEnabled,
                        onCheckedChange = vm::onLoggingEnabledChange
                    )
                    HorizontalDivider()
                    Text(
                        "路径：${ui.logFilePath ?: "(未初始化)"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "提示：在文件管理器中查看 /sdcard/Android/data/com.pocketllm/files/pocketllm.log",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // 这里只能用 TextButton 之类的简单控件，不做跳转/查看
                    }
                }
            }

            // ===== 关于 =====
            Section("关于", icon = "ℹ️")
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("PocketLLM", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Kotlin + Compose + llama.cpp", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Native: ${ui.nativeVersion}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("SoC: ${ui.socName} · 大核 ${ui.bigCores}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // 底部留白
            androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 16.dp))
        }
    }
}

@Composable
private fun Section(title: String, icon: String = "") {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (icon.isNotEmpty()) Text(icon, style = MaterialTheme.typography.titleMedium)
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun Subtitle(text: String) {
    Text(
        text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun BackendSelector(
    selected: BackendType,
    onSelect: (BackendType) -> Unit,
    ui: SettingsViewModel.UiState
) {
    val opts = BackendType.entries.map { b ->
        val enabled = when (b) {
            BackendType.CPU -> true
            BackendType.VULKAN -> ui.vulkanAvailable
            BackendType.NPU -> ui.npuAvailable
            BackendType.OPENCL -> ui.openclAvailable
        }
        b to enabled
    }
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        opts.forEach { (b, enabled) ->
            FilterChip(
                selected = selected == b,
                onClick = { if (enabled) onSelect(b) },
                enabled = enabled,
                label = {
                    val suffix = if (!enabled) " · 不可用" else ""
                    Text(b.displayNameZh + suffix)
                }
            )
        }
    }
}

@Composable
private fun KvQuantSelector(selected: String, onSelect: (String) -> Unit) {
    val opts = listOf("f16" to "F16（最高质量）", "q8_0" to "Q8_0（推荐）", "q4_0" to "Q4_0（最省内存）")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        opts.forEach { (id, label) ->
            FilterChip(
                selected = selected == id,
                onClick = { onSelect(id) },
                label = { Text(label) }
            )
        }
    }
}
