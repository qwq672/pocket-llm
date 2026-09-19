package com.pocketllm.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketllm.PocketLLMApp
import com.pocketllm.data.model.ChatMessage
import com.pocketllm.data.repo.SettingsRepository
import com.pocketllm.domain.inference.InferenceEngine
import com.pocketllm.domain.inference.BackendType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ChatViewModel : ViewModel() {

    private val container get() = PocketLLMApp.instance.container
    private val engine: InferenceEngine get() = container.inferenceEngine
    private val settings: SettingsRepository get() = container.settingsRepository

    data class UiState(
        val messages: List<ChatMessage> = emptyList(),
        val streaming: Boolean = false,
        val currentStream: String = "",
        val backendName: String = "CPU",
        val tokensPerSecond: Float = 0f,
        val thermalPercent: Int = 0,
        val errorMessage: String? = null,
        val hasModel: Boolean = false,
        val modelName: String = ""
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        // 监听 engine stats
        viewModelScope.launch {
            engine.stats.collectLatest { s ->
                _ui.value = _ui.value.copy(
                    tokensPerSecond = s.tokensPerSecond,
                    thermalPercent = s.thermalPercent,
                    backendName = s.backend.displayNameZh
                )
            }
        }
        // 监听 engine state
        viewModelScope.launch {
            engine.state.collectLatest { st ->
                _ui.value = _ui.value.copy(
                    hasModel = st.loaded,
                    modelName = st.modelPath?.substringAfterLast('/') ?: ""
                )
            }
        }
    }

    fun send(text: String) {
        if (_ui.value.streaming) return
        val msg = ChatMessage(role = "user", content = text, sessionId = 0)
        _ui.value = _ui.value.copy(
            messages = _ui.value.messages + msg,
            streaming = true,
            currentStream = ""
        )

        viewModelScope.launch {
            val prompt = buildPrompt(_ui.value.messages)
            val collected = StringBuilder()
            engine.completion(
                prompt = prompt,
                onToken = { tok ->
                    collected.append(tok)
                    _ui.value = _ui.value.copy(currentStream = collected.toString())
                },
                onStop = { reason ->
                    val assistant = ChatMessage(
                        role = "assistant",
                        content = collected.toString(),
                        sessionId = 0,
                        tokensPerSecond = _ui.value.tokensPerSecond
                    )
                    _ui.value = _ui.value.copy(
                        messages = _ui.value.messages + assistant,
                        streaming = false,
                        currentStream = ""
                    )
                }
            )
        }
    }

    fun stop() {
        engine.interrupt()
        _ui.value = _ui.value.copy(streaming = false, currentStream = "")
    }

    fun clearMessages() {
        _ui.value = _ui.value.copy(messages = emptyList())
    }

    /** 简化版 prompt 模板，实际可按 model arch 选用 chat template */
    private fun buildPrompt(msgs: List<ChatMessage>): String {
        val sb = StringBuilder()
        for (m in msgs) {
            when (m.role) {
                "user"      -> sb.append("User: ").append(m.content).append("\n")
                "assistant" -> sb.append("Assistant: ").append(m.content).append("\n")
            }
        }
        sb.append("Assistant: ")
        return sb.toString()
    }
}
