# Proton-droid 快速上手指南

> 📌 开工前先看 [`docs/PROJECT_MEMORY.md`](PROJECT_MEMORY.md)：项目记忆（铁律、代码地图、构建/验证手册、显示层路线与 TODO）。

欢迎使用 **Proton-droid**！本项目旨在将 Valve 官方的 **Proton 11（ARM64 + FEX + ARM64EC DXVK）** 移植并落地到 Android 设备上。

---

## 目录结构速览

```
Proton-droid/
├── app/                        # Android 应用层 (Kotlin + NDK C++)
│   ├── src/main/assets/proot/  # 内置 aarch64 PRoot 工具链 (proot/loader/libtalloc/libandroid-shmem)
│   ├── src/main/cpp/           # C++ JNI 调度桥接 (fork, execve, signal, page size, X11 探测)
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

## ⚠️ 关键约束：targetSdk 必须保持 28

Android 10 (API 29) 起对新应用启用 **W^X 限制**：应用私有目录
(`/data/user/0/<pkg>`) 中的文件 **既不能 `execve()`，也不能建立可执行映射
(`mmap PROT_EXEC`)**。Proton-droid 需要在这个沙箱里执行 PRoot，并让 Wine 把 PE 镜像
映射成可执行内存，因此 **`targetSdk` 必须保持 28**，进程才会落在
`untrusted_app_27` 域（可用 `cat /system/etc/selinux/plat_seapp_contexts` 验证）。

同样的做法见：
- Termux（`targetSdk 28`，否则 `proot-distro` 无法执行 `$PREFIX/bin` 下的二进制）
- Wine 官方 Android 移植：*“wineandroid: lower targetSdkVersion to avoid Android 10 W^X restrictions”*

如果误把 `targetSdk` 提到 29+，表现为：

| 症状 | 根因 |
| --- | --- |
| `execve failed ... (errno: 13)`，日志文件 0 字节，界面显示退出码 127 | `untrusted_app` 域禁止执行私有目录文件 |
| `wineserver: failed to load l_intl.nls` 后 `wine client error: recvmsg: Connection reset by peer` | wine 数据目录未 bind 到 `/usr/share/wine` |
| `err:virtual:map_image_into_view failed to set 60000020 protection ... noexec filesystem?` | W^X 禁止私有目录文件的可执行映射 |

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

> 也可以走 GitHub Actions：推送到 `main`（`app/**`、`build.gradle.kts` 变更时）会自动
> `assembleDebug` 并把 APK 传到 Artifacts，`workflow_dispatch` 还会顺带发布 Release。

---

## 第五步：运行期行为与常见坑（真机验证记录）

### 存储权限

`targetSdk = 28` 走 legacy storage：App 会在启动时申请
`READ/WRITE_EXTERNAL_STORAGE` 运行时权限，授予后即可用**路径方式**访问
`/sdcard/Download/ProtonDroid/`（游戏、`rootfs.tar.gz`、Proton 载荷）。
Android 11+ 的「所有文件访问权限」只作为兜底提示，不再强制跳转设置页。

> 注意：部分 ROM（如 ColorOS）不允许 `adb shell pm grant`，只能走应用内弹窗授权。

### 停止游戏会终止整棵进程树

`forkAndExec` 的子进程在 `execve` 前调用 `setpgid(0, 0)` 自建进程组，
`stopSession` 对**进程组**发 `SIGTERM`/`SIGKILL`，因此
`proot → wine → wineserver → wineboot → winedevice` 会被一次性收干净
（早期版本只杀直接子进程，会留下 4 个孤儿进程）。

### 残留进程自动清理

应用进程被系统回收后，`proot` 会变僵尸、其下的 wine 进程可能被 reparent 到 init
继续存活。App 启动时 / 启动新会话前 / 停止会话后都会调用
`NativeBridge.cleanupStaleProcesses(filesDir, activePid)`：

1. **若记录中的会话 pid 仍存活 ⇒ 整个清理直接跳过**（会话 pid 持久化在
   `files/active-session.pid`）。真机验证：会话运行中触发清理会打印
   `active session pid=… alive, skip sweep`，整棵 wine 进程树不受影响。
   这里刻意**不**用「父进程链」判断残留 —— wine 的
   `wineserver / wineboot / winedevice` 本来就 daemon 化（`PPid=1`），按父子关系
   会把活跃会话当成孤儿误杀。
2. 否则按「同 uid + `/proc/<pid>/exe` 位于本应用私有目录」匹配并 `SIGKILL`。
   真机验证：删除会话记录后触发清理，9 个进程（proot + wine 系）被一次清空。

### 本地编译注意（CMake 与沙箱）

本地 `./gradlew assembleDebug` 时，CMake 会在 *Detecting C compiler ABI info* 阶段
通过管道捕获编译器输出；若 DSH 运行在受限文件沙箱模式下，该步会因命名管道被禁止
而挂死。改为 `danger-full-access` 后构建正常（增量约 6–60 秒）。
本地 debug 构建使用 `<ANDROID_USER_HOME>/debug.keystore`，签名固定，
因此后续可直接 `adb install -r` 覆盖安装、**不必清空 2.4GB 已解包运行时**。


