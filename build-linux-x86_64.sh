#!/usr/bin/env bash
# ============================================================================
# PocketLLM 一键编译脚本 —— Linux x86_64 (最常见 Linux PC 环境)
#
# 用法：
#   1. chmod +x build-linux-x86_64.sh
#   2. ./build-linux-x86_64.sh
#
# 脚本会自动：
#   - 检查 git / java / javac
#   - 检查 Android SDK / NDK / CMake
#   - 拉取 llama.cpp 源码
#   - 生成 local.properties
#   - 跑 ./gradlew assembleRelease
#   - 把 APK 复制到 build-output/
#
# 失败时请按提示安装对应依赖后重跑。
# ============================================================================

set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

# 颜色
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m'
log()  { echo -e "${GREEN}[$(date +%H:%M:%S)]${NC} $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
err()  { echo -e "${RED}[ERR ]${NC} $*" >&2; }
step() { echo -e "\n${BLUE}==== $* ====${NC}"; }

echo -e "${BLUE}╔════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║  PocketLLM Build — Linux x86_64           ║${NC}"
echo -e "${BLUE}╚════════════════════════════════════════════╝${NC}"

# ---------- 1. 检查基本工具 ----------
step "1/7 检查基础工具"
MISSING=()
for cmd in git java javac curl unzip; do
    if command -v $cmd >/dev/null 2>&1; then
        log "  ✓ $cmd → $(command -v $cmd)"
    else
        MISSING+=("$cmd")
        warn "  ✗ $cmd 未安装"
    fi
done

if [ ${#MISSING[@]} -gt 0 ]; then
    err "缺少工具: ${MISSING[*]}"
    err ""
    err "在 Debian/Ubuntu 上安装："
    err "  sudo apt update && sudo apt install -y git curl unzip openjdk-17-jdk"
    err "在 Fedora/RHEL 上："
    err "  sudo dnf install -y git curl unzip java-17-openjdk-devel"
    err "在 Arch 上："
    err "  sudo pacman -S git curl unzip jdk17-openjdk"
    exit 1
fi

JAVA_VER=$(java -version 2>&1 | head -1 | awk -F\" '{print $2}' | cut -d. -f1)
if [ "$JAVA_VER" != "17" ] && [ "$JAVA_VER" != "21" ] && [ "$JAVA_VER" != "11" ]; then
    warn "  Java 版本 $JAVA_VER，推荐 JDK 17（Android Gradle Plugin 8.5 要求）"
fi
log "  Java 版本: $JAVA_VER"

# ---------- 2. 检查 Android SDK ----------
step "2/7 检查 Android SDK"
ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$ANDROID_HOME" ]; then
    # 试默认路径
    for p in "$HOME/Android/Sdk" "$HOME/.android-sdk" "/opt/android-sdk"; do
        if [ -d "$p" ]; then
            ANDROID_HOME="$p"; break
        fi
    done
fi

if [ -z "$ANDROID_HOME" ] || [ ! -d "$ANDROID_HOME" ]; then
    err "未找到 Android SDK"
    err ""
    err "请选一种方式安装："
    err "  方式 A（推荐）：装 Android Studio，启动后会自动装 SDK"
    err "    下载: https://developer.android.com/studio"
    err "  方式 B（命令行）：下载 cmdline-tools"
    err "    mkdir -p ~/Android/Sdk/cmdline-tools"
    err "    cd ~/Android/Sdk/cmdline-tools"
    err "    curl -LO https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
    err "    unzip commandlinetools-linux-*_latest.zip"
    err "    mv cmdline-tools latest"
    err "  然后设置环境变量："
    err "    export ANDROID_HOME=\$HOME/Android/Sdk"
    err "    export PATH=\$PATH:\$ANDROID_HOME/cmdline-tools/latest/bin"
    err "  把这两行加到 ~/.bashrc 或 ~/.zshrc 后 source 一下，再重跑本脚本"
    exit 1
fi
log "  ✓ ANDROID_HOME = $ANDROID_HOME"
export ANDROID_HOME

# cmdline-tools 是否就位
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$SDKMANAGER" ]; then
    err "找不到 sdkmanager: $SDKMANAGER"
    err "请按上面方式 B 安装 cmdline-tools"
    exit 1
fi

# 接受许可（避免交互）
log "  接受 SDK 许可..."
yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true

# ---------- 3. 检查 NDK ----------
step "3/7 检查 NDK"
NDK_VERSION="26.1.10909125"
NDK_PATH="$ANDROID_HOME/ndk/$NDK_VERSION"
if [ ! -d "$NDK_PATH" ]; then
    warn "NDK $NDK_VERSION 未安装，自动安装中（约 600MB，请耐心等待）..."
    yes | "$SDKMANAGER" "ndk;$NDK_VERSION"
else
    log "  ✓ NDK $NDK_VERSION 已就位"
fi

# ---------- 4. 检查 CMake ----------
step "4/7 检查 CMake (Android SDK CMake)"
CMAKE_VERSION="3.22.1"
CMAKE_PATH="$ANDROID_HOME/cmake/$CMAKE_VERSION"
if [ ! -d "$CMAKE_PATH" ]; then
    warn "CMake $CMAKE_VERSION 未安装，自动安装中..."
    yes | "$SDKMANAGER" "cmake;$CMAKE_VERSION"
else
    log "  ✓ CMake $CMAKE_VERSION 已就位"
fi

# ---------- 5. 拉取 llama.cpp ----------
step "5/7 拉取 llama.cpp 源码"
CPP_DIR="$PROJECT_DIR/app/src/main/cpp"
if [ -d "$CPP_DIR/llama.cpp/.git" ]; then
    log "  llama.cpp 已存在，跳过 clone（如需更新请手动 cd 进去 git pull）"
else
    log "  git clone llama.cpp（depth=1，约 50MB）..."
    git clone --depth=1 https://github.com/ggerganov/llama.cpp "$CPP_DIR/llama.cpp"
fi

# ---------- 6. 生成 local.properties ----------
step "6/7 生成 local.properties"
cat > "$PROJECT_DIR/local.properties" <<EOF
# 自动生成，请勿手动编辑
sdk.dir=$ANDROID_HOME
EOF
log "  ✓ local.properties 已生成"

# ---------- 7. 编译 ----------
step "7/7 编译 APK (Release)"

# 如果是首次跑，先初始化 wrapper（gradlew 已自带，不用）
log "  跑 ./gradlew :app:assembleRelease --no-daemon"
log "  （首次会下载 Gradle 8.10.1 + Android Gradle Plugin + Kotlin + Compose 等依赖，约 800MB，请耐心等待）"

./gradlew :app:assembleRelease --no-daemon -Dorg.gradle.jvmargs="-Xmx3g" || {
    err "编译失败，常见原因："
    err "  1. 内存不足 → 关掉其他大程序，或修改 gradle.properties 里的 -Xmx"
    err "  2. 网络不通 → 检查能否访问 dl.google.com / repo.maven.apache.org"
    err "  3. NDK 未装 → 重新跑本脚本会自动装"
    err "  4. CMake 报错 → 看错误日志，常见是 llama.cpp 子模块版本不兼容"
    exit 1
}

# ---------- 输出 ----------
APK="$PROJECT_DIR/app/build/outputs/apk/release/app-release.apk"
OUT_DIR="$PROJECT_DIR/build-output"
mkdir -p "$OUT_DIR"
DATE=$(date +%Y%m%d-%H%M)
OUT_APK="$OUT_DIR/PocketLLM-linux-x86_64-$DATE.apk"
cp "$APK" "$OUT_APK"

echo ""
echo -e "${GREEN}╔════════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║  ✓ 编译成功                                ║${NC}"
echo -e "${GREEN}╚════════════════════════════════════════════╝${NC}"
echo ""
log "APK 路径: $OUT_APK"
log "大小: $(du -h "$OUT_APK" | cut -f1)"
echo ""
echo "安装到手机："
echo "  adb install -r \"$OUT_APK\""
echo ""
echo "（如果没有 adb，可以："
echo "  sudo apt install adb   # Linux"
echo "  或在 Android Studio 里用 Device Manager 装机）"
