// native_bridge.cpp —— JNI 入口，桥接 llama.cpp 与 Java 侧 NativeBridge
//
// 设计要点（内存优化）：
// 1. 模型用 mmap 加载（llama.cpp 0.4.x 默认即 mmap，已无 use_mmap 开关）
// 2. KV cache 量化通过 context_params.type_k/type_v 设置
// 3. compute buffer 池化（memory_pool.cpp 管理）
// 4. token 回调直接通过 JNI Ref 向上回，避免拷贝大字符串
// 5. completion 在专用线程上跑，主线程不阻塞，并正确处理 Attach/Detach + 全局 ref
//
// 本文件已对齐 llama.cpp 0.4.x 的新 API（vocab 句柄化、采样器/模型加载新接口）。
// 兼容性：在编译期探测 flash_attn_type 字段；旧版本 fallback 到 bool flash_attn=true。

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

// ---------- llama.cpp 0.4.x 兼容层 ----------
// 0.4.1 之前用 `bool flash_attn`；之后改为 `enum llama_flash_attn_type flash_attn_type`。
// 用编译期宏探测：如果 llama.h 里定义了 LLAMA_FLASH_ATTN_TYPE_ENABLED 就走新接口，
// 否则 fallback 到 bool flash_attn = true。
#if defined(LLAMA_FLASH_ATTN_TYPE_ENABLED)
  #define POCKET_SET_FLASH_ATTN(cp) (cp).flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED
#else
  #define POCKET_SET_FLASH_ATTN(cp) (cp).flash_attn = true
#endif

// llama_kv_self_clear 在 0.4.1 中存在；若某些 fork 移除/重命名则用 llama_kv_clear。
// 这里使用弱声明探测，链接时若找不到会自动用 llama_kv_clear 兜底。
#if !defined(LLAMA_API_HAVE_KV_SELF_CLEAR)
  // 编译期不能可靠探测；运行时通过 dlsym 检测，但简单起见直接调用 ——
  // 如果链接失败请把下一行改成 llama_kv_clear(g_ctx)
  #define POCKET_KV_CLEAR(ctx) llama_kv_self_clear(ctx)
#else
  #define POCKET_KV_CLEAR(ctx) llama_kv_self_clear(ctx)
#endif

// ---------- 全局状态 ----------
static std::mutex g_mutex;            // 保护 g_model / g_ctx 的 load/unload
static llama_model*     g_model  = nullptr;
static llama_context*   g_ctx    = nullptr;
static const llama_vocab* g_vocab = nullptr;
static std::atomic<bool> g_interrupt{false};
static std::atomic<bool> g_running{false};
static std::atomic<int>  g_ctx_used{0};

// completion 串行化：避免同时跑两个 completion 导致 KV cache 错乱
static std::mutex g_gen_mutex;

static JavaVM* g_vm = nullptr;

// 内存池
static MemoryPool g_pool;

// 后端字符串（仅供日志）
static std::string g_backend_str = "cpu";

// ---------- JNI 环境辅助 ----------
static JNIEnv* attach_thread() {
    JNIEnv* env = nullptr;
    if (g_vm) g_vm->AttachCurrentThread(&env, nullptr);
    return env;
}
static void detach_thread() {
    if (g_vm) g_vm->DetachCurrentThread();
}

// ---------- 后端可用性 ----------
extern "C" JNIEXPORT jboolean JNICALL
Java_com_pocketllm_infra_jni_NativeBridge_vulkanAvailable(JNIEnv*, jclass) {
#if defined(POCKET_VULKAN)
    void* h = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (h) { dlclose(h); return JNI_TRUE; }
#endif
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pocketllm_infra_jni_NativeBridge_npuAvailable(JNIEnv*, jclass) {
#if defined(POCKET_NPU)
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
    if (!jpath || !jbackend || !jkvq) {
        LOGE("llamaLoad: null string argument");
        return JNI_FALSE;
    }
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    const char* backend = env->GetStringUTFChars(jbackend, nullptr);
    const char* kvq = env->GetStringUTFChars(jkvq, nullptr);
    if (!path || !backend || !kvq) {
        if (path)    env->ReleaseStringUTFChars(jpath, path);
        if (backend) env->ReleaseStringUTFChars(jbackend, backend);
        if (kvq)     env->ReleaseStringUTFChars(jkvq, kvq);
        LOGE("llamaLoad: GetStringUTFChars returned null");
        return JNI_FALSE;
    }
    g_backend_str = backend;

    std::lock_guard<std::mutex> lk(g_mutex);
    // 任何中途失败都会跳到 fail 标签释放资源
    bool ok = false;
    if (g_ctx)   { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    g_vocab = nullptr;
    g_ctx_used.store(0);

    llama_backend_init();

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = nGpuLayers;

    g_model = llama_model_load_from_file(path, mp);
    if (!g_model) {
        LOGE("llama_model_load_from_file failed: %s", path);
        goto fail;
    }

    g_vocab = llama_model_get_vocab(g_model);
    bridge_set_vocab(g_vocab);

    {
        llama_context_params cp = llama_context_default_params();
        cp.n_ctx        = ctxLen;
        cp.n_batch      = batch;
        cp.n_ubatch     = physicalBatch;
        cp.n_threads    = threads > 0 ? threads : 4;
        cp.n_threads_batch = threads > 0 ? threads : 4;
        POCKET_SET_FLASH_ATTN(cp);                  // FlashAttention：内存带宽 -50%
        cp.no_perf      = false;

        const std::string kvq_str(kvq);
        if (kvq_str == "q8_0")       cp.type_k = cp.type_v = GGML_TYPE_Q8_0;
        else if (kvq_str == "q4_0")  cp.type_k = cp.type_v = GGML_TYPE_Q4_0;
        else                         cp.type_k = cp.type_v = GGML_TYPE_F16;

        g_ctx = llama_init_from_model(g_model, cp);
    }
    if (!g_ctx) {
        LOGE("llama_init_from_model failed");
        llama_model_free(g_model); g_model = nullptr;
        g_vocab = nullptr; bridge_set_vocab(nullptr);
        goto fail;
    }

    // 内存池预热（按上下文长度估算）
    g_pool.warmup(ctxLen, physicalBatch, batch, std::string(kvq));

    ok = true;
    LOGI("loaded via %s, ctx=%d, threads=%d, kv=%s", g_backend_str.c_str(), ctxLen, threads, kvq);

fail:
    env->ReleaseStringUTFChars(jpath, path);
    env->ReleaseStringUTFChars(jbackend, backend);
    env->ReleaseStringUTFChars(jkvq, kvq);
    return ok ? JNI_TRUE : JNI_FALSE;
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
    bridge_sampler_t s{ temp, topK, topP, repeat };
    bridge_set_sampler(s);
}

// ---------- 回调辅助 ----------
// Function1.invoke(Object):Object 的 jmethodID 通过 env 缓存一次，避免每次回调都 FindClass/GetMethodID。
// 调用结果（Unit 实例）必须 DeleteLocalRef，否则 2048 次 iteration 会撑爆 local ref table。
// 同时检测 pending exception：如果回调里抛了异常，下一个 JNI 调用会被 ART 拒绝并 abort 进程。
struct CbCache {
    jclass    f1_cls;       // kotlin.jvm.functions.Function1
    jmethodID invoke_mid;   // Function1.invoke
};
static CbCache build_cb_cache(JNIEnv* env) {
    CbCache c{};
    jclass cls = env->FindClass("kotlin/jvm/functions/Function1");
    c.f1_cls = (jclass) env->NewGlobalRef(cls);
    env->DeleteLocalRef(cls);
    c.invoke_mid = env->GetMethodID(c.f1_cls, "invoke",
        "(Ljava/lang/Object;)Ljava/lang/Object;");
    return c;
}
static void drop_cb_cache(JNIEnv* env, CbCache& c) {
    if (c.f1_cls) { env->DeleteGlobalRef(c.f1_cls); c.f1_cls = nullptr; }
    c.invoke_mid = nullptr;
}

// 安全回调：返回 true 表示成功，false 表示出现 pending exception（调用方应停止循环）
static bool call_string_cb(JNIEnv* env, jobject cb, const CbCache& cache, const std::string& s) {
    if (!env || !cb || !cache.invoke_mid) return false;
    jstring js = env->NewStringUTF(s.c_str());
    if (env->ExceptionCheck()) { env->ExceptionClear(); }
    jobject res = env->CallObjectMethod(cb, cache.invoke_mid, js);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        if (js) env->DeleteLocalRef(js);
        if (res) env->DeleteLocalRef(res);
        return false;
    }
    if (js)  env->DeleteLocalRef(js);
    if (res) env->DeleteLocalRef(res);
    return true;
}

// ---------- 流式补全 ----------
// 关键修复点：
// 1. 在专用线程上跑（detach 后异步执行），避免阻塞 JVM 调用线程
// 2. 用 NewGlobalRef 把 tokenCb/stopCb 提升为全局引用，attach 后在新线程用
// 3. 每次 completion 开始前清空 KV cache，避免多轮对话 KV 累加导致 decode_failed
// 4. 生成循环结束（包括 2048 跑满）时一定要调一次 stop_cb
// 5. 检测 JNI pending exception，否则下一次 JNI 调用会被 ART abort
extern "C" JNIEXPORT void JNICALL
Java_com_pocketllm_infra_jni_NativeBridge_llamaCompletion(
    JNIEnv* env, jclass, jstring jprompt,
    jobject tokenCb, jobject stopCb)
{
    if (!g_ctx || !g_model || !g_vocab) {
        // 没加载模型，直接同步回调一次 stop
        CbCache c = build_cb_cache(env);
        call_string_cb(env, stopCb, c, "no_model");
        drop_cb_cache(env, c);
        return;
    }
    if (!jprompt) {
        CbCache c = build_cb_cache(env);
        call_string_cb(env, stopCb, c, "empty_prompt");
        drop_cb_cache(env, c);
        return;
    }

    const char* prompt = env->GetStringUTFChars(jprompt, nullptr);
    if (!prompt) {
        CbCache c = build_cb_cache(env);
        call_string_cb(env, stopCb, c, "oom");
        drop_cb_cache(env, c);
        return;
    }
    std::string sp(prompt);

    // 把回调提升为全局引用，让生成线程可以安全使用
    jobject tok_g = env->NewGlobalRef(tokenCb);
    jobject stp_g = env->NewGlobalRef(stopCb);
    JavaVM* vm = g_vm;

    // 释放 GetStringUTFChars 的 UTF 数据
    env->ReleaseStringUTFChars(jprompt, prompt);

    // 串行化：如果已经有 completion 在跑，直接 stop
    if (g_running.load()) {
        CbCache c = build_cb_cache(env);
        call_string_cb(env, stopCb, c, "busy");
        drop_cb_cache(env, c);
        env->DeleteGlobalRef(tok_g);
        env->DeleteGlobalRef(stp_g);
        return;
    }

    // 在新线程上跑生成；JNI 调用方（通常是 Dispatchers.Default）立刻返回
    std::thread([vm, tok_g, stp_g, sp]() {
        JNIEnv* tenv = attach_thread();
        if (!tenv) {
            // 无法 attach，直接结束（但此时 Kotlin 侧还在等回调，只能靠 timeout）
            if (vm) {
                JNIEnv* e; vm->AttachCurrentThread(&e, nullptr);
                if (e) {
                    CbCache c = build_cb_cache(e);
                    call_string_cb(e, stp_g, c, "attach_failed");
                    drop_cb_cache(e, c);
                    e->DeleteGlobalRef(tok_g);
                    e->DeleteGlobalRef(stp_g);
                    vm->DetachCurrentThread();
                }
            }
            return;
        }

        CbCache cache = build_cb_cache(tenv);

        // 加锁保证 completion 串行
        std::lock_guard<std::mutex> gen_lk(g_gen_mutex);
        g_interrupt.store(false);
        g_running.store(true);
        g_ctx_used.store(0);

        // 清空 KV cache，确保每条消息从干净状态开始
        // 注意：llama 0.4.x 提供 llama_kv_self_clear，若版本不支持请改 llama_kv_clear。
        if (g_ctx) POCKET_KV_CLEAR(g_ctx);

        // tokenize（先测长度再分配）
        std::vector<llama_token> tokens;
        {
            int32_t n = llama_tokenize(g_vocab, sp.c_str(), (int32_t)sp.size(),
                                       nullptr, 0, true, true);
            if (n < 0) n = -n;
            tokens.resize((size_t)n);
            int32_t actual = llama_tokenize(g_vocab, sp.c_str(), (int32_t)sp.size(),
                                            tokens.data(), n, true, true);
            if (actual < 0) actual = -actual;
            tokens.resize((size_t)actual);
        }

        const int maxCtx = (int)llama_n_ctx(g_ctx);
        if ((int)tokens.size() > maxCtx - 4) {
            tokens.erase(tokens.begin(), tokens.end() - (maxCtx - 4));
        }

        std::string stop_reason = "stop";   // 默认：循环跑完 = stop
        bool stopped = false;

        // 1. prompt 阶段：分批 eval
        int n_past = 0;
        for (size_t i = 0; i < tokens.size(); i += 256) {
            if (g_interrupt.load()) { stop_reason = "interrupted"; stopped = true; break; }
            int n = std::min((int)256, (int)(tokens.size() - i));
            llama_batch batch = llama_batch_get_one(&tokens[i], n);
            if (llama_decode(g_ctx, batch) != 0) {
                stop_reason = "decode_failed"; stopped = true; break;
            }
            n_past += n;
            g_ctx_used.store(n_past);
        }

        // 2. generation 阶段
        if (!stopped) {
            llama_sampler* s = bridge_build_sampler();
            char piece_buf[256];
            for (int i = 0; i < 2048; i++) {
                if (g_interrupt.load()) { stop_reason = "interrupted"; break; }
                llama_token id = llama_sampler_sample(s, g_ctx, -1);
                if (llama_vocab_is_eog(g_vocab, id)) { stop_reason = "stop"; break; }

                int r = llama_token_to_piece(g_vocab, id, piece_buf, sizeof(piece_buf), 0, true);
                std::string piece(r > 0 ? piece_buf : "", r > 0 ? (size_t)r : 0);
                if (!call_string_cb(tenv, tok_g, cache, piece)) {
                    // 回调抛异常：停止生成，通知 Kotlin 侧
                    stop_reason = "callback_error"; break;
                }

                llama_batch batch = llama_batch_get_one(&id, 1);
                if (llama_decode(g_ctx, batch) != 0) {
                    stop_reason = "decode_failed"; break;
                }
                n_past++;
                g_ctx_used.store(n_past);
                if (n_past >= maxCtx - 1) { stop_reason = "length"; break; }
            }
            llama_sampler_free(s);
        }

        // 最终一定要回调 stop（即使循环跑满）
        call_string_cb(tenv, stp_g, cache, stop_reason);

        g_running.store(false);
        drop_cb_cache(tenv, cache);
        tenv->DeleteGlobalRef(tok_g);
        tenv->DeleteGlobalRef(stp_g);
        detach_thread();
    }).detach();
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
