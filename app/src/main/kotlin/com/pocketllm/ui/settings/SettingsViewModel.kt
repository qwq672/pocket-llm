package com.pocketllm.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketllm.PocketLLMApp
import com.pocketllm.domain.inference.BackendType
import com.pocketllm.domain.inference.InferenceConfig
import com.pocketllm.infra.jni.NativeBridge
import com.pocketllm.util.AppLogger
import com.pocketllm.util.CpuInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SettingsViewModel : ViewModel() {

    private val container get() = PocketLLMApp.instance.container

    data class UiState(
        val config: InferenceConfig = InferenceConfig(),
        val nativeVersion: String = "",
        val socName: String = "",
        val bigCores: Int = 0,
        val dynamicColor: Boolean = true,
        val darkTheme: String = "system",
        val loggingEnabled: Boolean = true,
        val logFilePath: String? = null,
        // 可用后端
        val vulkanAvailable: Boolean = false,
        val npuAvailable: Boolean = false,
        val openclAvailable: Boolean = false
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            container.settingsRepository.config.collectLatest { c ->
                _ui.value = _ui.value.copy(config = c)
            }
        }
        viewModelScope.launch {
            container.settingsRepository.dynamicColor.collectLatest {
                _ui.value = _ui.value.copy(dynamicColor = it)
            }
        }
        viewModelScope.launch {
            container.settingsRepository.darkTheme.collectLatest {
                _ui.value = _ui.value.copy(darkTheme = it)
            }
        }
        viewModelScope.launch {
            container.settingsRepository.loggingEnabled.collectLatest {
                _ui.value = _ui.value.copy(loggingEnabled = it)
                AppLogger.setEnabled(it)
            }
        }
        viewModelScope.launch {
            runCatching { container.nativeBridge.nativeVersion() }
                .onSuccess { _ui.value = _ui.value.copy(nativeVersion = it) }
                .onFailure { _ui.value = _ui.value.copy(nativeVersion = "native not loaded") }
        }
        _ui.value = _ui.value.copy(
            socName = CpuInfo.socName,
            bigCores = CpuInfo.bigCores,
            logFilePath = AppLogger.filePath()
        )
        // 探测后端可用性
        viewModelScope.launch {
            val b = container.nativeBridge
            _ui.value = _ui.value.copy(
                vulkanAvailable = runCatching { b.vulkanAvailable() }.getOrDefault(false),
                npuAvailable    = runCatching { b.npuAvailable() }.getOrDefault(false),
                openclAvailable = runCatching { b.openclAvailable() }.getOrDefault(false)
            )
        }
    }

    fun update(c: InferenceConfig) {
        viewModelScope.launch { container.settingsRepository.save(c) }
    }

    fun onBackendChange(b: BackendType) {
        update(_ui.value.config.copy(backend = b))
    }

    fun onKvQuantChange(q: String) {
        update(_ui.value.config.copy(kvQuant = q))
    }

    fun onDynamicColorChange(on: Boolean) {
        viewModelScope.launch { container.settingsRepository.setDynamicColor(on) }
    }

    fun onDarkThemeChange(mode: String) {
        viewModelScope.launch { container.settingsRepository.setDarkTheme(mode) }
    }

    fun onLoggingEnabledChange(on: Boolean) {
        viewModelScope.launch { container.settingsRepository.setLoggingEnabled(on) }
    }

    fun clearLog() {
        AppLogger.clear()
    }
}
