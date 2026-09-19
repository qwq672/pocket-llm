package com.pocketllm.ui.models

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketllm.domain.download.RemoteGgufFile
import com.pocketllm.ui.components.ModelCard
import com.pocketllm.ui.download.DownloadViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(
    vm: ModelsViewModel = viewModel(),
    dl: DownloadViewModel = viewModel()
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val d by dl.ui.collectAsStateWithLifecycle()

    val pickFile = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { vm.importFromUri(it) } }

    Scaffold(
        topBar = { TopAppBar(title = { Text("模型管理") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { pickFile.launch(arrayOf("application/octet-stream", "*/*")) },
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text("导入本地模型") }
            )
        }
    ) { inner ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // ===== 已下载 =====
            item { SectionTitle("已下载模型") }
            if (ui.models.isEmpty()) {
                item { EmptyHint("还没有模型。点右下角导入 .gguf，或在下方一键下载推荐模型。") }
            }
            items(ui.models, key = { it.id }) { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ModelCard(
                        model = m,
                        isActive = ui.activeId == m.id,
                        onClick = { vm.activate(m.id) }
                    )
                    IconButton(onClick = { vm.delete(m) }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "删除",
                            tint = MaterialTheme.colorScheme.error)
                    }
                }
            }

            // ===== 推荐一键下载 =====
            item { SectionTitle("推荐模型（一键下载）") }
            items(dl.prefabs, key = { it.file }) { p ->
                PrefabRow(
                    label = p.label,
                    note = p.note,
                    progress = d.progress[p.file] ?: 0,
                    onDownload = { dl.downloadPrefab(p) }
                )
            }

            // ===== 仓库浏览 =====
            item { SectionTitle("从仓库浏览") }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        d.sources.forEach { src ->
                            FilterChip(
                                selected = src.id == d.currentSourceId,
                                onClick = { dl.selectSource(src.id) },
                                label = { Text(src.displayNameZh) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = d.repoInput,
                        onValueChange = dl::onRepoInput,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("仓库 ID，例如 Qwen/Qwen2.5-1.5B-Instruct-GGUF") },
                        singleLine = true
                    )
                    Button(onClick = { dl.listFiles() }, modifier = Modifier.fillMaxWidth()) {
                        Text("列出文件")
                    }
                    if (d.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    d.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
            items(d.files, key = { it.path }) { f ->
                RemoteFileRow(
                    file = f,
                    progress = d.progress[f.path] ?: 0,
                    onDownload = { dl.download(d.repoInput, f) }
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.outline
    )
}

@Composable
private fun PrefabRow(label: String, note: String, progress: Int, onDownload: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                Text(note, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline)
            }
            if (progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .fillMaxWidth(0.4f)
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

@Composable
private fun RemoteFileRow(file: RemoteGgufFile, progress: Int, onDownload: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(file.path, style = MaterialTheme.typography.bodyMedium)
            Text("${file.sizeBytes / 1024 / 1024} MB",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline)
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
