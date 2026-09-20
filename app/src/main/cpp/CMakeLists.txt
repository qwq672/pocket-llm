# ============================================================================
# PocketLLM Native Build
#
# 集成：
#   - llama.cpp  (CPU + 部分 GPU)
#   - ggml Vulkan backend
#   - ggml OpenCL backend
#   - ggml-hexagen (NPU backend, 高通 QNN HTP / 联发科 APU)
#
# 体积优化：
#   - 只编译 arm64-v8a
#   - 关闭 RTTI / 异常
#   - 隐藏符号
#   - 静态链接 ggml/llama.cpp，最终只产一个 libpocketllm.so
# ============================================================================

cmake_minimum_required(VERSION 3.22.1)
project(pocketllm LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

# ----- 选项 -----
# 默认开启 Vulkan 和 OpenCL（Android 7+ 几乎所有手机都支持 Vulkan，OpenCL 在 Mali/Adreno GPU 上常见）。
# CI 不需要传任何参数就能编出支持 GPU 后端的 APK。
# NPU 仍然 OFF（需要私有高通 QNN SDK）。
# 本地想强制关掉 GPU 后端时在 gradle 命令加：
#   -DPOCKET_VULKAN=OFF -DPOCKET_OPENCL=OFF
option(POCKET_VULKAN  "Enable Vulkan backend"  ON)
option(POCKET_OPENCL  "Enable OpenCL backend"  ON)
option(POCKET_NPU     "Enable NPU (HexaGen) backend" OFF)

# ----- 第三方源码目录 -----
# llama.cpp 源码（含 ggml + ggml-hexagon）：放到 app/src/main/cpp/llama.cpp
# 用户提到的 "ggml-hexagen" 实际就是 llama.cpp 内置的 ggml-hexagon 后端（高通 QNN HTP）。
# 不需要单独 clone。
set(LLAMA_DIR    ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp)

# ----- llama.cpp 子构建 -----
# llama.cpp 已自带 ggml-hexagon（高通 QNN HTP）、ggml-opencl、ggml-vulkan 等所有后端。
# 我们直接通过 llama.cpp 的 CMake option 启用对应后端，不需要单独 add_subdirectory。
if(EXISTS ${LLAMA_DIR}/CMakeLists.txt)
    set(GGML_VULKAN   ${POCKET_VULKAN} CACHE BOOL "" FORCE)
    set(GGML_OPENCL   ${POCKET_OPENCL} CACHE BOOL "" FORCE)
    set(GGML_HEXAGON  ${POCKET_NPU}    CACHE BOOL "" FORCE)
    set(GGML_OPENBLAS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
    add_subdirectory(${LLAMA_DIR} llama_build EXCLUDE_FROM_ALL)
else()
    message(FATAL_ERROR "llama.cpp not found at ${LLAMA_DIR}.
    请先执行: git clone --depth=1 https://github.com/ggerganov/llama.cpp ${LLAMA_DIR}")
endif()

# ===== PocketLLM 自己的源 =====
add_library(pocketllm SHARED
    native_bridge.cpp
    bridge.cpp
    memory_pool.cpp
    thermals.cpp
)

target_include_directories(pocketllm PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${LLAMA_DIR}/include
    ${LLAMA_DIR}/ggml/include
)

# 链接
if(EXISTS ${LLAMA_DIR}/CMakeLists.txt)
    target_link_libraries(pocketllm PRIVATE llama ggml)
endif()

if(POCKET_NPU)
    # ggml-hexagon 已在 llama.cpp 子构建里编出（GGML_HEXAGON=ON）
    # 这里只声明 POCKET_NPU 宏给 native_bridge.cpp 用
    target_compile_definitions(pocketllm PRIVATE POCKET_NPU=1)
endif()

# Vulkan 系统库
if(POCKET_VULKAN)
    find_library(VULKAN_LIB vulkan REQUIRED)
    target_link_libraries(pocketllm PRIVATE ${VULKAN_LIB})
    target_compile_definitions(pocketllm PRIVATE POCKET_VULKAN=1)
endif()

# OpenCL 系统库
if(POCKET_OPENCL)
    find_library(OPENCL_LIB OpenCL)
    if(OPENCL_LIB)
        target_link_libraries(pocketllm PRIVATE ${OPENCL_LIB})
        target_compile_definitions(pocketllm PRIVATE POCKET_OPENCL=1)
    endif()
endif()

# Android 系统库
find_library(LOG_LIB log REQUIRED)
find_library(ANDROID_LIB android REQUIRED)
target_link_libraries(pocketllm PRIVATE ${LOG_LIB} ${ANDROID_LIB})

# 隐藏符号 + 优化
target_compile_options(pocketllm PRIVATE
    -Ofast -fvisibility=hidden -fvisibility-inlines-hidden
    -ffunction-sections -fdata-sections
)
target_link_options(pocketllm PRIVATE
    -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-s
)
