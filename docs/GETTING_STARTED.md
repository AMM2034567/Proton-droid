# Proton-droid 快速上手指南

欢迎使用 **Proton-droid**！本项目旨在将 Valve 官方的 **Proton 11（ARM64 + FEX + ARM64EC DXVK）** 移植并落地到 Android 设备上。

---

## 目录结构速览

```
Proton-droid/
├── app/                        # Android 应用层 (Kotlin + NDK C++)
│   ├── src/main/cpp/           # C++ JNI 调度桥接 (fork, execve, signal, page size)
│   ├── src/main/java/          # Kotlin 进程管理与控制台界面
│   └── src/main/res/           # Android UI 布局与主题
├── scripts/
│   ├── cloud_build_proton_arm64.sh  # 云端一键交叉编译 Proton 11 ARM64 产物脚本
│   ├── proton_standalone.py         # 脱离 Steam 客户端的独立运行核心调度器
│   └── setup_device.ps1             # 本机 ADB 自动化部署与手机环境准备脚本
├── docs/
│   ├── GETTING_STARTED.md           # 本上手指南
│   └── ARCHITECTURE.md              # 路线 B 架构与技术原理解析
├── local.properties            # 本机 SDK & NDK 路径配置
└── build.gradle.kts            # 项目顶层构建脚本
```

---

## 第一步：云端编译 Proton 11 ARM64

由于 Valve 官方 SteamRT4 SDK 容器体积庞大且交叉编译包含 LLVM、Wine、DXVK、VKD3D 和 FEX，建议在具备较高 CPU 配置的云端服务器（如 8 核+、16GB+ 内存、80GB+ 硬盘）上构建：

1. 将 `scripts/cloud_build_proton_arm64.sh` 上传至云服务器：
   ```bash
   scp scripts/cloud_build_proton_arm64.sh user@your-cloud-server:/root/
   ```
2. 在服务器上赋予权限并执行：
   ```bash
   chmod +x cloud_build_proton_arm64.sh
   ./cloud_build_proton_arm64.sh
   ```
3. 脚本会自动：
   - 拉取 Valve 官方容器镜像 `registry.gitlab.steamos.cloud/proton/steamrt4/sdk/arm64-llvm:...`
   - 克隆 `ValveSoftware/Proton`（`proton_11.0` 分支及其子模块）
   - 配置目标架构为 `--target-arch=arm64` 并执行 `make redist`
   - 最终在工作目录打包出 `proton-droid-arm64-YYYYMMDD.tar.gz`

4. 将打包好的产物下载回本机。

---

## 第二步：通过 ADB 为手机准备基础运行环境

确保你的安卓手机已通过 USB 数据线连接并开启 USB 调试。

1. 在本机 PowerShell 中运行部署脚本：
   ```powershell
   cd D:\cargoproject\Proton-droid
   .\scripts\setup_device.ps1
   ```
2. 该脚本将自动：
   - 验证 ADB 通信与天玑 8100 (PGZ110) 状态；
   - 检查并安装 **Termux** 与 **Termux-X11**（用于图形窗口呈现）；
   - 在手机端建立 `/sdcard/ProtonDroid/games` 目录；
   - 推送独立启动器 `proton_standalone.py` 至手机 `/data/local/tmp/proton-droid/`。

---

## 第三步：导入云端产物并首次验证运行

1. 将第一步下载的 `proton-droid-arm64-*.tar.gz` 推送到手机：
   ```powershell
   D:\XSBDownload\SDK\platform-tools\adb.exe push proton-droid-arm64-*.tar.gz /data/local/tmp/proton-droid/
   ```
2. 进入手机终端解压：
   ```powershell
   D:\XSBDownload\SDK\platform-tools\adb.exe shell
   cd /data/local/tmp/proton-droid/
   tar -xzf proton-droid-arm64-*.tar.gz
   ```
3. 尝试启动测试应用（例如 Wine 经典的控制台或配置工具）：
   ```bash
   python3 proton_standalone.py winecfg
   ```
   *此时手机上的 Termux-X11 将弹出 Wine 的 Windows 桌面配置窗口，验证渲染管线完全打通！*

---

## 第四步：构建并安装 Android App (Kotlin + NDK)

在 Android Studio 中打开 `D:\cargoproject\Proton-droid` 目录，或者直接通过 Gradle 编译：
```powershell
.\gradlew assembleDebug
```
生成的 APK 安装到手机后，即可在界面中一键启动指定的 Windows 游戏，无需每次敲命令行！
