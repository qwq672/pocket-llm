package com.pocketllm.infra.jni

import java.nio.ByteBuffer

/**
 * JNI 桥。所有 native 调用都集中在这里，便于管理符号。
 *
 * 对应 C++ 侧 native_bridge.cpp。每个方法都映射到一个 extern "C" 函数。
 *
 * 命名约定：
 * - llama*     : llama.cpp 推理相关
 * - memoryPool* : 内存池相关
 * - vulkanAvailable / npuAvailable / openclAvailable : 后端探测
 */
class NativeBridge {

    init {
        System.loadLibrary("pocketllm")
    }

    // -------- 后端可用性 --------
    external fun vulkanAvailable(): Boolean
    external fun npuAvailable(): Boolean
    external fun openclAvailable(): Boolean
    external fun nativeVersion(): String

    // -------- 模型加载 --------
    external fun llamaLoad(
        modelPath: String,
        backend: String,
        nGpuLayers: Int,
        threads: Int,
        physicalBatch: Int,
        batch: Int,
        contextLength: Int,
        kvQuant: String
    ): Boolean

    external fun llamaUnload()
    external fun llamaSetSampler(
        temperature: Float,
        topK: Int,
        topP: Float,
        repeatPenalty: Float
    )

    /**
     * 流式补全。token 通过 [tokenCallback] 回调到 JVM（已是字符串），
     * 完成时调用 [stopCallback] 传入 stop reason。
     */
    external fun llamaCompletion(
        prompt: String,
        tokenCallback: (String) -> Unit,
        stopCallback: (String) -> Unit
    )

    external fun llamaInterrupt()

    // -------- 状态查询 --------
    external fun llamaTokensPerSecond(): Float
    external fun llamaContextUsed(): Int
    external fun llamaContextMax(): Int

    // -------- 内存池 --------
    external fun memoryPoolWarmup(kvBytes: Long, computeBytes: Long)
    external fun memoryPoolRelease()
    external fun memoryPoolShrinkIdle(idleMs: Long, keepTokens: Int)
    external fun memoryPoolPeakMb(): Long

    /**
     * 借一片 DirectByteBuffer（池化复用）。
     * 调用方使用完必须 [returnDirectBuffer] 归还。
     */
    external fun borrowDirectBuffer(byteSize: Int): ByteBuffer
    external fun returnDirectBuffer(buf: ByteBuffer)

    // -------- 热感知 --------
    external fun thermalPercent(): Int
}
