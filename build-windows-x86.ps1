# ============================================================================
# PocketLLM 一键编译脚本 —— Windows x86 / x86_64 (PowerShell 版本)
#
# 用法：
#   1. 右键点击本文件 → "使用 PowerShell 运行"
#   2. 或在 PowerShell 里：.\build-windows-x86.ps1
#
# 如果提示"无法加载脚本，因为在此系统上禁止运行脚本"：
#   以管理员身份开 PowerShell，执行：
#   Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser
# ============================================================================

$ErrorActionPreference = "Stop"
$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ProjectDir

function Log($msg)  { Write-Host "[$(Get-Date -Format 'HH:mm:ss')] $msg" -ForegroundColor Green }
function Warn($msg) { Write-Host "[WARN] $msg" -ForegroundColor Yellow }
function Err($msg)  { Write-Host "[ERR ] $msg" -ForegroundColor Red }
function Step($msg) { Write-Host "`n==== $msg ====" -ForegroundColor Blue }

Write-Host "================================================" -ForegroundColor Blue
Write-Host "  PocketLLM Build — Windows (PowerShell)        " -ForegroundColor Blue
Write-Host "================================================" -ForegroundColor Blue

# 显示架构
$arch = $env:PROCESSOR_ARCHITECTURE
Log "Host arch: $arch"

# ---------- 1. 检查工具 ----------
Step "1/7 检查基础工具"
$missing = @()
foreach ($cmd in @("git", "java", "javac", "curl")) {
    $found = Get-Command $cmd -ErrorAction SilentlyContinue
    if ($found) {
        Log "  ✓ $cmd -> $($found.Source)"
    } else {
        $missing += $cmd
        Warn "  ✗ $cmd 未安装"
    }
}
if ($missing.Count -gt 0) {
    Err "缺少工具: $($missing -join ', ')"
    Err ""
    Err "安装方法（任选一种）："
    Err ""
    Err "方式 A：手动装"
    Err "  Git:   https://git-scm.com/download/win"
    Err "  JDK17: https://adoptium.net/temurin/releases/?version=17&os=windows"
    Err "  curl:  Windows 10+ 自带，无需装"
    Err ""
    Err "方式 B：用 winget（Windows 10 1709+ 自带）"
    Err "  winget install --id Git.Git -e"
    Err "  winget install --id EclipseAdoptium.Temurin.17.JDK -e"
    Err ""
    Err "方式 C：用 Chocolatey"
    Err "  choco install git openjdk17 -y"
    exit 1
}

# 检查 Java 版本
$javaVer = (java -version 2>&1 | Select-Object -First 1) -replace '.*"(\d+)\..*', '$1'
if ($javaVer -ne "17" -and $javaVer -ne "21" -and $javaVer -ne "11") {
    Warn "  Java 版本 $javaVer，推荐 JDK 17"
} else {
    Log "  Java 版本: $javaVer"
}

# ---------- 2. Android SDK ----------
Step "2/7 检查 Android SDK"
$androidHome = $env:ANDROID_HOME
if (-not $androidHome) { $androidHome = $env:ANDROID_SDK_ROOT }
if (-not $androidHome) {
    # 试默认路径
    $candidates = @(
        "$env:LOCALAPPDATA\Android\Sdk",
        "$env:USERPROFILE\AppData\Local\Android\Sdk",
        "C:\Android\Sdk"
    )
    foreach ($p in $candidates) {
        if (Test-Path $p) { $androidHome = $p; break }
    }
}

if (-not $androidHome -or -not (Test-Path $androidHome)) {
    Err "未找到 Android SDK"
    Err ""
    Err "安装方法（任选一种）："
    Err ""
    Err "方式 A（推荐）：装 Android Studio"
    Err "  下载: https://developer.android.com/studio"
    Err "  启动后选 'Standard' 安装会自动装 SDK"
    Err ""
    Err "方式 B（命令行）："
    Err "  mkdir $env:LOCALAPPDATA\Android\Sdk\cmdline-tools"
    Err "  cd $env:LOCALAPPDATA\Android\Sdk\cmdline-tools"
    Err "  curl -LO https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
    Err "  Expand-Archive commandlinetools-win-*_latest.zip -DestinationPath ."
    Err "  Move-Item cmdline-tools latest"
    Err ""
    Err "设置环境变量（PowerShell 永久设置）："
    Err "  [Environment]::SetEnvironmentVariable('ANDROID_HOME', '$env:LOCALAPPDATA\Android\Sdk', 'User')"
    Err "  [Environment]::SetEnvironmentVariable('Path', `$env:Path + ';$env:LOCALAPPDATA\Android\Sdk\cmdline-tools\latest\bin', 'User')"
    Err "然后重开 PowerShell 再跑本脚本"
    exit 1
}
Log "  ✓ ANDROID_HOME = $androidHome"
$env:ANDROID_HOME = $androidHome

$sdkmanager = "$androidHome\cmdline-tools\latest\bin\sdkmanager.bat"
if (-not (Test-Path $sdkmanager)) {
    Err "找不到 sdkmanager: $sdkmanager"
    exit 1
}

Log "  接受 SDK 许可..."
# sdkmanager 的 license 提示需要 stdin
"y`n" * 20 | & $sdkmanager --licenses 2>&1 | Out-Null

# ---------- 3. NDK ----------
Step "3/7 检查 NDK"
$ndkVersion = "26.1.10909125"
$ndkPath = "$androidHome\ndk\$ndkVersion"
if (-not (Test-Path $ndkPath)) {
    Warn "NDK $ndkVersion 未安装，自动安装中（约 600MB）..."
    "y`n" * 5 | & $sdkmanager "ndk;$ndkVersion"
} else {
    Log "  ✓ NDK $ndkVersion"
}

# ---------- 4. CMake ----------
Step "4/7 检查 CMake"
$cmakeVersion = "3.22.1"
if (-not (Test-Path "$androidHome\cmake\$cmakeVersion")) {
    Warn "CMake $cmakeVersion 未安装，自动安装中..."
    "y`n" * 5 | & $sdkmanager "cmake;$cmakeVersion"
} else {
    Log "  ✓ CMake $cmakeVersion"
}

# ---------- 5. llama.cpp ----------
Step "5/7 拉取 llama.cpp 源码"
$cppDir = "$ProjectDir\app\src\main\cpp"
if (Test-Path "$cppDir\llama.cpp\.git") {
    Log "  llama.cpp 已存在，跳过"
} else {
    Log "  git clone llama.cpp（depth=1，约 50MB）..."
    git clone --depth=1 https://github.com/ggerganov/llama.cpp "$cppDir\llama.cpp"
    if ($LASTEXITCODE -ne 0) { Err "git clone 失败"; exit 1 }
}

# ---------- 6. local.properties ----------
Step "6/7 生成 local.properties"
# Windows 路径需要双反斜杠转义
$escapedHome = $androidHome -replace '\\', '\\'
@"
sdk.dir=$escapedHome
"@ | Set-Content -Path "$ProjectDir\local.properties" -Encoding ASCII
Log "  ✓ local.properties 已生成"

# ---------- 7. 编译 ----------
Step "7/7 编译 APK (Release)"
Log "  跑 .\gradlew.bat :app:assembleRelease --no-daemon"
Log "  （首次会下载 Gradle + AGP + Kotlin + Compose 等依赖，约 800MB）"

& .\gradlew.bat :app:assembleRelease --no-daemon "-Dorg.gradle.jvmargs=-Xmx3g"
if ($LASTEXITCODE -ne 0) {
    Err "编译失败，常见原因："
    Err "  1. 内存不足 → 关掉其他大程序，或修改 gradle.properties 里的 -Xmx"
    Err "  2. 路径含中文/空格 → 把项目移到 C:\dev\PocketLLM 这类纯英文路径"
    Err "  3. 网络不通 → 检查能否访问 dl.google.com / repo.maven.apache.org"
    Err "  4. 防火墙拦截 → 临时关 Windows Defender 防火墙试一下"
    exit 1
}

# ---------- 输出 ----------
$apk = "$ProjectDir\app\build\outputs\apk\release\app-release.apk"
$outDir = "$ProjectDir\build-output"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$date = Get-Date -Format "yyyyMMdd-HHmm"
$outApk = "$outDir\PocketLLM-windows-$date.apk"
Copy-Item $apk $outApk

Write-Host ""
Write-Host "================================================" -ForegroundColor Green
Write-Host "  ✓ 编译成功                                     " -ForegroundColor Green
Write-Host "================================================" -ForegroundColor Green
Write-Host ""
Log "APK 路径: $outApk"
Log "大小: $((Get-Item $outApk).Length / 1MB) MB"
Write-Host ""
Write-Host "安装到手机："
Write-Host "  adb install -r `"$outApk`""
Write-Host ""
Write-Host "（如果没有 adb，可以："
Write-Host "  方式 A：装 Android Studio，用 Device Manager"
Write-Host "  方式 B：单独装 platform-tools:"
Write-Host "    `"$androidHome\cmdline-tools\latest\bin\sdkmanager.bat`" `"platform-tools`""
Write-Host "  然后 adb 就在 $androidHome\platform-tools\adb.exe）"
