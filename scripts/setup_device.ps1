# ==============================================================================
# Proton-droid: Android Device Setup & Environment Provisioning Script
# ==============================================================================
param(
    [string]$AdbPath = "D:\XSBDownload\SDK\platform-tools\adb.exe",
    [switch]$SkipDownload
)

$ErrorActionPreference = "Stop"

function Write-Info($msg)  { Write-Host "[INFO] $msg" -ForegroundColor Cyan }
function Write-Ok($msg)    { Write-Host "[OK]   $msg" -ForegroundColor Green }
function Write-Warn($msg)  { Write-Host "[WARN] $msg" -ForegroundColor Yellow }
function Write-Err($msg)   { Write-Host "[ERR]  $msg" -ForegroundColor Red }

Write-Host "==========================================================" -ForegroundColor Green
Write-Host "     Proton-droid: Android 设备环境自动化部署工具         " -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Green

# 1. 验证 ADB
if (-not (Test-Path $AdbPath)) {
    $AdbPath = (Get-Command adb -ErrorAction SilentlyContinue).Source
    if (-not $AdbPath) {
        Write-Err "未找到有效的 adb.exe，请检查路径！"
        exit 1
    }
}
Write-Ok "使用 ADB 路径: $AdbPath"

# 2. 检查设备连接
$devices = & $AdbPath devices | Select-String "device$"
if (-not $devices) {
    Write-Err "未检测到已授权的安卓设备，请确保手机已连接并开启 USB 调试！"
    exit 1
}
$devModel = & $AdbPath shell getprop ro.product.model
Write-Ok "检测到已连接设备: $devModel"

# 3. 准备手机端目录
Write-Info "在手机端创建工作目录..."
& $AdbPath shell "mkdir -p /sdcard/ProtonDroid/games /sdcard/ProtonDroid/logs"
& $AdbPath shell "mkdir -p /data/local/tmp/proton-droid"
Write-Ok "手机目录结构已就绪 (/sdcard/ProtonDroid/)"

# 4. 检查与部署 Termux & Termux-X11 APK
$cacheDir = Join-Path $PSScriptRoot "..\cache"
if (-not (Test-Path $cacheDir)) {
    New-Item -ItemType Directory -Path $cacheDir | Out-Null
}

$termuxInstalled = & $AdbPath shell "pm list packages com.termux"
$x11Installed = & $AdbPath shell "pm list packages com.termux.x11"

# 4.1 Termux
if ($termuxInstalled -like "*com.termux*") {
    Write-Ok "Termux 已经安装在设备上"
} else {
    Write-Warn "设备未安装 Termux，准备部署..."
    $termuxApk = Join-Path $cacheDir "termux-app.apk"
    if (-not (Test-Path $termuxApk) -and -not $SkipDownload) {
        $termuxUrl = "https://github.com/termux/termux-app/releases/download/v0.118.1/termux-app_v0.118.1+github-debug_arm64-v8a.apk"
        Write-Info "正在下载 Termux arm64-v8a APK..."
        Invoke-WebRequest -Uri $termuxUrl -OutFile $termuxApk
    }
    if (Test-Path $termuxApk) {
        Write-Info "正在通过 ADB 安装 Termux..."
        & $AdbPath install -r $termuxApk
        Write-Ok "Termux 安装成功！"
    }
}

# 4.2 Termux-X11
if ($x11Installed -like "*com.termux.x11*") {
    Write-Ok "Termux-X11 已经安装在设备上"
} else {
    Write-Warn "设备未安装 Termux-X11（图形渲染窗口服务），准备部署..."
    $x11Apk = Join-Path $cacheDir "termux-x11.apk"
    if (-not (Test-Path $x11Apk) -and -not $SkipDownload) {
        $x11Url = "https://github.com/termux/termux-x11/releases/download/nightly/app-arm64-v8a-debug.apk"
        Write-Info "正在下载 Termux-X11 arm64-v8a APK..."
        Invoke-WebRequest -Uri $x11Url -OutFile $x11Apk
    }
    if (Test-Path $x11Apk) {
        Write-Info "正在通过 ADB 安装 Termux-X11..."
        & $AdbPath install -r $x11Apk
        Write-Ok "Termux-X11 安装成功！"
    }
}

# 5. 推送独立启动脚本到设备
$standaloneScript = Join-Path $PSScriptRoot "proton_standalone.py"
if (Test-Path $standaloneScript) {
    Write-Info "推送独立启动脚本 proton_standalone.py 到设备..."
    & $AdbPath push $standaloneScript "/data/local/tmp/proton-droid/proton_standalone.py"
    & $AdbPath shell "chmod +x /data/local/tmp/proton-droid/proton_standalone.py"
    Write-Ok "脚本推送完成！"
}

Write-Host ""
Write-Host "==========================================================" -ForegroundColor Green
Write-Host "        Android 端基础运行环境初始化就绪！                " -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Green
Write-Info "当云端编译完成生成 proton-droid-arm64-dist.tar.gz 后，可直接运行："
Write-Host "  adb push <产物路径> /data/local/tmp/proton-droid/" -ForegroundColor Yellow
Write-Host "  adb shell 'cd /data/local/tmp/proton-droid && tar -xzf proton-droid-arm64-*.tar.gz'" -ForegroundColor Yellow
