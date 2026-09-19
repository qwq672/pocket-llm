# PocketLLM — Android 离线 GGUF 推理 App

> Kotlin + Jetpack Compose + llama.cpp + ggml-hexagen，支持 CPU / Vulkan / NPU / OpenCL 四后端，
> 内置内存池化与热感知软降档（不靠「提示用户降温」），中英双语，Material You 动态取色。

## 功能清单

| 分类 | 功能 | 状态 |
|------|------|------|
| 后端 | CPU（兜底，自动线程数） | ✅ |
| 后端 | Vulkan（GPU 通用计算） | ✅ |
| 后端 | NPU（ggml-hexagen） | ✅ |
| 后端 | OpenCL（fallback） | ✅ |
| 设置 | 后端选择 | ✅ |
| 设置 | GPU/NPU 层数（仅非 CPU） | ✅ |
| 设置 | CPU 线程数（0=自动按大核数） | ✅ |
| 设置 | 防回退开关（antiRollback） | ✅ |
| 设置 | 物理批处理 + 逻辑批处理 | ✅ |
| 设置 | KV 量化（f16 / q8_0 / q4_0） | ✅ |
| 设置 | 上下文长度 | ✅ |
| 设置 | 采样参数（temperature / topK / topP / repeat_penalty） | ✅ |
| 设置 | 启动自动加载上次模型 | ✅ |
| 模型 | 导入本地 .gguf | ✅ |
| 模型 | 列表 + 激活 | ✅ |
| 下载 | HF / HF-Mirror / ModelScope 三源切换 | ✅ |
| 下载 | 断点续传 + 进度 | ✅ |
| 优化 | mmap 加载（不抖 page cache） | ✅ |
| 优化 | KV cache 池化复用 | ✅ |
| 优化 | DirectByteBuffer 池 | ✅ |
| 优化 | FlashAttention | ✅ |
| 优化 | 热感知软降档（不打断对话） | ✅ |

## 项目结构

```
app/src/main/
├── kotlin/com/pocketllm/
│   ├── PocketLLMApp.kt            # Application 入口
│   ├── MainActivity.kt
│   ├── di/AppContainer.kt         # 手写 DI
│   ├── domain/
│   │   ├── inference/
│   │   │   ├── Backend.kt          # 后端接口 + 4 实现
│   │   │   ├── InferenceConfig.kt  # 全部参数
│   │   │   ├── InferenceEngine.kt  # 统一调度
│   │   │   ├── MemoryOptimizer.kt  # 内存优化核心 ⭐
│   │   │   ├── ThermalGovernor.kt  # 热感知软降档 ⭐
│   │   │   ├── CpuBackend.kt
│   │   │   ├── VulkanBackend.kt
│   │   │   ├── NpuBackend.kt
│   │   │   └── OpenClBackend.kt
│   │   └── download/              # 三源适配
│   ├── data/                       # Room + DataStore
│   ├── infra/jni/NativeBridge.kt  # JNI 桥
│   ├── ui/                         # Compose 全套
│   └── util/                       # CpuInfo / ThermalMonitor / FileUtils
└── cpp/
    ├── CMakeLists.txt              # 编译 llama.cpp + hexagen
    ├── native_bridge.cpp           # JNI 实现
    ├── memory_pool.{h,cpp}         # 池化内存
    └── bridge.{h,cpp}              # llama 版本抽象
```

## 构建

### 准备
1. 安装 [Android Studio Ladybug+](https://developer.android.com/studio)
2. SDK Platform 34 + NDK 26.1.10909125 + CMake 3.22.1
3. 把 `local.properties.template` 复制为 `local.properties`，填 SDK 路径

### 拉取 native 依赖

```bash
cd app/src/main/cpp
git clone --depth 1 https://github.com/ggerganov/llama.cpp
git clone --depth 1 https://github.com/Anvell/ggml-hexagen   # 或镜像
```

### 编译

用 Android Studio 打开根目录 → Sync → Run。
或命令行：

```bash
./gradlew :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

## 内存优化原理（为什么这个 App 不那么烫）

手机端跑大模型，**真凶不是算力而是 DDR 带宽与 malloc 抖动**。本 App 的做法：

1. **mmap 加载权重**：不把整份权重塞进 page cache，让 OS 按需 page-in，避免反复 evict。
2. **KV cache 池化**：load 时一次性按 `max_ctx` 预分配，generation 阶段不再 realloc。
3. **KV 量化**：默认 `q8_0`，把 KV 带宽砍掉一半；热到 85%+ 自动降 `q4_0`。
4. **FlashAttention**：llama.cpp 的 `flash_attn=true`，attention 阶段内存带宽减 50%。
5. **DirectByteBuffer 池**：JNI 传 token id 不再 `malloc/free`，池化复用。
6. **热感知软降档**：温度升高自动降 `batch` / `threads` / KV 量化级别，而不是中断推理让用户等降温。
   `antiRollback=true` 时本机制禁用，性能优先（用户自担风险）。
7. **空闲 shrink**：N 秒无推理，KV cache 自动缩到最近一段，下次首 token 稍慢但内存恒稳。

## 三下载源对照

| 源 | id | 域名 | 备注 |
|----|----|------|------|
| HF-Mirror（默认） | `hf_mirror` | hf-mirror.com | 国内推荐 |
| HuggingFace | `hf` | huggingface.co | 海外 |
| ModelScope | `modelscope` | modelscope.cn | 国内最快，API 适配 |

## 已知限制

- 实际编译需要本机有 NDK + CMake，且需要把 `llama.cpp` 与 `ggml-hexagen` 源码放到 `app/src/main/cpp/`。
- `ggml-hexagen` 项目本身仍处于快速演进期，不同 NPU（高通 HTP / 联发科 APU）需要对应预编译库。
- App 体积取决于编译进哪些后端，只打 CPU + Vulkan 大约 8MB APK；含 NPU 库会到 25MB+。

## License
MIT
