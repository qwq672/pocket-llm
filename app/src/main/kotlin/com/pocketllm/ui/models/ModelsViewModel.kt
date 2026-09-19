package com.pocketllm.ui.models

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketllm.PocketLLMApp
import com.pocketllm.data.model.ModelInfo
import com.pocketllm.data.repo.SettingsRepository
import com.pocketllm.domain.inference.InferenceConfig
import com.pocketllm.domain.inference.InferenceEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

class ModelsViewModel : ViewModel() {

    private val container get() = PocketLLMApp.instance.container
    private val repo get() = container.modelRepository
    private val engine: InferenceEngine get() = container.inferenceEngine
    private val settings: SettingsRepository get() = container.settingsRepository

    data class UiState(
        val models: List<ModelInfo> = emptyList(),
        val activeId: Long? = null,
        val loading: Boolean = false
    )
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            repo.observeAll().collectLatest { list ->
                _ui.value = _ui.value.copy(models = list)
            }
        }
        viewModelScope.launch {
            engine.state.collectLatest { st ->
                // activeId 通过 modelPath 反查（简化处理）
                val active = _ui.value.models.firstOrNull { it.filePath == st.modelPath }
                _ui.value = _ui.value.copy(activeId = active?.id)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val list = _ui.value.models
            // 自动加载上次模型
            val cfg = settings.config.let { /* trigger */ it }
            // 这里仅占位，真正 autoLoad 在 App 启动时做
        }
    }

    fun importFromUri(uri: Uri) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true)
            runCatching { repo.importFromUri(uri) }
            _ui.value = _ui.value.copy(loading = false)
        }
    }

    fun activate(id: Long) {
        viewModelScope.launch {
            val m = repo.getById(id) ?: return@launch
            // 读当前设置
            val config = container.settingsRepository.config.firstOrNull()
                ?: InferenceConfig()
            engine.loadModel(m.filePath, config)
            settings.setLastModel(id)
            repo.touchUsed(id)
        }
    }
}
