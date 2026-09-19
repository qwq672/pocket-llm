#!/usr/bin/env bash
# ============================================================================
# PocketLLM 一键编译脚本 —— Linux x86 (32 位老 PC / 上网本 / 部分虚拟机)
#
# 用法：./build-linux-x86.sh
#
# 重要提示：
#   - Android Gradle Plugin 8.5 + JDK 17 在 32 位 x86 上可能内存受限
#   - 推荐升级到 x86_64；如果实在只能用 x86，本脚本会尝试用 JDK 11 兼容
#   - 编译速度大约比 x86_64 慢 2-3 倍
# ============================================================================

set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; BLUE='\033[0;34m'; NC='\033[0m'
log()  { echo -e "${GREEN}[$(date +%H:%M:%S)]${NC} $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
err()  { echo -e "${RED}[ERR ]${NC} $*" >&2; }
step() { echo -e "\n${BLUE}==== $* ====${NC}"; }

ARCH=$(uname -m)
if [ "$ARCH" = "x86_64" ] || [ "$ARCH" = "amd64" ]; then
    warn "你是 x86_64 系统，建议直接用 build-linux-x86_64.sh，速度更快"
    read -p "强制使用 x86 模式继续? [y/N] " CONFIRM
    [ "$CONFIRM" = "y" ] || exit 1
fi

echo -e "${BLUE}╔════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║  PocketLLM Build — Linux x86 (32-bit)     ║${NC}"
echo -e "${BLUE}╚════════════════════════════════════════════╝${NC}"

# ---------- 1. 工具 ----------
step "1/7 检查基础工具"
MISSING=()
for cmd in git java javac curl unzip; do
    command -v $cmd >/dev/null 2>&1 && log "  ✓ $cmd" || { MISSING+=("$cmd"); warn "  ✗ $cmd"; }
done
[ ${#MISSING[@]} -gt 0 ] && {
    err "缺少: ${MISSING[*]}"
    err "32 位 Linux 安装命令（Debian/Ubuntu）："
    err "  sudo dpkg --add-architecture i386  # 启用 32 位"
    err "  sudo apt update"
    err "  sudo apt install -y git curl unzip openjdk-17-jdk:i386"
    err "如果 OpenJDK 17 没有 32 位包，改用 OpenJDK 11："
    err "  sudo apt install -y openjdk-11-jdk:i386"
    exit 1
}

# ---------- 2. Android SDK ----------
step "2/7 检查 Android SDK"
ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -z "$ANDROID_HOME" ] && for p in "$HOME/Android/Sdk" "/opt/android-sdk"; do
    [ -d "$p" ] && ANDROID_HOME="$p" && break
done
[ -z "$ANDROID_HOME" ] && {
    err "未找到 Android SDK，按 build-linux-x86_64.sh 的提示装"
    err "（32 位 Linux 用同样的 cmdline-tools 命令，SDK 本身是架构无关的 zip）"
    exit 1
}
log "  ✓ ANDROID_HOME = $ANDROID_HOME"
export ANDROID_HOME

SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
[ -x "$SDKMANAGER" ] || { err "找不到 sdkmanager"; exit 1; }
yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true

# ---------- 3. NDK ----------
step "3/7 检查 NDK"
NDK_VERSION="26.1.10909125"
[ -d "$ANDROID_HOME/ndk/$NDK_VERSION" ] || {
    warn "NDK $NDK_VERSION 未安装，自动安装中..."
    yes | "$SDKMANAGER" "ndk;$NDK_VERSION"
}
log "  ✓ NDK"

# ---------- 4. CMake ----------
step "4/7 检查 CMake"
CMAKE_VERSION="3.22.1"
[ -d "$ANDROID_HOME/cmake/$CMAKE_VERSION" ] || {
    warn "CMake 未安装，自动安装中..."
    yes | "$SDKMANAGER" "cmake;$CMAKE_VERSION"
}
log "  ✓ CMake"

# ---------- 5. llama.cpp ----------
step "5/7 拉取 llama.cpp"
CPP_DIR="$PROJECT_DIR/app/src/main/cpp"
[ -d "$CPP_DIR/llama.cpp/.git" ] || {
    log "  git clone llama.cpp..."
    git clone --depth=1 https://github.com/ggerganov/llama.cpp "$CPP_DIR/llama.cpp"
}

# ---------- 6. local.properties ----------
step "6/7 生成 local.properties"
cat > "$PROJECT_DIR/local.properties" <<EOF
sdk.dir=$ANDROID_HOME
EOF

# ---------- 7. 编译 ----------
step "7/7 编译 APK (Release)"
warn "32 位 host 编译可能遇到内存上限，已降低 Gradle 内存到 1.5g"
log "  跑 ./gradlew :app:assembleRelease --no-daemon"
./gradlew :app:assembleRelease --no-daemon -Dorg.gradle.jvmargs="-Xmx1536m" || {
    err "编译失败。32 位 Linux 常见问题："
    err "  1. JVM 内存上限：把 -Xmx 调到 1024m"
    err "  2. NDK 编译 OOM：在 app/build.gradle.kts 临时把 abiFilters 改成只编 arm64-v8a"
    err "  3. 强烈建议升级到 64 位 Linux"
    exit 1
}

APK="$PROJECT_DIR/app/build/outputs/apk/release/app-release.apk"
OUT_DIR="$PROJECT_DIR/build-output"
mkdir -p "$OUT_DIR"
DATE=$(date +%Y%m%d-%H%M)
OUT_APK="$OUT_DIR/PocketLLM-linux-x86-$DATE.apk"
cp "$APK" "$OUT_APK"
echo ""
echo -e "${GREEN}╔════════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║  ✓ 编译成功                                ║${NC}"
echo -e "${GREEN}╚════════════════════════════════════════════╝${NC}"
log "APK: $OUT_APK"
log "大小: $(du -h "$OUT_APK" | cut -f1)"
