# Proton-droid 项目记忆（Project Memory）

> 最后更新：2026-10-03 会话收尾
> 状态：**「游戏无法启动」已修复并在真机逐层验证**；显示层方案已定 **B（内嵌 Xlorie）**，后续演进到 **C（wineandroid.drv）**。
> 正式版方向：**App 内置下载 rootfs 与编译好的 Proton 产物**（产物走自己打包 → GitHub Release），见第 12 节。
> 本文件是下次开工的第一入口；改动运行链路的代码前请先读第 2 节「铁律」。

---

## 0. 一句话现状

App 已能**完全脱离 Termux** 在自身沙箱里把 Windows 游戏进程拉起来：
`App → proot(aarch64) → Debian 13 glibc guest → Proton wine-11.0 → wineserver/wineboot/winedevice`。
唯一缺口是**画面**：Proton 载荷只带 X11 后端（`winex11.so`），必须有 X 服务器才能出窗口。

---

## 1. 项目目标与架构

- 目标：把 Valve Proton 11（ARM64 + FEX + ARM64EC DXVK）搬到 Android，做**自包含** App —— 不依赖 Termux / proot-distro / Termux-X11。
- 运行模型：App 私有目录里跑 PRoot 伪 root，进入 Debian glibc rootfs，再执行 Proton 的 ARM64 wine。
- 历史遗留：`scripts/*.sh`（proot-distro 那套）与 `proton_standalone.py` 是早期 Termux 路线，**已不是必需路径**，仅作参考。

---

## 2. 铁律（改代码前必读，全部真机踩过）

1. **`targetSdk` 必须保持 28。**
   Android 10+ 对 targetSdk ≥ 29 的应用启用 W^X：应用私有目录里的文件**既不能 `execve` 也不能做可执行映射**。
   - 症状 a：`execve failed ... (errno: 13)`，`proton-stdout.log` 0 字节，界面只显示退出码 127。
   - 症状 b：wine 报 `err:virtual:map_image_into_view failed to set 60000020 protection ... noexec filesystem?`。
   - 验证域：`cat /proc/<pid>/attr/current` → 应为 `u:r:untrusted_app_27:s0`；`/system/etc/selinux/plat_seapp_contexts` 里 `minTargetSdkVersion=28` 才映射到该域。
   - 同样做法：Termux（targetSdk 28）、Wine 官方 Android 移植补丁（wineandroid: lower targetSdkVersion to avoid Android 10 W^X restrictions）。

2. **PRoot 工具链必须是设备同架构（aarch64）且组件齐全。**
   必须 5 个文件一起放到 `files/bin/`：`proot`、`loader`、`loader32`、`libtalloc.so.2`、`libandroid-shmem.so`。
   - `proot-me/proot` 的 release 资产 `proot` 是 **x86_64**，不能直接用（ARM64 上直接 ENOEXEC/不可执行）。
   - 必须设置 `PROOT_LOADER`、`PROOT_LOADER_32`、`PROOT_TMP_DIR`（Android 没有 `/tmp`）、`LD_LIBRARY_PATH=<files/bin>`。
   - `PROOT_NO_SECCOMP=1`。

3. **guest root 的层次不能假定。** `rootfs.tar.gz` 可能是扁平根，也可能是 `debian/rootfs/...`。
   统一走 `ProtonLayout.resolveGuestRoot()`（当前设备解析结果：`files/rootfs/debian/rootfs`）。
   Proton 载荷必须落在 `<guestRoot>/opt/proton`。

4. **Wine 的编译期数据目录是 `/usr/share/wine`**，运行期必须 bind：
   `-b <guestRoot>/opt/proton/files/share/wine:/usr/share/wine`，否则
   `wineserver: failed to load l_intl.nls` → `wine client error:0: recvmsg: Connection reset by peer`。
   另外 `WINEDLLPATH=<proton>/files/lib/vkd3d:<proton>/files/lib/wine` 不能少。

5. **wine 的 `wineserver/wineboot/winedevice` 会 daemon 化（PPid=1）**，
   所以判断「是否为残留进程」**不能**用父进程链，必须用「是否有活跃会话」。

6. **Android Vulkan 只暴露 `VK_KHR_android_surface`**。
   DXVK 走 X11 窗口时没有可用的 WSI —— 这是显示层 B 方案之后仍要补的一块（见第 8 节）。

7. **proot 自身路径不能出现在 `args` 里**（`forkAndExec` 已把 `argv[0]` 注入），否则 proot 会把自身当成要执行的程序。

---

## 3. 启动链路（当前实现）

```
MainActivity ──Intent──> GameViewActivity ──startForegroundService──> ProtonForegroundService
                                                                          │
                                                   ProtonProcessManager.launchGame()
                                                                          │
                        fork() + execve()  （JNI；失败时经 CLOEXEC 管道回传 errno）
                                                                          │
                        files/bin/proot  ← APK assets 释放的 aarch64 工具链
                                                                          │
        proot -r <guestRoot> -0 -w <游戏目录> --kill-on-exit -b /sdcard:/sdcard \
              -b /dev:/dev -b /proc:/proc -b /sys:/sys \
              -b <guestRoot>/opt/proton/files/share/wine:/usr/share/wine \
              /opt/proton/files/bin-arm64/wine explorer /desktop=ProtonDroid,1280x720 <game.exe>
                                                                          │
                              DISPLAY=:0 ──> 需要一个 X 服务器（当前缺；B 方案要做）
```

---

## 4. 代码地图

| 文件 | 职责 |
| --- | --- |
| `app/src/main/java/com/protondroid/runtime/ProtonLayout.kt` | 目录布局 + guest root 探测 + ELF 架构校验 + host→guest 路径换算 |
| `app/src/main/java/com/protondroid/runtime/ProtonRuntimeInstaller.kt` | 一键装配：工具链（assets/公共目录）→ rootfs 解压 → Proton 载荷 → prefix → 自检 |
| `app/src/main/java/com/protondroid/runtime/WinePrefix.kt` | 从 `default_pfx_arm64` 克隆 prefix，符号链接改写为 **guest 绝对路径** |
| `app/src/main/java/com/protondroid/ProtonProcessManager.kt` | 启动参数/环境构造、errno 中文报错、进程监控、停止（进程组）、残留清理 |
| `app/src/main/java/com/protondroid/ProtonStatus.kt` | 极简状态总线（主界面 + 游戏视窗同时显示状态） |
| `app/src/main/cpp/proton_bridge.cpp` | `forkAndExec`(CLOEXEC 管道回传 errno / `setpgid`)、`checkX11Display`、`killProcess`(进程组)、`cleanupStaleProcesses`、`waitPid`(编码状态)、ANativeWindow 直通 |
| `app/src/main/assets/proot/` | 内置 aarch64 工具链（5 个文件，见第 9 节来源） |
| `app/src/main/AndroidManifest.xml` | targetSdk 28 的原因注释、`requestLegacyExternalStorage` |

---

## 5. 构建与部署手册

### 5.1 本地构建（推荐，迭代快、签名固定）

```powershell
$env:GRADLE_USER_HOME='D:\cargoproject\Proton-droid\.gradle-home'   # 从 E:\Android\.gradle 拷来的 gradle-8.7-bin
$env:GRADLE_OPTS='-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=10809 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=10809'
$env:ANDROID_USER_HOME='D:\cargoproject\Proton-droid\.android-home' # debug.keystore 落这里 → 签名固定
.\gradlew.bat assembleDebug --no-watch-fs --console=plain
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

- **必须 full-access 文件策略**：受限沙箱下 CMake 会卡在 *Detecting C compiler ABI info*（需要管道捕获编译器输出，被沙箱禁止命名管道）。
- 卡住后的清理：`.\gradlew.bat --stop` → 杀 `java/cmake/ninja` → 删 `app/build`、`app/.cxx`。
- 本地 debug keystore 固定（SHA-256 `5b1a37d1ac1c53e2190d8e9940fbccffc37bab4a9e5ebb33d321e069285f6db3`），
  因此 **`adb install -r app/build/outputs/apk/debug/app-debug.apk` 可覆盖安装、不清数据**。

### 5.2 CI 构建

- 工作流：`.github/workflows/build-apk.yml`（push `main` 且改到 `app/**`、`build.gradle.kts`、`settings.gradle.kts` 时触发；`workflow_dispatch` 额外发 Release）。
- `gh` CLI 已登录 `AMM2034567`；`git push` 需要代理 + token URL（见第 9 节命令片段）。
- **CI 产物签名每次不同**（runner 临时 debug keystore），覆盖安装必然
  `INSTALL_FAILED_UPDATE_INCOMPATIBLE` → 只能卸载重装 → **会清掉 ~2.4GB 已解包运行时**，
  装好后需再点一次「一键导入 / 重置 Proton 核心」（从 `/sdcard` 解包，约 1 分钟）。

---

## 6. 真机验证手册（PGZ110 / Android 15）

```powershell
$adb='D:\XSBDownload\SDK\platform-tools\adb.exe'

# a) 装/覆盖
& $adb install -r app\build\outputs\apk\debug\app-debug.apk

# b) 权限（ColorOS 下 pm grant / appops set 会被拒，只能走应用内弹窗）
#    首次启动会弹存储权限 → 允许；日志应出现“已发现 N 款游戏”

# c) 运行时自检（最关键的回归点：能否执行私有目录内的 proot）
#    点「运行时自检」→ 期望：
#    [自检] 自检完成: proot 可执行 (退出码 0) 输出: … Copyright (C) 2015 STMicroelectronics…
#    旧版本这里是无输出 + errno 13

# d) 启动游戏后应看到
& $adb shell "ps -A -o PID,PPID,NAME | grep -E 'proot|wine'"
#    proot → wine → wineserver → wineboot.exe → winedevice.exe
& $adb logcat -d -v brief | Select-String "ProtonBridge|ProtonProcessMgr"
#    期望出现 "Spawned Proton child process"；绝不出现 "execve failed ... errno: 13"
& $adb shell "run-as com.protondroid cat files/proton-stdout.log"   # 不再是 0 字节

# e) 退出（点游戏视窗右上「退出游戏」）后必须干净
& $adb shell "ps -A -o PID,NAME | grep -E 'proot|wine'"            # 期望：空

# f) 清理门禁（活跃会话保护）
#    会话运行中触发 MainActivity.onCreate（新任务）→ 日志：
#    cleanupStaleProcesses: active session pid=… alive, skip sweep    且进程树存活

# g) X11 是否可达（非 Termux-X11 环境下为 false，属预期）
& $adb logcat -d -v brief | Select-String "No X11 server reachable"
```

---

## 7. 本次修复清单（提交 → 症状 → 证据）

| 提交 | 修的内容 |
| --- | --- |
| `4f5d36a` | **主修复**：targetSdk 28（W^X 执行权限）、内置 aarch64 PRoot 工具链、guest root 探测、proot 参数/环境、WINEDLLPATH、`share/wine→/usr/share/wine`、errno 回传、自检、X11 预检、状态条 |
| `8ab451e` | `java.io.File` 无 `isSymbolicLink/readSymlink` → 改 NIO；固定 `buildToolsVersion=35.0.0`（本机 34.0.0 是残缺安装） |
| `9a075a7` | 补 `ProtonRuntimeInstaller` 导入；忽略 `app/build/`、`.cache-gh/`（曾误提交 413 个构建产物，已 force-push 清除） |
| `be80660` | **存储权限**：targetSdk 28 走 legacy storage，改为始终申请 `READ/WRITE_EXTERNAL_STORAGE`（Android 11+「所有文件访问」开关在 ColorOS 上对 legacy 应用无效），并加 `onRequestPermissionsResult` |
| `076a351` | **prefix 误判**（`exists()` 无法解析 guest 绝对路径符号链接 → 每次启动白克隆 37MB）；**`waitPid` 语义**（退出码 0 与“仍在运行”混同 → 正常退出被报成“被信号终止”） |
| `6d0c452` | **退出残留**：子进程 `setpgid(0,0)` + `kill(-pid)` 终止整组（此前点退出会留下 4 个 wine 孤儿）；新增孤儿清理 |
| `a3fe0ad` → `05f1738` | 清理判据两次修正：父进程链版本会误杀（wine 进程 PPid 本来就是 1）→ 最终改为**持久化会话 pid 门禁**（`files/active-session.pid`） |

真机已验证的行为（全部在上面第 6 节可复现）：
`自检退出码 0` / `无 errno 13` / `完整 wine 进程树` / `日志非空` / `退出零残留` / `活跃会话不被误杀` / `清理路径命中 9 个同域进程`。

---

## 8. 显示层路线（已定：先 B，后 C）

### 8.1 为什么必须有 X 服务器

Proton 载荷 `files/lib/wine/aarch64-unix/` 里只有 `winex11.so`、`win32u.so`、`winevulkan.so`、`opengl32.so`，
**没有 `wineandroid.drv.so`、也没有 `winewayland.so`**。因此 `GameViewActivity` 的 `SurfaceView`
只是拿到了 `ANativeWindow` 句柄，没人往里画 —— 文档里「SurfaceView 直通」的说法不成立。

### 8.2 B 方案：内嵌 Termux-X11 的 X 服务器（已确认可行）

已核实的事实（从设备上 `com.termux.x11` 的 `base.apk` 提取）：

```
lib/arm64-v8a/libXlorie.so   3,550,768 bytes   aarch64, SONAME=libXlorie.so
NEEDED: libGLESv2.so libandroid.so libmediandk.so liblog.so libm.so libz.so libEGL.so libc.so
        → 只有 Android 系统库，没有 Termux 私有依赖 ✅ 可直接作为 jniLib 内嵌
导出符号: JNI_OnLoad（按 JNI 方式加载）
native 侧写死的 Java 契约类: com/termux/x11/LorieView、com/termux/x11/CmdEntryPoint、com/termux/x11/MainActivity
```

因此 B 的实现路径：

1. 从 `termux/termux-x11` 上游移植 Java 胶水（`LorieView` + `CmdEntryPoint` 等，保持 FQCN 与 native 方法签名一致），
   去掉所有 Termux 文件系统/前缀假设。
2. 把 `libXlorie.so` 放进 `jniLibs/arm64-v8a/`（`System.loadLibrary`），由 App 提供 Surface + 输入注入 + 生命周期。
3. X 服务器与 proot guest 通过 `DISPLAY=:0` 的**抽象 socket** `@/tmp/.X11-unix/X0` 通信
   （同一 network namespace，不需要 bind 文件系统路径）。
4. 先于 `launchGame` 启动 X 服务器；`checkX11Display` 通过后即可去掉「Termux-X11 提示」。
5. 验证顺序：guest 内 `xwininfo -root -tree` 能连上 → `wine explorer /desktop=...` 能建窗口 →
   画面出现在 SurfaceView。

风险/待办：GPLv3（Xorg 派生）合规；Java 胶水移植量；**D3D11/DXVK 还需要 Vulkan WSI 桥**
（把 Win32 Vulkan swapchain 接到 ANativeWindow，或提供 X11 present 桥）。

### 8.3 C 方案（终态，B 之后再上）

用 `wineandroid.drv` 重编 Proton ARM64：Wine 直接渲染进 `ANativeWindow`，
Vulkan/DXVK 用 Android 原生 `VK_KHR_android_surface` 直出 Surface，**彻底取消 X 和合成层**，性能上限最高。
代价：全栈交叉编译（LLVM/Wine/DXVK/VKD3D/FEX）+ 移植 `org.winehq.wine` 的 Java 侧。
证据表明这个方向是生态共识：上游已有 *wineandroid: lower targetSdkVersion to avoid Android 10 W^X restrictions* 补丁。

### 8.4 为什么不选 A（Xvfb + 帧缓冲 blit）

Xvfb 是纯软件 X 服务器（GLX 走 swrast，OpenGL 全 CPU），且软 X 无法接收 Vulkan 呈交 → DXVK 基本无解。
只适合当「先让画面出来」的临时脚手架。

---

## 9. 环境 / 设备 / 工具链事实

| 项 | 值 |
| --- | --- |
| 设备 | PGZ110（天玑 8100 / Mali-G610），Android 15，arm64-v8a，已 root? 否 |
| adb | `D:\XSBDownload\SDK\platform-tools\adb.exe` |
| SDK / NDK / CMake | `D:\XSBDownload\SDK`，NDK `27.2.12479018`，CMake `3.22.1`，build-tools `35.0.0`（⚠️ 本机 `34.0.0` 只有 `.installer`，是残缺安装） |
| JDK | `D:\jdk-17.0.20.101-hotspot` |
| Gradle | wrapper 8.7；缓存根 `E:\Android\.gradle`（只读），本仓库用 `.gradle-home`（已 gitignore） |
| 代理 | `10809`(http) / `10808`(socks5)；`gh` 走 api.github.com 通常可用，artifact 下载有时需手动 |
| 设备侧 staging | `/sdcard/Download/ProtonDroid/`：`rootfs.tar.gz`(771MB)、`proton-droid-arm64.tar.gz`(589MB)、`games/`(goose、osu)、`proot_arm64/`(工具链副本，可作公共覆盖源) |
| 远端 | `https://github.com/AMM2034567/Proton-droid`（`gh` 已登录，scopes: repo/workflow） |

内置工具链来源（Termux 官方源，均已解包为裸文件）：

| 文件 | 来源 | SHA-256（前 12） |
| --- | --- | --- |
| `proot` | `packages.termux.dev/.../proot/proot_5.1.107.96_aarch64.deb` | `1545B85B3125` |
| `loader` / `loader32` | 同上（`usr/libexec/proot/`） | `CBDEF0E652C2` / `C429996FEE73` |
| `libtalloc.so.2` | `.../libtalloc/libtalloc_2.5.0_aarch64.deb` | `742B438C4D09` |
| `libandroid-shmem.so` | `.../libandroid-shmem/libandroid-shmem_0.7_aarch64.deb` | `84475798E07C` |

推送命令（需代理；token 不落盘）：

```powershell
$tok = (& 'C:\Program Files\GitHub CLI\gh.exe' auth token).Trim()
$env:GIT_TERMINAL_PROMPT='0'
git -c credential.helper= -c http.proxy=http://127.0.0.1:10809 `
  push "https://x-access-token:$tok@github.com/AMM2034567/Proton-droid.git" HEAD:main
```

---

## 10. 已知坑 / 未解问题

1. **画面仍缺 X 服务器**（第 8 节，B 方案待做）。
2. **DXVK 的 Vulkan WSI**：Android 只有 `VK_KHR_android_surface`；走 X11 时需自建 WSI 桥（B 之后）。
3. **ColorOS 限制**：`adb shell pm grant` / `appops set` 均被拒；只能应用内弹窗授权。
   `am kill` 对有前台服务的 App 无效；`am crash` 会连子进程一起带走（cgroup/进程组回收）。
4. **run-as 的域是 `runas_app`**，App 是 `untrusted_app_27`，跨域读 `/proc/<pid>/status|exe` 会被拒 ——
   用 run-as 造「残留进程」来测清理会得到假阴性。
5. **PulseAudio 音频**：`PULSE_SERVER=127.0.0.1` 只是占位，App 内没有音频服务（未验证）。
6. `docs/ARCHITECTURE.md` 早期写的「ANativeWindow 直通渲染」不准确，已在文档中改成「显示依赖 X 服务器」。

---

## 11. 下次开工 TODO（按优先级）

**P0 — B 方案（显示层，当前唯一硬缺口）**

1. 拉取 `termux/termux-x11` 源码，梳理 `LorieView` / `CmdEntryPoint` 与 native 的 JNI 契约（方法签名、回调、启动参数、环境变量）。
2. 把 `libXlorie.so` 以 `jniLibs` 形式引入（`System.loadLibrary`），确认在 `untrusted_app_27` 域下能 `dlopen` + 建 Surface。
3. 实现最小 Java 胶水：Surface 提供、输入注入（touch→X 事件）、生命周期；剪贴板先不做。
4. `ProtonProcessManager.launchGame` 前启动 X 服务器；`checkX11Display` 从「提示」升级为「硬前置条件」。
5. 验证链：guest `xwininfo -root -tree` → `wine explorer /desktop=ProtonDroid,1280x720 <game.exe>` → SurfaceView 出现画面。
6. 记录性能基线（osu!/goose 帧率、CPU/GPU 占用），作为 C 方案的对比依据。

**P1 — 正式版运行时下载与发布链路**（见第 12 节）

7. 定义 `runtime-manifest.json` 并让 App 按 manifest 下载 / 校验 / 解包 rootfs 与 Proton。
8. `scripts/export_rootfs.sh` 改成**干净 rootfs 打包**（debootstrap 或精简导出），产物发 Release。
9. `cloud_build_proton_arm64.sh` 的产物发 Release，并在 manifest 里登记版本 + SHA256。
10. 下载体验：镜像回退、断点续传、空间预检、失败可续、进度可视化。

**P2 — C 方案（终态）**

11. 用 `wineandroid.drv` 重编 Proton ARM64，取消 X 服务器与合成层（Vulkan 直出 `ANativeWindow`）。

**P3 — 收尾体验**

12. 音频（PulseAudio 目前只是占位 `PULSE_SERVER=127.0.0.1`，未验证）、输入映射（手柄/键鼠）、prefix 存档备份/迁移。

---

## 12. 正式版方向：内置下载运行时 + Release 发布链路

目标：用户装完 APK 后**不需要手动往 `/sdcard` 拷任何东西**，App 自己把干净的 glibc rootfs 与编译好的
Proton 产物下载并装配好。

### 12.1 现状（起点）

| 组件 | 现状 | 缺口 |
| --- | --- | --- |
| PRoot 工具链（5 文件） | 已作为 APK assets 内置；也支持 `/sdcard/Download/ProtonDroid/proot_arm64/` 覆盖 | 基本够用，可加版本号 |
| `proton-droid-arm64.tar.gz`（Proton 11 载荷） | `ProtonRuntimeInstaller.URL_PROTON_CORE` 已有下载地址 + `downloadWithProgress()` | 无版本管理/校验；URL 应指向自己的 Release |
| `rootfs.tar.gz`（glibc 环境） | **只能从 `/sdcard/Download/ProtonDroid/rootfs.tar.gz` 读**（`PUBLIC_ROOTFS_TAR`），没有下载路径 | 需要「干净系统打包 → 发 Release → App 下载」整条链路 |

### 12.2 发布侧（打包干净系统 + 编译产物）

1. **干净 rootfs**：不要直接用设备备份（会带 `/opt/proton`、apt 缓存、游戏残留、proot-distro 痕迹）。
   推荐 `debootstrap --arch=arm64 --variant=minbase trixie <dir> <mirror>`（或容器内等价流程），
   只装 Proton 运行必需的依赖，最后 `tar -czf rootfs.tar.gz -C <dir> .`（扁平结构，正好被
   `ProtonLayout.resolveGuestRoot()` 识别；同时保留对 `debian/rootfs/...` 嵌套结构的兼容）。
2. **Proton 产物**：沿用 `scripts/cloud_build_proton_arm64.sh`（SteamRT4 SDK + `--target-arch=arm64` + `make redist`），
   打包成 `proton-droid-arm64-<版本>.tar.gz`，**顶层就是 `files/...`**（解包到 `/opt/proton` 后得到
   `/opt/proton/files/bin-arm64/wine`）。
3. **发布**：两者都挂到 GitHub Release（tag 建议 `runtime-<日期>`），同时上传 `SHA256SUMS`。
   Proton 是 Valve 的产物，分发前先确认许可（当前 `URL_PROTON_CORE` 指向 `AMM2034567/Proton-droid` 的 release，
   正式发布时补齐 LICENSE/来源说明）。

### 12.3 App 侧（按 manifest 下载）

建议新增 `runtime-manifest.json`（放 Release 资产或 App 内置默认值 + 远端覆盖）：

```json
{
  "manifestVersion": 1,
  "rootfs": {
    "version": "trixie-minbase-2026.10",
    "url": "https://github.com/<owner>/<repo>/releases/download/runtime-20261003/rootfs.tar.gz",
    "mirrors": ["https://.../rootfs.tar.gz"],
    "sha256": "…", "size": 771000000, "layout": "flat"
  },
  "proton": {
    "version": "11.0-100-arm64",
    "url": "…/proton-droid-arm64-<版本>.tar.gz",
    "sha256": "…", "size": 589000000
  },
  "prootToolchain": { "version": "5.1.107.96", "bundled": true }
}
```

实现要点（对应 `ProtonRuntimeInstaller`）：

- **下载**：`downloadWithProgress()` 扩展成「多镜像回退 + 断点续传（HTTP Range）+ 超时重试」；
  先下载到 `files/downloads/*.part`，完成后校验再改名。
- **校验**：SHA256（可用 `java.security.MessageDigest` 流式计算）+ 解包后结构校验
  （复用 `ProtonLayout.resolveGuestRoot()` / `isRuntimeInstalled()`）。
- **空间预检**：解包后 rootfs ~2.4GB + Proton ~1.x GB，下载前用 `StatFs` 检查剩余空间，
  失败给明确提示（当前 `files/rootfs` 实测 2.4GB）。
- **更新流程**：manifest 版本变化 → 只重下变化的组件；`games/` 是 `/sdcard` 上的用户数据不受影响；
  wine prefix（`root/.proton_droid_pfx`）默认保留，提供「重置 prefix」按钮。
- **可观测**：下载/解包各阶段写状态总线（`ProtonStatus`）与 `proton-stdout.log`，失败时报 URL/HTTP 码/校验结果。
- **降级**：保留 `/sdcard/Download/ProtonDroid/` 的本地文件优先逻辑（离线装机、内网分发）。

### 12.4 与显示层 B/C 的关系

- 显示层 B（内嵌 Xlorie）也会引入新的二进制资产（`libXlorie.so`）与 Java 胶水，
  建议一并纳入 manifest 的版本管理（虽然它更适合直接内置进 APK）。
- 12.2 的 Proton 重建流程（SteamRT4 SDK 容器）与 C 方案（`wineandroid.drv`）是同一套构建体系，
  后续应把「是否带 android 驱动」做成构建参数，而不是两套脚本。

