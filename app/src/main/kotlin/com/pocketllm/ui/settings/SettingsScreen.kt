package com.pocketllm.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketllm.domain.inference.BackendType
import com.pocketllm.domain.inference.InferenceConfig
import com.pocketllm.ui.components.SliderWithLabel
import com.pocketllm.ui.components.SwitchRow

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
            // ===== 后端选择 =====
            Section("后端 / Backend")
            BackendSelector(cfg.backend, vm::onBackendChange)
            Subtitle("仅 Vulkan / NPU / OpenCL 后端可调节 GPU 层数")
            SliderWithLabel(
                title = "GPU/NPU 层数",
                value = cfg.nGpuLayers.toFloat(),
                valueRange = 0f..100f,
                valueText = if (cfg.nGpuLayers == 0) "自动（NPU=全部）" else cfg.nGpuLayers.toString(),
                onValueChange = { vm.update(cfg.copy(nGpuLayers = it.toInt())) }
            )

            // ===== CPU 线程 =====
            Section("CPU 线程 / Threads")
            SliderWithLabel(
                title = "线程数",
                value = cfg.cpuThreads.toFloat(),
                valueRange = 0f..8f,
                steps = 7,
                valueText = if (cfg.cpuThreads == 0) "自动（按大核数）" else cfg.cpuThreads.toString(),
                onValueChange = { vm.update(cfg.copy(cpuThreads = it.toInt())) }
            )

            // ===== 批处理 =====
            Section("批处理 / Batch")
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

            // ===== KV 量化 =====
            Section("KV Cache 量化")
            KvQuantSelector(cfg.kvQuant, vm::onKvQuantChange)
            Subtitle("更高级别量化 → 内存占用↓、带宽↓、温度↓；质量略降")

            // ===== 上下文 =====
            Section("上下文长度 / Context")
            SliderWithLabel(
                title = "Context",
                value = cfg.contextLength.toFloat(),
                valueRange = 512f..32768f,
                valueText = cfg.contextLength.toString(),
                onValueChange = { vm.update(cfg.copy(contextLength = it.toInt())) }
            )

            // ===== 防回退 =====
            Section("热管理")
            SwitchRow(
                title = "防回退（Anti-Rollback）",
                subtitle = "开：保持 GPU/NPU 频率不被热降档，性能优先但发热更明显。\n关：允许热感知软降（推荐，发烫治理）",
                checked = cfg.antiRollback,
                onCheckedChange = { vm.update(cfg.copy(antiRollback = it)) }
            )

            // ===== 模型设置 =====
            Section("模型采样参数")
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

            // ===== 自动加载 =====
            Section("启动行为")
            SwitchRow(
                title = "启动时自动加载上次模型",
                subtitle = "下次打开 App 自动恢复推理状态",
                checked = cfg.autoLoadModel,
                onCheckedChange = { vm.update(cfg.copy(autoLoadModel = it)) }
            )

            // ===== 关于 =====
            Section("关于")
            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("PocketLLM", style = MaterialTheme.typography.titleMedium)
                    Text("Kotlin + Compose + llama.cpp + ggml-hexagen", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    Text("Native: ${ui.nativeVersion}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    Text("SoC: ${ui.socName} · 大核 ${ui.bigCores}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun Subtitle(text: String) {
    Text(
        text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline
    )
}

@Composable
private fun BackendSelector(selected: BackendType, onSelect: (BackendType) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        BackendType.entries.forEach { b ->
            FilterChip(
                selected = selected == b,
                onClick = { onSelect(b) },
                label = { Text(b.displayNameZh) }
            )
        }
    }
}

@Composable
private fun KvQuantSelector(selected: String, onSelect: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        listOf("f16" to "F16（最高质量）", "q8_0" to "Q8_0（推荐）", "q4_0" to "Q4_0（最省内存）").forEach { (id, label) ->
            FilterChip(
                selected = selected == id,
                onClick = { onSelect(id) },
                label = { Text(label) }
            )
        }
    }
}
