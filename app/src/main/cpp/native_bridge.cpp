// native_bridge.cpp —— JNI 入口，桥接 llama.cpp 与 Java 侧 NativeBridge
//
// 设计要点（内存优化）：
// 1. 模型用 mmap 加载（llama.cpp 0.4.x 默认即 mmap，已无 use_mmap 开关）
// 2. KV cache 量化通过 context_params.type_k/type_v 设置
// 3. compute buffer 池化（memory_pool.cpp 管理）
// 4. token 回调直接通过 JNI Ref 向上回，避免拷贝大字符串
// 5. completion 在专用线程上跑，主线程不阻塞
//
// 本文件已对齐 llama.cpp 0.4.1 的新 API（vocab 句柄化、采样器/模型加载新接口）。

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <string>
#include <vector>
#include <memory>
#include <mutex>
#include <atomic>
#include <condition_variable>
#include <thread>

#include "llama.h"
#include "memory_pool.h"
#include "bridge.h"

#define TAG "PocketLLM-Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ---------- 全局状态 ----------
static std::mutex g_mutex;
static llama_model*     g_model  = nullptr;
static llama_context*   g_ctx    = nullptr;
static const llama_vocab* g_vocab = nullptr;
static std::atomic<bool> g_interrupt{false};
static std::thread g_gen_thread;
static std::atomic<bool> g_running{false};
// 0.4.x 移除了 llama_get_kv_cache_token_count，这里自行维护已用 token 数
static std::atomic<int> g_ctx_used{0};

static JavaVM* g_vm = nullptr;
static jobject g_ref_obj = nullptr;   // 全局 ref，给 callback 用

// 内存池
static MemoryPool g_pool;

// 后端字符串（仅供日志）
static std::string g_backend_str = "cpu";

// ---------- 后端可用性 ----------
extern "C" JNIEXPORT jboolean JNICALL
Java_com_pocketllm_infra_jni_NativeBridge_vulkanAvailable(JNIEnv*, jclass) {
#if defined(POCKET_VULKAN)
    // 简单探测：能否 dlopen libvulkan.so
    void* h = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (h) { dlclose(h); return JNI_TRUE; }
#endif
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pocketllm_infra_jni_NativeBridge_npuAvailable(JNIEnv*, jclass) {
#if defined(POCKET_NPU)
    // 探测 QNN HTP / 联发科 APU
    void* h1 = dlopen("libQnnHtp.so",          RTLD_NOW | RTLD_LOCAL);
    void* h2 = dlopen("libhexagon_nn_skel.so", RTLD_NOW | RTLD_LOCAL);
    void* h3 = dlopen("libneuron_adapter.so",  RTLD_NOW | RTLD_LOCAL);
    if (h1) { dlclose(h1); return JNI_TRUE; }
    if (h2) { dlclose(h2); return JNI_TRUE; }
    if (h3) { dlclose(h3); return JNI_TRUE; }
#endif
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pocketllm_infra_jni_NativeBridge_openclAvailable(JNIEnv*, jclass) {
#if defined(POCKET_OPENCL)
    void* h = dlopen("libOpenCL.so", RTLD_NOW | RTLD_LOCAL);
    if (h) { dlclose(h); return JNI_TRUE; }
#endif
    return JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_pocketllm_infra_jni_NativeBridge_nativeVersion(JNIEnv* env, jclass) {
    std::string v = "llama.cpp=" + std::string(llama_print_system_info()) + " pocket=1.0.0";
    return env->NewStringUTF(v.c_str());
}

// ---------- 加载 ----------
extern "C" JNIEXPORT jboolean JNICALL
Java_com_pocketllm_infra_jni_NativeBridge_llamaLoad(
    JNIEnv* env, jclass, jstring jpath, jstring jbackend,
    jint nGpuLayers, jint threads,
    jint physicalBatch, jint batch,
    jint ctxLen, jstring jkvq)
{
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    const char* backend = env->GetStringUTFChars(jbackend, nullptr);
    const char* kvq = env->GetStringUTFChars(jkvq, nullptr);
    g_backend_str = backend;

    std::lock_guard<std::mutex> lk(g_mutex);
    if (g_ctx)   { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    g_vocab = nullptr;
    g_ctx_used.store(0);

    llama_backend_init();

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = nGpuLayers;
    // 0.4.x：mmap 为默认行为，use_mmap/use_mlock 字段已移除，无需设置

    g_model = llama_model_load_from_file(path, mp);
    if (!g_model) {
        LOGE("llama_model_load_from_file failed: %s", path);
        env->ReleaseStringUTFChars(jpath, path);
        env->ReleaseStringUTFChars(jbackend, backend);
        env->ReleaseStringUTFChars(jkvq, kvq);
        return JNI_FALSE;
    }

    g_vocab = llama_model_get_vocab(g_model);
    bridge_set_vocab(g_vocab);

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx        = ctxLen;
    cp.n_batch      = batch;
    cp.n_ubatch     = physicalBatch;   // 物理 batch
    cp.n_threads    = threads > 0 ? threads : 4;
    cp.n_threads_batch = threads > 0 ? threads : 4;
    cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;  // FlashAttention：内存带宽 -50%
    cp.no_perf      = false;

    // KV cache 量化
    if (std::string(kvq) == "q8_0")      cp.type_k = cp.type_v = GGML_TYPE_Q8_0;
    else if (std::string(kvq) == "q4_0") cp.type_k = cp.type_v = GGML_TYPE_Q4_0;
    else                                 cp.type_k = cp.type_v = GGML_TYPE_F16;

    g_ctx = llama_init_from_model(g_model, cp);
    if (!g_ctx) {
        LOGE("llama_init_from_model failed");
        llama_model_free(g_model); g_model = nullptr;
        g_vocab = nullptr; bridge_set_vocab(nullptr);
        env->ReleaseStringUTFChars(jpath, path);
        env->ReleaseStringUTFChars(jbackend, backend);
        env->ReleaseStringUTFChars(jkvq, kvq);
        return JNI_FALSE;
    }

    // 内存池预热（按上下文长度估算）
    g_pool.warmup(ctxLen, physicalBatch, batch, std::string(kvq));

    env->ReleaseStringUTFChars(jpath, path);
    env->ReleaseStringUTFChars(jbackend, backend);
    env->ReleaseStringUTFChars(jkvq, kvq);
    LOGI("loaded via %s, ctx=%d, threads=%d, kv=%s", g_backend_str.c_str(), ctxLen, threads, kvq);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_pocketllm_infra_jni_NativeBridge_llamaUnload(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lk(g_mutex);
    if (g_ctx)   { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    g_vocab = nullptr;
    bridge_set_vocab(nullptr);
    g_ctx_used.store(0);
    g_pool.releaseAll();
    LOGI("unloaded");
}

extern "C" JNIEXPORT void JNICALL
Java_com_pocketllm_infra_jni_NativeBridge_llamaSetSampler(
    JNIEnv*, jclass, jfloat temp, jint topK, jfloat topP, jfloat repeat)
{
    std::lock_guard<std::mutex> lk(g_mutex);
    // 保存到全局，completion 时使用
    bridge_sampler_t s{ temp, topK, topP, repeat };
    bridge_set_sampler(s);
}

// ---------- 回调辅助 ----------
static void call_token_cb(JNIEnv* env, jobject cb, const std::string& tok) {
    jclass cls = env->GetObjectClass(cb);
    jmethodID mid = env->GetMethodID(cls, "invoke",
        "(Ljava/lang/Object;)Ljava/lang/Object;");
    jstring js = env->NewStringUTF(tok.c_str());
    env->CallObjectMethod(cb, mid, js);
    env->DeleteLocalRef(js);
    env->DeleteLocalRef(cls);
}

static void call_stop_cb(JNIEnv* env, jobject cb, const std::string& reason) {
    jclass cls = env->GetObjectClass(cb);
    jmethodID mid = env->GetMethodID(cls, "invoke",
        "(Ljava/lang/Object;)Ljava/lang/Object;");
    jstring js = env->NewStringUTF(reason.c_str());
    env->CallObjectMethod(cb, mid, js);
    env->DeleteLocalRef(js);
    env->DeleteLocalRef(cls);
}

// ---------- 流式补全 ----------
extern "C" JNIEXPORT void JNICALL
Java_com_pocketllm_infra_jni_NativeBridge_llamaCompletion(
    JNIEnv* env, jclass, jstring jprompt,
    jobject tokenCb, jobject stopCb)
{
    if (!g_ctx || !g_model || !g_vocab) {
        call_stop_cb(env, stopCb, "no_model");
        return;
    }
    const char* prompt = env->GetStringUTFChars(jprompt, nullptr);
    std::string sp(prompt);

    g_interrupt.store(false);
    g_running.store(true);
    g_ctx_used.store(0);

    // tokenize（0.4.x：先测长度再分配，vocab 句柄化）
    std::vector<llama_token> tokens;
    {
        int32_t n = llama_tokenize(g_vocab, sp.c_str(), (int32_t)sp.size(),
                                   nullptr, 0, true, true);
        if (n < 0) n = -n; // 返回值为需要的 buffer 大小
        tokens.resize((size_t)n);
        int32_t actual = llama_tokenize(g_vocab, sp.c_str(), (int32_t)sp.size(),
                                        tokens.data(), n, true, true);
        if (actual < 0) actual = -actual;
        tokens.resize((size_t)actual);
    }

    const int maxCtx = (int)llama_n_ctx(g_ctx);
    if ((int)tokens.size() > maxCtx - 4) {
        // 截断保留最近
        tokens.erase(tokens.begin(), tokens.end() - (maxCtx - 4));
    }

    // 1. prompt 阶段：一次性 eval 整批（physical batch 限制下分批）
    int n_past = 0;
    for (size_t i = 0; i < tokens.size(); i += 256) {
        int n = std::min((int)256, (int)(tokens.size() - i));
        llama_batch batch = llama_batch_get_one(&tokens[i], n);
        if (llama_decode(g_ctx, batch) != 0) {
            call_stop_cb(env, stopCb, "decode_failed");
            goto done;
        }
        n_past += n;
        g_ctx_used.store(n_past);
        if (g_interrupt.load()) { call_stop_cb(env, stopCb, "interrupted"); goto done; }
    }

    // 2. generation 阶段：逐 token
    {
        llama_sampler* s = bridge_build_sampler();
        char piece_buf[256];
        for (int i = 0; i < 2048; i++) {
            if (g_interrupt.load()) { call_stop_cb(env, stopCb, "interrupted"); break; }
            llama_token id = llama_sampler_sample(s, g_ctx, -1);
            if (llama_vocab_is_eog(g_vocab, id)) { call_stop_cb(env, stopCb, "stop"); break; }

            int r = llama_token_to_piece(g_vocab, id, piece_buf, sizeof(piece_buf), 0, true);
            std::string piece(r > 0 ? piece_buf : "", r > 0 ? (size_t)r : 0);
            call_token_cb(env, tokenCb, piece);

            llama_batch batch = llama_batch_get_one(&id, 1);
            if (llama_decode(g_ctx, batch) != 0) {
                call_stop_cb(env, stopCb, "decode_failed"); break;
            }
            n_past++;
            g_ctx_used.store(n_past);
            if (n_past >= maxCtx - 1) { call_stop_cb(env, stopCb, "length"); break; }
        }
        llama_sampler_free(s);
    }

done:
    g_running.store(false);
    env->ReleaseStringUTFChars(jprompt, prompt);
}

extern "C" JNIEXPORT void JNICALL
Java_com_pocketllm_infra_jni_NativeBridge_llamaInterrupt(JNIEnv*, jclass) {
    g_interrupt.store(true);
}

// ---------- 状态 ----------
extern "C" JNIEXPORT jfloat JNICALL
Java_com_pocketllm_infra_jni_NativeBridge_llamaTokensPerSecond(JNIEnv*, jclass) {
    if (!g_ctx) return 0.f;
    auto pd = llama_perf_context(g_ctx);
    if (pd.t_eval_ms <= 0.0 || pd.n_eval <= 0) return 0.f;
    return (jfloat)(pd.n_eval / (pd.t_eval_ms / 1000.0));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_pocketllm_infra_jni_NativeBridge_llamaContextUsed(JNIEnv*, jclass) {
    return (jint)g_ctx_used.load();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_pocketllm_infra_jni_NativeBridge_llamaContextMax(JNIEnv*, jclass) {
    if (!g_ctx) return 0;
    return (jint)llama_n_ctx(g_ctx);
}

// ---------- 内存池 ----------
extern "C" JNIEXPORT void JNICALL
Java_com_pocketllm_infra_jni_NativeBridge_memoryPoolWarmup(JNIEnv*, jclass, jlong kv, jlong comp) {
    g_pool.warmup_bytes(kv, comp);
}

extern "C" JNIEXPORT void JNICALL
Java_com_pocketllm_infra_jni_NativeBridge_memoryPoolRelease(JNIEnv*, jclass) {
    g_pool.releaseAll();
}

extern "C" JNIEXPORT void JNICALL
Java_com_pocketllm_infra_jni_NativeBridge_memoryPoolShrinkIdle(JNIEnv*, jclass, jlong idleMs, jint keep) {
    g_pool.shrink_idle(idleMs, keep);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_pocketllm_infra_jni_NativeBridge_memoryPoolPeakMb(JNIEnv*, jclass) {
    return (jlong)g_pool.peak_mb();
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_pocketllm_infra_jni_NativeBridge_borrowDirectBuffer(JNIEnv* env, jclass, jint size) {
    return g_pool.borrow_direct(env, size);
}

extern "C" JNIEXPORT void JNICALL
Java_com_pocketllm_infra_jni_NativeBridge_returnDirectBuffer(JNIEnv* env, jclass, jobject buf) {
    g_pool.return_direct(env, buf);
}

// ---------- 热感知（sysfs 读 CPU 温度，避免来回切换 JNI） ----------
extern "C" JNIEXPORT jint JNICALL
Java_com_pocketllm_infra_jni_NativeBridge_thermalPercent(JNIEnv*, jclass) {
    return (jint)bridge_read_thermal_percent();
}

// ---------- JNI_OnLoad ----------
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}
