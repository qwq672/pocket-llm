#!/usr/bin/env bash
# ============================================================================
# PocketLLM 一键编译脚本 —— Linux arm64 (Apple Silicon Linux / 树莓派 4/5 / Ampere 等)
#
# 用法：
#   ./build-linux-arm64.sh
#
# 注意：
#   - 本脚本编译的是 Android arm64-v8a APK（手机用），不是 Linux arm64 二进制
#   - 只是 host（你的电脑）是 arm64 而已
#   - 编译目标 ABI 由 app/build.gradle.kts 的 abiFilters 决定
# ============================================================================

set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; BLUE='\033[0;34m'; NC='\033[0m'
log()  { echo -e "${GREEN}[$(date +%H:%M:%S)]${NC} $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
err()  { echo -e "${RED}[ERR ]${NC} $*" >&2; }
step() { echo -e "\n${BLUE}==== $* ====${NC}"; }

# 确认架构
ARCH=$(uname -m)
if [ "$ARCH" != "aarch64" ] && [ "$ARCH" != "arm64" ]; then
    warn "当前架构是 $ARCH，不是 aarch64/arm64"
    warn "如果你是在 x86_64 上模拟 arm64（如 qemu），可继续；否则请用 build-linux-x86_64.sh"
    read -p "继续? [y/N] " CONFIRM
    [ "$CONFIRM" = "y" ] || exit 1
fi

echo -e "${BLUE}╔════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║  PocketLLM Build — Linux arm64            ║${NC}"
echo -e "${BLUE}╚════════════════════════════════════════════╝${NC}"
log "Host arch: $ARCH"

# ---------- 1. 检查工具 ----------
step "1/7 检查基础工具"
MISSING=()
for cmd in git java javac curl unzip; do
    command -v $cmd >/dev/null 2>&1 && log "  ✓ $cmd" || { MISSING+=("$cmd"); warn "  ✗ $cmd"; }
done
if [ ${#MISSING[@]} -gt 0 ]; then
    err "缺少: ${MISSING[*]}"
    err "Debian/Ubuntu (含树莓派 OS): sudo apt update && sudo apt install -y git curl unzip openjdk-17-jdk"
    err "Fedora:                       sudo dnf install -y git curl unzip java-17-openjdk-devel"
    err "Arch Linux ARM:               sudo pacman -S git curl unzip jdk17-openjdk"
    exit 1
fi

# ---------- 2. 检查 Android SDK ----------
step "2/7 检查 Android SDK"
ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -z "$ANDROID_HOME" ] && for p in "$HOME/Android/Sdk" "$HOME/.android-sdk" "/opt/android-sdk"; do
    [ -d "$p" ] && ANDROID_HOME="$p" && break
done

if [ -z "$ANDROID_HOME" ] || [ ! -d "$ANDROID_HOME" ]; then
    err "未找到 Android SDK"
    err ""
    err "arm64 Linux 上装 Android SDK 步骤："
    err "  1) 下载 cmdline-tools（注意选 Linux 版，arm64 兼容 x86_64 JVM）："
    err "     mkdir -p ~/Android/Sdk/cmdline-tools"
    err "     cd ~/Android/Sdk/cmdline-tools"
    err "     curl -LO https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
    err "     unzip commandlinetools-linux-*_latest.zip"
    err "     mv cmdline-tools latest"
    err "  2) 设置环境变量（加到 ~/.bashrc）："
    err "     export ANDROID_HOME=\$HOME/Android/Sdk"
    err "     export PATH=\$PATH:\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools"
    err "  3) source ~/.bashrc 后重跑本脚本"
    exit 1
fi
log "  ✓ ANDROID_HOME = $ANDROID_HOME"
export ANDROID_HOME

SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
[ -x "$SDKMANAGER" ] || { err "找不到 sdkmanager: $SDKMANAGER"; exit 1; }
yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true

# ---------- 3. NDK ----------
step "3/7 检查 NDK"
NDK_VERSION="26.1.10909125"
NDK_PATH="$ANDROID_HOME/ndk/$NDK_VERSION"
if [ ! -d "$NDK_PATH" ]; then
    warn "NDK $NDK_VERSION 未安装，自动安装中（约 600MB）..."
    yes | "$SDKMANAGER" "ndk;$NDK_VERSION"
else
    log "  ✓ NDK $NDK_VERSION"
fi

# ---------- 4. CMake ----------
step "4/7 检查 CMake"
CMAKE_VERSION="3.22.1"
[ -d "$ANDROID_HOME/cmake/$CMAKE_VERSION" ] || {
    warn "CMake $CMAKE_VERSION 未安装，自动安装中..."
    yes | "$SDKMANAGER" "cmake;$CMAKE_VERSION"
}
log "  ✓ CMake $CMAKE_VERSION"

# ---------- 5. llama.cpp ----------
step "5/7 拉取 llama.cpp"
CPP_DIR="$PROJECT_DIR/app/src/main/cpp"
if [ -d "$CPP_DIR/llama.cpp/.git" ]; then
    log "  llama.cpp 已存在"
else
    log "  git clone llama.cpp（depth=1）..."
    git clone --depth=1 https://github.com/ggerganov/llama.cpp "$CPP_DIR/llama.cpp"
fi

# ---------- 6. local.properties ----------
step "6/7 生成 local.properties"
cat > "$PROJECT_DIR/local.properties" <<EOF
sdk.dir=$ANDROID_HOME
EOF
log "  ✓"

# ---------- 7. 编译 ----------
step "7/7 编译 APK (Release)"
log "  注意：arm64 host 上编译 Android arm64 APK，交叉编译开销较小"
log "  跑 ./gradlew :app:assembleRelease --no-daemon"
./gradlew :app:assembleRelease --no-daemon -Dorg.gradle.jvmargs="-Xmx2g" || {
    err "编译失败。arm64 host 常见问题："
    err "  1. 树莓派 4GB 以下内存：减少 gradle.properties 的 -Xmx 到 1g"
    err "  2. llama.cpp CMake 子构建慢：可以先用 -P预编译 llama 静态库"
    err "  3. JDK 必须是 arm64 版本（多数发行版默认就是）"
    exit 1
}

# 输出
APK="$PROJECT_DIR/app/build/outputs/apk/release/app-release.apk"
OUT_DIR="$PROJECT_DIR/build-output"
mkdir -p "$OUT_DIR"
DATE=$(date +%Y%m%d-%H%M)
OUT_APK="$OUT_DIR/PocketLLM-linux-arm64-$DATE.apk"
cp "$APK" "$OUT_APK"
echo ""
echo -e "${GREEN}╔════════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║  ✓ 编译成功                                ║${NC}"
echo -e "${GREEN}╚════════════════════════════════════════════╝${NC}"
log "APK: $OUT_APK"
log "大小: $(du -h "$OUT_APK" | cut -f1)"
