package com.pocketllm.ui.download

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketllm.PocketLLMApp
import com.pocketllm.data.repo.SettingsRepository
import com.pocketllm.domain.download.ModelSource
import com.pocketllm.domain.download.RemoteGgufFile
import com.pocketllm.util.FileUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

class DownloadViewModel : ViewModel() {

    private val container get() = PocketLLMApp.instance.container
    private val settings: SettingsRepository get() = container.settingsRepository

    data class UiState(
        val sources: List<ModelSource> = emptyList(),
        val currentSourceId: String = "hf_mirror",
        val repoInput: String = "Qwen/Qwen2.5-1.5B-Instruct-GGUF",
        val files: List<RemoteGgufFile> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
        val progress: Map<String, Int> = emptyMap()
    )

    private val _ui = MutableStateFlow(UiState(sources = container.downloadSources))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            settings.downloadSource.collectLatest { src ->
                _ui.value = _ui.value.copy(currentSourceId = src)
            }
        }
    }

    fun selectSource(id: String) {
        viewModelScope.launch { settings.setDownloadSource(id) }
    }

    fun onRepoInput(v: String) { _ui.value = _ui.value.copy(repoInput = v) }

    val listFiles: () -> Unit = {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null, files = emptyList())
            runCatching {
                val src = container.sourceById(_ui.value.currentSourceId)
                src.listGgufFiles(_ui.value.repoInput)
            }.onSuccess { files ->
                _ui.value = _ui.value.copy(loading = false, files = files)
            }.onFailure { e ->
                _ui.value = _ui.value.copy(loading = false, error = e.message ?: "未知错误")
            }
        }
        Unit
    }

    fun download(file: RemoteGgufFile) {
        viewModelScope.launch {
            val src = container.sourceById(_ui.value.currentSourceId)
            val url = src.fileUrl(_ui.value.repoInput, file.path)
            val destDir = FileUtils.modelsDir(container.appContext)
            val dest = File(destDir, file.path.substringAfterLast('/'))
            runCatching {
                container.downloadManager.download(url, dest) { p ->
                    _ui.value = _ui.value.copy(
                        progress = _ui.value.progress + (file.path to p)
                    )
                }
            }.onSuccess {
                container.modelRepository.registerDownloaded(
                    filePath = it.absolutePath,
                    name = it.nameWithoutExtension
                )
            }.onFailure { e ->
                _ui.value = _ui.value.copy(error = e.message)
            }
        }
    }
}
