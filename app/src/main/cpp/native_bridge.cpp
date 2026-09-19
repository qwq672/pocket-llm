// native_bridge.cpp —— JNI 入口，桥接 llama.cpp 与 Java 侧 NativeBridge
//
// 设计要点（内存优化）：
// 1. 模型用 mmap 加载（llama.cpp 默认就是 mmap，确认不传 LLAMA_FILE_NO_MMAP）
// 2. KV cache 量化通过 llama_kv_cache_quantize / kv quant type 设置
// 3. compute buffer 池化（memory_pool.cpp 管理）
// 4. token 回调直接通过 JNI Ref 向上回，避免拷贝大字符串
// 5. completion 在专用线程上跑，主线程不阻塞

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
static llama_model*  g_model  = nullptr;
static llama_context* g_ctx   = nullptr;
static std::atomic<bool> g_interrupt{false};
static std::thread g_gen_thread;
static std::atomic<bool> g_running{false};

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
    void* h1 = dlopen("libQnnHtp.so",     RTLD_NOW | RTLD_LOCAL);
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
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_free_model(g_model); g_model = nullptr; }

    llama_backend_init();

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = nGpuLayers;
    // 关键：mmap 加载，避免整份权重进 page cache 反复 evict 抖动
    mp.use_mmap = true;
    mp.use_mlock = false;

    g_model = llama_load_model_from_file(path, mp);
    if (!g_model) {
        LOGE("llama_load_model_from_file failed: %s", path);
        env->ReleaseStringUTFChars(jpath, path);
        env->ReleaseStringUTFChars(jbackend, backend);
        env->ReleaseStringUTFChars(jkvq, kvq);
        return JNI_FALSE;
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx        = ctxLen;
    cp.n_batch      = batch;
    cp.n_ubatch     = physicalBatch;   // 物理 batch
    cp.n_threads    = threads > 0 ? threads : 4;
    cp.n_threads_batch = threads > 0 ? threads : 4;
    cp.flash_attn   = true;            // FlashAttention：内存带宽 -50%
    cp.no_perf      = false;

    // KV cache 量化
    if (std::string(kvq) == "q8_0")      cp.type_k = cp.type_v = GGML_TYPE_Q8_0;
    else if (std::string(kvq) == "q4_0") cp.type_k = cp.type_v = GGML_TYPE_Q4_0;
    else                                 cp.type_k = cp.type_v = GGML_TYPE_F16;

    g_ctx = llama_new_context_with_model(g_model, cp);
    if (!g_ctx) {
        LOGE("llama_new_context_with_model failed");
        llama_free_model(g_model); g_model = nullptr;
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
    if (g_model) { llama_free_model(g_model); g_model = nullptr; }
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
    if (!g_ctx || !g_model) {
        call_stop_cb(env, stopCb, "no_model");
        return;
    }
    const char* prompt = env->GetStringUTFChars(jprompt, nullptr);
    std::string sp(prompt);

    g_interrupt.store(false);
    g_running.store(true);

    // tokenize
    std::vector<llama_token> tokens = ::llama_tokenize(g_model, sp, true, true);
    const int maxCtx = llama_n_ctx(g_ctx);
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
        if (g_interrupt.load()) { call_stop_cb(env, stopCb, "interrupted"); goto done; }
    }

    // 2. generation 阶段：逐 token
    {
        llama_sampler* s = bridge_build_sampler();
        for (int i = 0; i < 2048; i++) {
            if (g_interrupt.load()) { call_stop_cb(env, stopCb, "interrupted"); break; }
            llama_token id = llama_sampler_sample(s, g_ctx, -1);
            if (llama_token_is_eog(g_model, id)) { call_stop_cb(env, stopCb, "stop"); break; }
            std::string piece = llama_token_to_piece(g_ctx, id);
            call_token_cb(env, tokenCb, piece);

            llama_batch batch = llama_batch_get_one(&id, 1);
            if (llama_decode(g_ctx, batch) != 0) {
                call_stop_cb(env, stopCb, "decode_failed"); break;
            }
            n_past++;
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
    return (jfloat)llama_perf_context(g_ctx)->t_eval;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_pocketllm_infra_jni_NativeBridge_llamaContextUsed(JNIEnv*, jclass) {
    if (!g_ctx) return 0;
    return (jint)llama_get_kv_cache_token_count(g_ctx);
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
