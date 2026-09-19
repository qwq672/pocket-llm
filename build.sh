#!/usr/bin/env bash
# ============================================================================
# PocketLLM 通用编译入口 —— 自动识别 host 平台，调用对应脚本
#
# 用法：./build.sh
# ============================================================================

set -e
cd "$(dirname "$0")"

OS=$(uname -s)
ARCH=$(uname -m)

case "$OS" in
    Linux)
        case "$ARCH" in
            x86_64|amd64)  SCRIPT="build-linux-x86_64.sh" ;;
            aarch64|arm64) SCRIPT="build-linux-arm64.sh"  ;;
            i386|i686|x86) SCRIPT="build-linux-x86.sh"    ;;
            *)
                echo "未支持的架构: $ARCH"
                echo "请手动选择 build-linux-*.sh 之一"
                exit 1
                ;;
        esac
        ;;
    Darwin)
        echo "================================================" >&2
        echo "  macOS 检测到，本套脚本不直接支持 macOS。"     >&2
        echo "  你可以："                                    >&2
        echo "  1. 装 Android Studio macOS 版直接编译"      >&2
        echo "  2. 在 Linux 虚拟机里跑 build-linux-x86_64.sh" >&2
        echo "================================================" >&2
        exit 1
        ;;
    MINGW*|MSYS*|CYGWIN*)
        echo "================================================" >&2
        echo "  检测到 Git Bash / MSYS2 环境"                >&2
        echo "  请改用 PowerShell 跑 build-windows-x86.ps1"   >&2
        echo "  或在 cmd 里跑 build-windows-x86.bat"         >&2
        echo "================================================" >&2
        exit 1
        ;;
    *)
        echo "未支持的系统: $OS"
        exit 1
        ;;
esac

echo "检测到平台: $OS $ARCH → 使用 $SCRIPT"
exec bash "./$SCRIPT" "$@"
