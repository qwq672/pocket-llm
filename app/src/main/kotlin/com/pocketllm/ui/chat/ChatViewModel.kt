package com.pocketllm.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketllm.PocketLLMApp
import com.pocketllm.data.model.ChatMessage
import com.pocketllm.data.repo.SettingsRepository
import com.pocketllm.domain.inference.InferenceEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.pocketllm.util.logi
import com.pocketllm.util.loge

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
        val modelName: String = "",
        val contextUsed: Int = 0,
        val contextMax: Int = 0
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    @Volatile private var systemPrompt: String = ""
    @Volatile private var thinkingMode: Boolean = true

    init {
        viewModelScope.launch {
            settings.config.collectLatest { systemPrompt = it.systemPrompt }
        }
        viewModelScope.launch {
            settings.thinkingMode.collectLatest { thinkingMode = it }
        }
        viewModelScope.launch {
            engine.stats.collectLatest { s ->
                _ui.value = _ui.value.copy(
                    tokensPerSecond = s.tokensPerSecond,
                    thermalPercent = s.thermalPercent,
                    backendName = s.backend.displayNameZh,
                    contextUsed = s.contextUsed,
                    contextMax = s.contextMax
                )
            }
        }
        viewModelScope.launch {
            engine.state.collectLatest { st ->
                _ui.value = _ui.value.copy(
                    hasModel = st.loaded,
                    modelName = st.modelPath?.substringAfterLast('/') ?: "",
                    errorMessage = st.error
                )
            }
        }
    }

    fun send(text: String) {
        if (_ui.value.streaming) return
        if (text.isBlank()) return
        if (!_ui.value.hasModel) {
            _ui.value = _ui.value.copy(errorMessage = "尚未加载模型，请先到「模型」页加载")
            return
        }
        val msg = ChatMessage(role = "user", content = text, sessionId = 0)
        _ui.value = _ui.value.copy(
            messages = _ui.value.messages + msg,
            streaming = true,
            currentStream = "",
            errorMessage = null
        )
        logi("ChatViewModel: send ${text.length} chars")

        viewModelScope.launch {
            val prompt = buildPrompt(_ui.value.messages)
            val collected = StringBuilder()
            try {
                engine.completion(
                    prompt = prompt,
                    onToken = { tok ->
                        collected.append(tok)
                        _ui.value = _ui.value.copy(currentStream = collected.toString())
                    },
                    onStop = { reason ->
                        val tps = engine.stats.value.tokensPerSecond
                        val assistant = ChatMessage(
                            role = "assistant",
                            content = collected.toString(),
                            sessionId = 0,
                            tokensPerSecond = tps
                        )
                        val err = if (reason == "decode_failed" || reason == "callback_error" || reason.startsWith("exception"))
                            "推理中断：$reason" else null
                        _ui.value = _ui.value.copy(
                            messages = _ui.value.messages + assistant,
                            streaming = false,
                            currentStream = "",
                            errorMessage = err
                        )
                        logi("ChatViewModel: completion stopped reason=$reason tps=$tps")
                    }
                )
            } catch (t: Throwable) {
                loge("ChatViewModel: completion threw", t)
                _ui.value = _ui.value.copy(
                    streaming = false,
                    currentStream = "",
                    errorMessage = "推理异常: ${t.message}"
                )
            }
        }
    }

    fun stop() {
        engine.interrupt()
        // 不立即清空 streaming —— 等 onStop 回调中处理，避免状态不一致
    }

    fun clearMessages() {
        if (_ui.value.streaming) return
        _ui.value = _ui.value.copy(messages = emptyList(), errorMessage = null)
    }

    /** 删除单条消息 */
    fun deleteMessage(m: ChatMessage) {
        if (_ui.value.streaming) return
        _ui.value = _ui.value.copy(messages = _ui.value.messages.filterNot { it.ts == m.ts && it.role == m.role })
    }

    /**
     * 重新生成最后一条 assistant 回复：
     * 移除最后一条 assistant，然后基于现有对话历史重新调用 engine.completion。
     * 如果最后一条不是 assistant，啥也不做。
     */
    fun regenerate() {
        if (_ui.value.streaming) return
        val msgs = _ui.value.messages
        if (msgs.isEmpty()) return
        val last = msgs.last()
        if (last.role != "assistant") return
        val withoutLast = msgs.dropLast(1)
        _ui.value = _ui.value.copy(
            messages = withoutLast,
            streaming = true,
            currentStream = "",
            errorMessage = null
        )
        logi("ChatViewModel: regenerate")
        viewModelScope.launch {
            val prompt = buildPrompt(withoutLast)
            val collected = StringBuilder()
            try {
                engine.completion(
                    prompt = prompt,
                    onToken = { tok ->
                        collected.append(tok)
                        _ui.value = _ui.value.copy(currentStream = collected.toString())
                    },
                    onStop = { reason ->
                        val tps = engine.stats.value.tokensPerSecond
                        val assistant = ChatMessage(
                            role = "assistant",
                            content = collected.toString(),
                            sessionId = 0,
                            tokensPerSecond = tps
                        )
                        _ui.value = _ui.value.copy(
                            messages = _ui.value.messages + assistant,
                            streaming = false,
                            currentStream = ""
                        )
                        logi("ChatViewModel: regenerate stopped reason=$reason tps=$tps")
                    }
                )
            } catch (t: Throwable) {
                loge("ChatViewModel: regenerate threw", t)
                _ui.value = _ui.value.copy(
                    streaming = false,
                    currentStream = "",
                    errorMessage = "重新生成异常: ${t.message}"
                )
            }
        }
    }

    fun dismissError() {
        _ui.value = _ui.value.copy(errorMessage = null)
    }

    /**
     * 构造聊天 prompt。
     * - 如果 thinkingMode=true，在 system prompt 末尾加 "/think" 标志（Qwen3 支持）
     * - 如果用户没设 system prompt，且 thinkingMode=true，自动加默认 prompt + /think
     * - 如果 thinkingMode=false，加 /no_think 标志
     */
    private fun buildPrompt(msgs: List<ChatMessage>): String {
        val sb = StringBuilder()
        val effectiveSystem = if (systemPrompt.isBlank()) {
            // 默认不强制 system prompt，让 /think 标志自己发挥作用
            if (thinkingMode) "/think" else "/no_think"
        } else {
            val flag = if (thinkingMode) "/think" else "/no_think"
            "${systemPrompt.trim()}\n$flag"
        }
        sb.append("System: ").append(effectiveSystem).append("\n\n")
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
