package com.pocketllm.ui.models

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.pocketllm.PocketLLMApp
import com.pocketllm.ui.components.ModelCard

@Composable
fun ModelsScreen(vm: ModelsViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()

    val pickFile = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { vm.importFromUri(it) } }

    LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("模型") })
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { pickFile.launch(arrayOf("application/octet-stream", "*/*")) },
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text("导入本地模型") }
            )
        }
    ) { inner ->
        if (ui.models.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text("还没有模型", style = MaterialTheme.typography.titleLarge)
                Text(
                    "点击右下角「导入本地模型」选择 .gguf 文件，或到「下载」页拉取。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
        ) {
            items(ui.models, key = { it.id }) { m ->
                ModelCard(
                    model = m,
                    isActive = ui.activeId == m.id,
                    onClick = { vm.activate(m.id) }
                )
            }
        }
    }
}
