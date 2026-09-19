@echo off
REM ============================================================================
REM PocketLLM 一键编译脚本 —— Windows x86 / x86_64 (BAT 兜底版)
REM
REM 用法：双击本文件，或在 cmd 里执行 build-windows-x86.bat
REM
REM 注意：本脚本只是兜底，推荐改用 build-windows-x86.ps1（功能更全）
REM ============================================================================

setlocal enabledelayedexpansion
chcp 65001 >nul
cd /d "%~dp0"

echo ================================================================
echo   PocketLLM Build - Windows (BAT)
echo ================================================================

REM ---------- 1. 检查工具 ----------
echo.
echo ==== 1/7 检查基础工具 ====
set MISSING=
for %%c in (git java javac curl) do (
    where %%c >nul 2>&1
    if errorlevel 1 (
        echo   [WARN] %%c 未安装
        set MISSING=!MISSING! %%c
    ) else (
        echo   [OK] %%c
    )
)
if not "!MISSING!"==" " (
    if not "!MISSING!"=="" (
        echo.
        echo [ERR] 缺少工具:!MISSING!
        echo.
        echo 安装方法：
        echo   Git:   https://git-scm.com/download/win
        echo   JDK17: https://adoptium.net/temurin/releases/?version=17^&os=windows
        echo.
        echo 或用 winget：
        echo   winget install --id Git.Git -e
        echo   winget install --id EclipseAdoptium.Temurin.17.JDK -e
        exit /b 1
    )
)

REM ---------- 2. Android SDK ----------
echo.
echo ==== 2/7 检查 Android SDK ====
if "%ANDROID_HOME%"=="" set ANDROID_HOME=%ANDROID_SDK_ROOT%
if "%ANDROID_HOME%"=="" (
    if exist "%LOCALAPPDATA%\Android\Sdk" set ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk
)
if "%ANDROID_HOME%"=="" (
    echo [ERR] 未找到 Android SDK
    echo.
    echo 请装 Android Studio: https://developer.android.com/studio
    echo 或用 cmdline-tools: https://developer.android.com/tools
    echo.
    echo 装好后设置环境变量 ANDROID_HOME 指向 SDK 目录，重开 cmd 再跑
    exit /b 1
)
echo   [OK] ANDROID_HOME = %ANDROID_HOME%

set SDKMANAGER=%ANDROID_HOME%\cmdline-tools\latest\bin\sdkmanager.bat
if not exist "%SDKMANAGER%" (
    echo [ERR] 找不到 sdkmanager: %SDKMANAGER%
    exit /b 1
)

REM 接受许可
echo   接受 SDK 许可...
echo y | "%SDKMANAGER%" --licenses >nul 2>&1

REM ---------- 3. NDK ----------
echo.
echo ==== 3/7 检查 NDK ====
set NDK_VERSION=26.1.10909125
if not exist "%ANDROID_HOME%\ndk\%NDK_VERSION%" (
    echo   NDK %NDK_VERSION% 未安装，自动安装中（约 600MB）...
    echo y | "%SDKMANAGER%" "ndk;%NDK_VERSION%"
) else (
    echo   [OK] NDK %NDK_VERSION%
)

REM ---------- 4. CMake ----------
echo.
echo ==== 4/7 检查 CMake ====
set CMAKE_VERSION=3.22.1
if not exist "%ANDROID_HOME%\cmake\%CMAKE_VERSION%" (
    echo   CMake %CMAKE_VERSION% 未安装，自动安装中...
    echo y | "%SDKMANAGER%" "cmake;%CMAKE_VERSION%"
) else (
    echo   [OK] CMake %CMAKE_VERSION%
)

REM ---------- 5. llama.cpp ----------
echo.
echo ==== 5/7 拉取 llama.cpp ====
if exist "app\src\main\cpp\llama.cpp\.git" (
    echo   llama.cpp 已存在，跳过
) else (
    echo   git clone llama.cpp...
    git clone --depth=1 https://github.com/ggerganov/llama.cpp "app\src\main\cpp\llama.cpp"
    if errorlevel 1 (
        echo [ERR] git clone 失败
        exit /b 1
    )
)

REM ---------- 6. local.properties ----------
echo.
echo ==== 6/7 生成 local.properties ====
REM Windows 路径双反斜杠转义
set ESCAPED_HOME=%ANDROID_HOME:\=\\%
echo sdk.dir=%ESCAPED_HOME%> local.properties
echo   [OK]

REM ---------- 7. 编译 ----------
echo.
echo ==== 7/7 编译 APK (Release) ====
echo   跑 gradlew.bat :app:assembleRelease --no-daemon
echo   （首次会下载 Gradle + 依赖，约 800MB）
call gradlew.bat :app:assembleRelease --no-daemon -Dorg.gradle.jvmargs="-Xmx3g"
if errorlevel 1 (
    echo.
    echo [ERR] 编译失败，常见原因：
    echo   1. 内存不足 - 关掉其他大程序
    echo   2. 路径含中文/空格 - 移到 C:\dev\PocketLLM
    echo   3. 网络不通 - 检查 dl.google.com 可达性
    exit /b 1
)

REM ---------- 输出 ----------
set APK=app\build\outputs\apk\release\app-release.apk
if not exist "build-output" mkdir build-output
for /f "tokens=2 delims==" %%a in ('"wmic os get localdatetime /value"') do set LDT=%%a
set DATE=%LDT:~0,8%-%LDT:~8,4%
set OUT_APK=build-output\PocketLLM-windows-%DATE%.apk
copy /y "%APK%" "%OUT_APK%" >nul

echo.
echo ================================================================
echo   ✓ 编译成功
echo ================================================================
echo.
echo APK: %CD%\%OUT_APK%
for %%I in ("%OUT_APK%") do echo 大小: %%~zI 字节
echo.
echo 安装到手机：
echo   adb install -r "%CD%\%OUT_APK%"
echo.

endlocal
