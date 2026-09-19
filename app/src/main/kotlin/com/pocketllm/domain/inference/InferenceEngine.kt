package com.pocketllm.domain.inference

import android.util.Log
import com.pocketllm.infra.jni.NativeBridge
import com.pocketllm.util.CpuInfo
import com.pocketllm.util.ThermalMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 推理引擎统一入口。
 *
 * - 维护当前 Backend 实例，按 [InferenceConfig.backend] 切换。
 * - 把 [MemoryOptimizer] 与 [ThermalGovernor] 注入到当前 Backend。
 * - 暴露 stats 流，UI 实时显示。
 *
 * 单例，App 生命周期内唯一。线程安全。
 */
class InferenceEngine(
    private val nativeBridge: NativeBridge,
    private val thermalMonitor: ThermalMonitor,
    private val cpuInfo: CpuInfo
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _stats = MutableStateFlow(BackendStats(0f, 0, 0, 0L, 0, BackendType.CPU))
    val stats: StateFlow<BackendStats> = _stats.asStateFlow()

    private val memoryOptimizer = MemoryOptimizer(nativeBridge)
    private val thermalGovernor = ThermalGovernor(thermalMonitor)

    private var currentBackend: Backend? = null
    private var currentModelPath: String? = null

    /** 切换后端 + 加载模型。已加载相同后端则只更新 config。 */
    suspend fun loadModel(modelPath: String, config: InferenceConfig): Result<Unit> = withContext(Dispatchers.Default) {
        mutex.withLock {
            runCatching {
                // 1. 内存预热（避免大块 malloc 触发 mmap 抖动）
                memoryOptimizer.warmup(config)

                // 2. 若后端类型变了，先卸载旧的
                val needSwitch = currentBackend?.type != config.backend || currentModelPath != modelPath
                if (needSwitch) {
                    currentBackend?.close()
                    currentBackend = createBackend(config.backend)
                }

                val backend = currentBackend ?: error("backend null")
                if (!backend.isAvailable()) {
                    error("后端 ${config.backend} 不可用（设备不支持或 .so 未加载）")
                }

                // 3. 注入热档策略
                val tuned = thermalGovernor.tuneConfig(config)

                val ok = backend.load(modelPath, tuned)
                if (!ok) error("加载失败：${modelPath}")

                currentModelPath = modelPath
                _state.value = _state.value.copy(
                    loaded = true,
                    modelPath = modelPath,
                    backend = config.backend,
                    config = tuned
                )
                Log.i("InferenceEngine", "loaded ${modelPath.substringAfterLast('/')} via ${config.backend}")
                Unit
            }.onFailure {
                Log.e("InferenceEngine", "load failed", it)
                _state.value = _state.value.copy(loaded = false, error = it.message)
            }
        }
    }

    suspend fun completion(
        prompt: String,
        onToken: (String) -> Unit,
        onStop: (String) -> Unit
    ) {
        val backend = currentBackend ?: run {
            onStop("no_model"); return
        }
        // 推理前再检查一次热档位，必要时降级
        thermalGovernor.beforeCompletion(backend)
        backend.completion(prompt, onToken, onStop)
    }

    fun interrupt() {
        currentBackend?.interrupt()
    }

    suspend fun unload() = mutex.withLock {
        currentBackend?.close()
        currentBackend = null
        currentModelPath = null
        memoryOptimizer.releaseAll()
        _state.value = EngineState()
    }

    /** 启动后 1s 一次刷新 stats（UI 用） */
    init {
        scope.launch {
            while (true) {
                val b = currentBackend
                if (b != null && b.isLoaded()) {
                    val s = b.stats()
                    _stats.value = s.copy(thermalPercent = thermalMonitor.thermalPercent())
                }
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    private fun createBackend(type: BackendType): Backend = when (type) {
        BackendType.CPU    -> CpuBackend(nativeBridge, memoryOptimizer, thermalGovernor, cpuInfo)
        BackendType.VULKAN -> VulkanBackend(nativeBridge, memoryOptimizer, thermalGovernor, cpuInfo)
        BackendType.NPU    -> NpuBackend(nativeBridge, memoryOptimizer, thermalGovernor, cpuInfo)
        BackendType.OPENCL -> OpenClBackend(nativeBridge, memoryOptimizer, thermalGovernor, cpuInfo)
    }
}

data class EngineState(
    val loaded: Boolean = false,
    val modelPath: String? = null,
    val backend: BackendType = BackendType.CPU,
    val config: InferenceConfig = InferenceConfig(),
    val error: String? = null
)
