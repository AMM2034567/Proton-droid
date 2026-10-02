# Proton-droid 项目记忆（Project Memory）

> 最后更新：2026-10-03 会话收尾
> 状态：**「游戏无法启动」已修复并在真机逐层验证**；显示层方案已定 **B（内嵌 Xlorie）**，后续演进到 **C（wineandroid.drv）**。
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

## 11. 下次开工 TODO（B 方案）

1. 拉取 `termux/termux-x11` 源码，梳理 `LorieView` / `CmdEntryPoint` 与 native 的 JNI 契约（方法签名、回调、启动参数、环境变量）。
2. 把 `libXlorie.so` 以 `jniLibs` 形式引入（`packages/app` 侧 `System.loadLibrary`），并确认在 `untrusted_app_27` 域下能 `dlopen` + 建 Surface。
3. 实现最小 Java 胶水：Surface 提供、输入注入（touch→X 事件）、生命周期；先不做剪贴板。
4. `ProtonProcessManager.launchGame` 前启动 X 服务器；把 `checkX11Display` 从「提示」升级为「硬前置条件」。
5. 验证链：guest `xwininfo -root -tree` → `wine explorer /desktop=ProtonDroid,1280x720 <game.exe>` → SurfaceView 出现画面。
6. 记录性能基线（osu!/goose 帧率、CPU/GPU 占用），为 C 方案做对比依据。
