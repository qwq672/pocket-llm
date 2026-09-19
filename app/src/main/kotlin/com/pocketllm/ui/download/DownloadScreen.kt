package com.pocketllm.ui.download

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketllm.domain.download.RemoteGgufFile

@Composable
fun DownloadScreen(vm: DownloadViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text("下载模型") }) }) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 下载源切换
            Text("选择下载源", style = MaterialTheme.typography.titleMedium)
            androidx.compose.foundation.layout.Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ui.sources.forEach { src ->
                    FilterChip(
                        selected = src.id == ui.currentSourceId,
                        onClick = { vm.selectSource(src.id) },
                        label = { Text(src.displayNameZh) }
                    )
                }
            }

            // 仓库输入
            OutlinedTextField(
                value = ui.repoInput,
                onValueChange = vm::onRepoInput,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("仓库 ID，例如 Qwen/Qwen2.5-1.5B-Instruct-GGUF") },
                singleLine = true
            )

            Button(
                onClick = { vm.listFiles() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("列出文件") }

            if (ui.loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            ui.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            // 文件列表
            LazyColumn(
                Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(ui.files, key = { it.path }) { f ->
                    FileRow(
                        file = f,
                        progress = ui.progress[f.path] ?: 0,
                        onDownload = { vm.download(f) }
                    )
                }
            }
        }
    }
}

@Composable
private fun FileRow(file: RemoteGgufFile, progress: Int, onDownload: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(file.path, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${file.sizeBytes / 1024 / 1024} MB",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            if (progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                AssistChip(
                    onClick = onDownload,
                    label = { Text("下载") },
                    leadingIcon = { Icon(Icons.Outlined.Download, contentDescription = null) }
                )
            }
        }
    }
}
