# Proton-droid 项目记忆（Project Memory）

> 最后更新：2026-10-04（W0 打通 Vulkan 呈现；W0.5 卡在 DXVK 黑屏，见 §14.8；接力提示词见第 15 节）
> 状态：**B 方案（内嵌 libXlorie）真机验证通过** —— 显示链路全通，GooseDesktop 活跃渲染；
> **W0 ✅**：`-disable-dri3` 后 lavapipe + vkcube 出画（30 FPS）；
> **W0.5 🚧 卡点**：DXVK 初始化/3000 次 Present 全成功、服务器有 damage，但可见窗口纯黑（§14.8）；
> **W1 待做**：真 GPU 的 WSI（vulkan-wsi-layer / ARM DDK glibc libmali）。
> 终态演进到 **C（wineandroid.drv）**。
> 正式版方向：**App 内置下载 rootfs 与编译好的 Proton 产物**（产物走自己打包 → GitHub Release），见第 12 节。
> 本文件是下次开工的第一入口；改动运行链路的代码前请先读第 2 节「铁律」。
>
> ⚠️ **维护约定：做较大改动后必须及时回写本文件**（判定标准与更新清单见第 13 节）。

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

# g) 内嵌 X 服务器（B 方案）是否起来 —— 应看到 X 服务器启动 + 连接成功，而不是 "No X11 server reachable"
& $adb logcat -d -v brief | Select-String "XServer|LorieView|No X11 server reachable|xwininfo"
#    期望：XServer: X server started on :0 (app-side fd=…)  → LorieView: connected to X server (fd=…)
#          → X screen size -> 1288x720 之类（宽度按 8 对齐）
#    若仍出现 "No X11 server reachable" 说明 libXlorie 没加载成功或服务器没监听

# h) 从 guest 内部验证 X 可达（最硬的一条）
#    起好 App 进入游戏视窗后：
& $adb shell "run-as com.protondroid sh -c 'cd files && env PROOT_LOADER=\$PWD/bin/loader PROOT_TMP_DIR=\$PWD/tmp LD_LIBRARY_PATH=\$PWD/bin ./bin/proot -r \$PWD/rootfs/debian/rootfs -0 -b /sdcard:/sdcard /usr/bin/env DISPLAY=:0 PATH=/usr/bin:/bin xwininfo -root -tree'"
#    期望：打印出 root 窗口树（尺寸 = sendWindowChange 下发的 X 屏幕尺寸）
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
| `65ea7a8` | **B 方案第一步**：内嵌 X 服务器（libXlorie）+ 最小 Java 胶水 + `XServer` 管理器 + `LorieView` 承载显示 |
| 本次（见下） | B 方案真机打通：TMPDIR/XKB 硬前提、socket 路径与 `/tmp` bind、`setViewport` force 重发（修黑屏）、内置 xkb 资产 |

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

因此 B 的实现路径（进度：✅ 已完成并**真机验证** / ⬜ 未开始）：

1. ✅ 从 `termux/termux-x11` 上游移植 Java 胶水（保持 FQCN 与 native 方法签名一致），
   去掉所有 Termux 文件系统/前缀假设 —— 已产出最小版
   `app/src/main/java/com/termux/x11/{CmdEntryPoint,LorieView,MainActivity}.java`。
2. ✅ `libXlorie.so` 已放进 `jniLibs/arm64-v8a/`（预编译二进制，SHA256 `C5AE6A56…`，
   来源 `com.termux.x11` 1.03.01-0e1ebb4-01.10.26）；`com.protondroid.display.XServer`
   负责同进程启动 + 准备 TMPDIR/XKB + 等待 socket 可连接 + 暴露控制通道 fd。
3. ✅ X socket 实际是**文件系统 socket**：`$TMPDIR/.X11-unix/X<n>`（TMPDIR 由我们指定为
   App 私有 `files/tmp`）。启动 guest 时 `-b <filesDir>/tmp:/tmp`，guest 内 wine 便能按
   标准路径 `/tmp/.X11-unix/X0` 连上。
4. ✅ `GameViewActivity` 先起 X 服务器再拉起 Proton；`ProtonProcessManager` 里
   `checkX11Display` 改为 `XServer.isDisplayReachable()` 并作为**硬前置**。
5. ✅ 真机验证通过（2026-10-04）：

```
I/XServer : X server started on :0 (app fd=155, socket=/data/user/0/com.protondroid/files/tmp/.X11-unix/X0)
I/LorieView: nativeInit -> 491922368384
I/LorieView: connected to X server (fd=155)
D/gles-renderer: Xlorie: Initialized EGL version 1.4 / new surface applied 2294x1080
I/LorieView: X screen size -> 2288x1080 (view 2294x1080)      ← 按 8 对齐后下发成功
I/CmdEntryPoint: starting X server, args=:0 -ac -nolisten tcp
D/xkbcomp: The XKEYBOARD keymap compiler (xkbcomp) reports: …   ← 内置 xkb 生效

# guest 侧（run-as + proot，-b files/tmp:/tmp）：
xdpyinfo : dimensions: 2288x1080 pixels, depth 24
xwininfo -root -tree:
  0x400006 "ProtonDroid - Wine Desktop": ("steam_proton") 1280x720+0+0
     └─ 10 个子窗口（Input / Default IME / …）                 ← wine 真的连上并建窗
截图：SurfaceView 里能看到 X 光标（根窗口为黑），说明 EGL 合成通路已通
```

**libXlorie 的 JNI 契约（必须完全对齐；来自 `activity.cpp` / `cmdentrypoint.cpp` 的 RegisterNatives）**

| 类 | native 方法（名字 + 签名不可改） | native 会调用的 Java 成员 |
| --- | --- | --- |
| `com.termux.x11.CmdEntryPoint` | `start([Ljava/lang/String;)Z`、`getXConnection()Landroid/os/ParcelFileDescriptor;`、`getLogcatOutput()`、`reportFatalError(Ljava/lang/String;)V`、**`@CriticalNative static connected()Z`** | `sendBroadcast()V`、`sendBroadcastDelayed()V` |
| `com.termux.x11.LorieView` | `nativeInit()J`、`nativeDestroy(J)V`、`surfaceChanged(JLandroid/view/Surface;)V`、`setViewport(JIIIIIII)V`、`sendWindowChange(JIIILjava/lang/String;)V`、`sendMouseEvent(JFFIZZ)V`、`sendTouchEvent(JIIII)V`、`sendKeyEvent(JIIZ)Z`、`connect(JI)V`(static)、**`@CriticalNative static connected(J)Z`**、`sendClipboardEvent(J[B)V`、`sendTextEvent(J[B)V`、`requestConnection(J)Z` 等 | 字段 `activity:Lcom/termux/x11/MainActivity;`；方法 `setRendererViewport(IIIIFFFF)V`、`setClipboardText(Ljava/lang/String;)V`、`requestClipboard()V`、`onSyncReply(I)V`、`resetIme()V` |
| `com.termux.x11.MainActivity` | —— | `clientConnectedStateChanged()V`（`nativeInit` 里 `FindClass`，**类不存在会致命退出**） |

踩坑记录（真机逐个踩过）：
- `@CriticalNative` 方法**没有 JNIEnv/jclass 参数**，漏标 → ABI 错位崩溃；
  但 `dalvik.annotation.optimization.CriticalNative` 在 `android.jar` 里可直接引用，
  **不要自建 stub**（自建会报"程序包已存在于另一模块: java.base"）。
- 布局 XML 引用 `LorieView` 需要 `(Context, AttributeSet)` 构造，否则膨胀时崩溃。
- `SurfaceHolder.addCallback` 在 `SurfaceView` 上是单一回调实现，别在 Activity 里再注册一个；
  用自定义 `setSurfaceReadyListener(Runnable)`。
- **native `start()` 有两个硬前提**（缺任一个直接 `return JNI_FALSE`）：
  1. `TMPDIR` 必须存在且可写（X socket 与 lock 都在 `$TMPDIR` 下，路径 = `$TMPDIR/.X11-unix/X<n>`）；
     Android 既无 `/tmp` 也无 Termux 的 tmp，所以必须在 App 进程里
     `Os.setenv("TMPDIR", filesDir/tmp, true)`，并把该目录 bind 到 guest 的 `/tmp`。
  2. `XKB_CONFIG_ROOT` 必须指向**存在**的 xkb 目录（`xkeyboard-config-2` 或 `X11/xkb`）；
     guest 里默认没有 → 随 APK 内置 `assets/xkb/xkb.tar`（来自 Termux `xkeyboard-config_2.48-1_all.deb`，
     318 项）首次启动解压到 `files/xkeyboard-config-2`。
  另外启动前要删掉残留的 `$TMPDIR/.X0-lock`，否则会 "Server is already active for display 0"。
- **AAPT 会把 `.gz` 资源自动解压并去掉扩展名**：原本打包的 `assets/xkb/xkb.tar.gz` 在 APK 里变成
  `assets/xkb/xkb.tar`（3.58MB 裸 tar），按原名 `open()` 直接 FileNotFoundException。
  结论：asset 不要用 `.gz` 后缀，直接放裸 tar + `toybox tar -xf`。
- **`Renderer::setWindow()` 会把 `expectedW/H` 清零**（renderer.cpp:606），而每次
  `surfaceChanged(ptr, surface)` 都会走这条路 → **每次换 Surface 之后必须重新
  `setViewport()`**（我们加了 `force` 参数），否则持续报
  `Buffer N is not of expected size, expecting 0x0 or 0x0, got 2288x1080` 并丢弃所有帧，画面全黑。
- X 屏幕尺寸经 `sendWindowChange` 下发，宽度按 libxcvt 的 **8 像素粒度向下对齐**
  （2288 = 2294 对齐结果；1366x768 是特例，上游对 1360x768 特判成 1366）。
- 进程内启动是可行的：不需要 `app_process`/Binder/广播，但**必须在有 Looper 的线程**调用
  （native 里用 `AChoreographer_getInstance()`）。

风险/待办：**GPL-3.0 合规**（Xorg 派生 + 预编译二进制，见 `docs/THIRD_PARTY_NOTICES.md`）；
**D3D11/DXVK 还需要 Vulkan WSI 桥**（把 Win32 Vulkan swapchain 接到 ANativeWindow，或提供 X11 present 桥）。

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

内置显示组件（B 方案）：

| 文件 | 来源 | SHA-256（前 12） |
| --- | --- | --- |
| `libXlorie.so`（3,550,768 B） | 设备上 `com.termux.x11` APK（`1.03.01-0e1ebb4-01.10.26`, versionCode 15）的 `lib/arm64-v8a/`，源码 [termux/termux-x11](https://github.com/termux/termux-x11) | `C5AE6A56CA6D` |

> 上游源码参考已拉到 `cache/refs/termux-x11/`（gitignore，不入库）；关键文件：
> `lorie/src/main/java/com/termux/x11/{CmdEntryPoint,LorieView,MainActivity}.java`、
> `lorie/src/main/cpp/lorie/{activity.cpp,cmdentrypoint.cpp}`。

推送命令（需代理；token 不落盘）：

```powershell
$tok = (& 'C:\Program Files\GitHub CLI\gh.exe' auth token).Trim()
$env:GIT_TERMINAL_PROMPT='0'
git -c credential.helper= -c http.proxy=http://127.0.0.1:10809 `
  push "https://x-access-token:$tok@github.com/AMM2034567/Proton-droid.git" HEAD:main
```

---

## 10. 已知坑 / 未解问题

1. **画面**：B 方案（内嵌 `libXlorie`）代码已落地并**构建通过**，但真机验证因 adb 设备掉线未完成
   （第 8.2 节进度表；下次接上设备先跑第 6 节 g/h 两步）。
2. **DXVK 的 Vulkan WSI**：Android 只有 `VK_KHR_android_surface`；走 X11 时需自建 WSI 桥（B 之后）。
3. **GPL 合规未落地**：`libXlorie.so` 为 GPLv3 预编译二进制，仓库尚无 LICENSE 全文与源码说明，
   见 `docs/THIRD_PARTY_NOTICES.md`。
4. **ColorOS 限制**：`adb shell pm grant` / `appops set` 均被拒；只能应用内弹窗授权。
   `am kill` 对有前台服务的 App 无效；`am crash` 会连子进程一起带走（cgroup/进程组回收）。
5. **run-as 的域是 `runas_app`**，App 是 `untrusted_app_27`，跨域读 `/proc/<pid>/status|exe` 会被拒 ——
   用 run-as 造「残留进程」来测清理会得到假阴性。
6. **PulseAudio 音频**：`PULSE_SERVER=127.0.0.1` 只是占位，App 内没有音频服务（未验证）。
7. `docs/ARCHITECTURE.md` 早期写的「ANativeWindow 直通渲染」不准确，已在文档中改成「显示依赖 X 服务器」。
8. **构建期**：本仓库构建需 `danger-full-access`（否则 CMake 卡在 *Detecting C compiler ABI info*）；
   本地构建前先 `.\gradlew.bat --stop` + 清 `app/build`、`app/.cxx`，否则偶发"无法删除目录"。

---

## 11. 下次开工 TODO（按优先级）

**P0 — B 方案（显示层）**

1. ✅ 拉取 `termux/termux-x11` 源码，梳理 JNI 契约（结论见 §8.2 表格）——源码在 `cache/refs/termux-x11/`。
2. ✅ `libXlorie.so` 以 `jniLibs` 引入 + `System.loadLibrary`；`XServer.ensureStarted()` 负责同进程启动。
3. ✅ 最小 Java 胶水：`CmdEntryPoint` / `LorieView`（Surface + 触摸/滚轮/键盘输入）/ `MainActivity` 桩。
4. ✅ `GameViewActivity` 先起 X 服务器再拉起 Proton；X 可达性已是硬前置。
5. ✅ **真机验证完成**：X 服务器启动 → wine 建出 `ProtonDroid - Wine Desktop` 窗口（窗口树证据）
   → SurfaceView 出画面（X 光标可见）。细节见 §8.2 第 5 条的实测日志。
6. 🔄 **当前卡点：游戏进程立即退出（退出码 0）**。wine 与 X 都正常，是应用层问题。
   **2026-10-04 进展：已定位为游戏自身的更新器 + 网络问题，并已成功跑起 osu!**
   - 证据：打开 `WINEDEBUG=err+all,warn+all` 后，wine 的 X11 驱动完全正常
     （`X11DRV_InitKeyboard` / `xrandr` / `x11drv:get_work_area`），仅 4 条无害错误；
     osu! 自己的 `Logs/update.log` 显示它在 `Requesting update information...` 卡住，
     约 10 分钟后 `Force update requested` 然后退出（此前一次是 `NameResolutionFailure`）。
   - guest 网络实测正常：`osu.ppy.sh` DNS 解析 ✓、TCP 443 ✓、HTTPS 返回 200（RTT ~5s，较慢）。
   - **网络恢复后 osu! 成功启动并渲染**：进程树出现 `osu!.exe`，窗口树有
     `ProtonDroid - Wine Desktop`（蓝色 = wine 默认桌面底色）+ 鼠标指针，
     真机截图上能看到 **osu! 启动 logo**。即整条链路（PRoot → Proton → wine X11 → 内嵌 X 服务器 → EGL → SurfaceView）已通电。
   - 结论：早期"立即退出"是更新器在无网/慢网下放弃导致的，不是运行时缺陷。
   - 调试开关：往 `files/wine_debug.txt` 写 `err+all,warn+all` 即可改变 WINEDEBUG（免重编）；
     往 `files/extra_env.txt` 写 `KEY=VALUE`（每行一条）可追加任意 guest 环境变量
     （如 `MONO_LOG_MASK=socket`、`FEX_*`），同样免重编。
6. ✅ **进一步定位（同日）**：
   a. **osu! 卡在更新器的 .NET 层，不是网络**：抓 `WINEDEBUG=+winsock` 只见 `localhost` 解析，
      游戏进程对 `osu.ppy.sh` 一次 socket 都没开；`update.log` 停在 `Requesting update information...`
      约 4~10 分钟后写 `Force update requested` 并退出。而 guest 侧网络全部正常
      （`osu.ppy.sh` A/AAAA 记录、TCP 443、HTTPS 200、DoH 交叉验证过域名有效性；
      `dl.osu.ppy.sh`/`api.osu.ppy.sh`/`c1.ppy.sh` 是 NXDOMAIN，不存在的域名）。
   b. 期间修掉三个**真实环境缺陷**（都留在 guest 里，正式版应打进 rootfs）：
      - 缺 `libgnutls.so.30` → wine 完全无 TLS（`winediag:gnutls_process_attach failed to load libgnutls,
        no support for encryption`）→ 装 `libgnutls30`（apt 解包；dpkg 收尾会报
        `/var/lib/dpkg/status-old: Permission denied`，库已就位，不影响使用）
      - 缺 `libgcrypt.so.20` → wine 的 ECC/ECDHE 不可用（`gnutls_ecdh_compute_key not found` +
        `failed to load gcrypt`）→ 手工从 Debian 包解出 `libgcrypt.so.20.2.8` 与
        `libgpg-error.so.0.42.1` 放进 `usr/lib/aarch64-linux-gnu/`（ldd 0 缺失）
      - guest `resolv.conf` 只有 8.8.8.8/8.8.4.4（国内间歇性解析失败）→ 改为
        `192.168.6.1 / 114.114.114.114 / 223.5.5.5 / 8.8.8.8`
        （写 resolv.conf 别用 heredoc：Android mksh 的 heredoc 需要临时文件会 Permission denied，
        用 `echo >` / `echo >>`）
   c. 曾观察到一次 **FEX WOW64 崩溃**：`EXCEPTION_ACCESS_VIOLATION c0000005`，
      回溯 `libwow64fex.dll+0x3650` / `wow64.dll+0x1C85C`；补齐 TLS 库后未再复现。需持续观察。
   d. ✅ **成功跑起真实 .NET 游戏**：`GooseDesktop.exe`（Desktop Goose，无更新器）进程稳定、
      建出 1280x720 窗口、20 秒间隔两次截图哈希不同、`LorieNative` 稳定 6~9 FPS → 活跃渲染。
      这是 B 方案「游戏真在 App 内跑并出画面」的完整证据（此前 osu! 启动 logo 是第一次出画面）。
   e. 已知限制：**没有合成器（compositor）**，透明/分层窗口按不透明呈现
      （GooseDesktop 的透明覆盖窗显示为白/蓝块）；音频 `mmdevapi` 无后端
      （`pulse,alsa,oss,coreaudio` 全部加载失败）→ 归入 P3。
7. ✅ **性能基线（2026-10-04 首次测量，探针已固化）** —— 结论：**低帧率不是显示层造成的**。
   探针：`cache/xfps_probe.py` + `cache/run_xfps.sh`（guest 内 python3 + ctypes 打 libX11，
   `XFillRectangle` 整窗填充 + 每帧 `XSync`，含客户端往返）。
   实测（内嵌 libXlorie，X 屏 2288x1080）：

   | 窗口尺寸 | 帧率 | 吞吐 |
   | --- | --- | --- |
   | 320x180 | 597 fps | 34 Mpx/s |
   | 1280x720（wine 桌面尺寸） | **287 fps** | 264 Mpx/s |
   | 2288x1080（满屏） | **119 fps** | 295 Mpx/s |

   注意：该测量本身是在 guest 里经 proot（ptrace 拦截）发出的，所以**服务端真实上限还要更高**；
   吞吐随像素线性 → 呈现路径是像素受限（CPU 光栅化 + 零拷贝 AHardwareBuffer 呈现），不是同步/唤醒受限。
   同刻对照：App 进程（X 服务器 + EGL 合成）各线程 **~3% CPU**；`GooseDesktop.exe` **36.6%**；
   `proot` **55.1%**（ptrace 税）→ 当时 3~5 FPS 的瓶颈在 **guest 执行栈（wine/FEX/mono + proot）**，
   而不是显示层。
   **推论**：B 方案下 2D/桌面类负载很宽裕；3D 游戏的帧率上限由 FEX 执行开销与「呈现拷贝」决定，
   性能优化的两个大头是 ①减少 proot 的 ptrace 开销（syscall 密集进程可达 50%+ CPU），
   ②C 方案（wineandroid.drv）的零拷贝直出。**Vulkan WSI 桥解决的是「能不能跑」，不是「跑多快」。**
   **下一批要做的**：**Vulkan WSI 桥 —— 方案已完整落在 §14（规划完成、未动工，含 ICD 候选、
   presenter 选择、W0/W1/W2 分阶段与验收标准）**；其余：osu! 更新器绕过、音频后端、
   将 libgnutls/libgcrypt × DNS 等环境修复固化进 rootfs 打包脚本。
8. ⬜ GPL 合规三件套（LICENSE + `licenses/` + App 内开源许可页），见 `docs/THIRD_PARTY_NOTICES.md`。

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

---

## 13. 记忆维护约定（重要）

**原则：做较大改动后，及时回写本文件 —— 不要等到“下次想起来”。**
本文件的价值在于「下次开工 5 分钟进入状态」，过期的记忆比没有记忆更危险。

### 13.1 什么算「较大改动」（命中任意一条就要回写）

- 运行链路/启动参数/环境变量变化（第 2、3 节）
- 目录布局、guest root 结构、prefix 位置变化（第 4 节 + `ProtonLayout`）
- 构建与部署方式变化（Gradle/AGP/SDK/NDK/签名/CI 工作流/沙箱要求，第 5 节）
- 新增或替换内置二进制/依赖（第 9 节的来源与校验和表）
- 显示层架构变化（第 8 节的方案、`libXlorie`、WSI、C 方案进展）
- 正式版下载/发布链路推进（第 12 节：manifest、打包脚本、Release 规范）
- **推翻或修正了旧结论**、或踩到新的坑（第 2、10 节）—— 这类尤其要写，否则下次会重踩
- 完成/调整 TODO 优先级（第 11 节）

### 13.2 回写清单（按命中项更新）

1. 头部「最后更新 / 状态」两行（含方案是否变化）
2. 对应技术小节（铁律 / 链路 / 代码地图 / 环境事实 / 已知坑）
3. 第 7 节补一条「提交 → 症状 → 证据」记录
4. 第 11 节 TODO：勾掉完成项、插入新发现的问题并重排优先级
5. 真机验证命令若发生变化，同步第 6 节（保证回归手册始终可照抄执行）

### 13.3 同时要更新的邻居文档

- `docs/GETTING_STARTED.md`：用户可感知的流程变化（安装、权限、构建方式）
- `docs/ARCHITECTURE.md`：架构图与原理（显示层、渲染路径、PRoot/guest 结构）
- `.github/workflows/*`：若 CI 触发条件或产物命名变了，记忆第 5.2 节要跟着改

### 13.4 提交信息里留痕

较大改动的 commit message 末尾加一行：`Memory: 已更新 docs/PROJECT_MEMORY.md §X`（或说明为何无需更新），
便于回溯「哪次改动改了记忆、哪次漏了」。

---

## 14. Vulkan WSI 桥（W0 已完成 ✅；W1 待做；**W0.5 卡点见 §14.8**）

> 状态：**仅规划，未写任何代码**（用户 2026-10-04 明确要求先落记忆）。
> 这一节是 3D 游戏（D3D9/10/11/12 → DXVK/VKD3D → Vulkan）能不能跑起来的**唯一硬缺口**。

### 14.1 问题陈述与现场证据

B 方案把 X11 显示链路打通了（§8.2），但 **D3D→Vulkan 的呈现链路不通**。真机日志（wine vulkan 初始化）：

```text
00cc:warn:vulkan:vulkan_init_once Extension "VK_KHR_xcb_surface" is not supported.     ← 实际截取到的原话
00cc:warn:vulkan:vulkan_init_once Extension "VK_KHR_display" is not supported.
00cc:warn:vulkan:vulkan_init_once Extension "VK_EXT_acquire_xlib_display" is not supported.
00cc:warn:vulkan:init_physical_device Extension "VK_ANDROID_external_memory_android_hardware_buffer" is not supported.
00cc:warn:wgl:egl_init EGL support is disabled.
```
（`VK_KHR_xlib_surface` 属于同族 WSI 扩展；我们的日志截取里逐条列出的是上面这些，写代码前用
`vulkaninfo | grep -i surface` 再确认一次设备实际支持的 surface 扩展面。）

机制：DXVK 不认识 Win32 窗口，它依赖 winevulkan 把 HWND 映射成 **X11 surface**（`VK_KHR_xcb_surface`/`VK_KHR_xlib_surface`），
再 `vkCreateSwapchainKHR` 呈现。而 Android 上的 Vulkan ICD（Mali 厂商驱动）只提供
**`VK_KHR_android_surface`**（面向 `ANativeWindow`），既没有 XCB/XLIB，也不暴露
`VK_ANDROID_external_memory_android_hardware_buffer` → swapchain 建不出来。

同时，我们的内嵌 X 服务器（libXlorie）**不是** DRM/DRI3 的 GPU 合成器：它的后端是 gralloc/AHardwareBuffer 共享内存，
不能充当「DRM render node 的代理」。所以桥必须做在 **Vulkan 这一层**，而不是 X 服务器里。

### 14.2 前置问题：guest 需要一个「glibc 可用的 Vulkan ICD」

真机 recon（2026-10-04）：

| 位置 | 内容 | 对 guest 可用性 |
| --- | --- | --- |
| host `/system/lib64/libvulkan.so` | Android Vulkan loader | ✗ bionic |
| host `/vendor/lib64/hw/vulkan.mali.so` → `mt6895/vulkan.mali.so` | MT6895（天玑 8100）Mali 厂商 ICD | ✗ bionic（双 libc 问题） |
| guest `/usr/lib/aarch64-linux-gnu/libvulkan.so.1`（1.4.309） | Mesa Vulkan loader（glibc） | ✅ |
| guest `/usr/share/vulkan/icd.d/` | `lvp`(**lavapipe**) `freedreno` `broadcom` `gfxstream` `nouveau` … | ✅ 现成可用 |
| guest 内嵌 X 服务器扩展（`xdpyinfo -display :0`） | **MIT-SHM、DRI3、Present**、Composite、DAMAGE、DOUBLE-BUFFER、GLX、RANDR、SYNC、XFIXES、XInputExtension、XKEYBOARD…（共 23 个） | ✅ presenter 有落点 |

呈现预算（实测，§11 P0-7）：满屏 2288x1080 ≈ **119 fps**、1280x720 ≈ **287 fps**（≈295 Mpx/s）。
即 SHM presenter 每帧 ~9.9 MB 的 CPU 拷贝（60 fps ≈ 600 MB/s）相对这个量级**仍有余量，
present 不是第一瓶颈**；W1 的成败关键在 ICD 能否拿到真 GPU、以及 FEX/proot 的执行开销。

ICD 候选（**开工第一步就是在这张表里做选择**）：

| 方案 | 可行性 | 性能 | 主要风险 |
| --- | --- | --- | --- |
| **lavapipe**（Mesa 软件 Vulkan） | ✅ guest 已装，立刻可验证 | 极低（纯 CPU） | 无 —— 只用于打通/回归 DXVK 链路 |
| **ARM DDK 的 glibc `libmali`**（x11/gbm flavor） | 需与设备 `mali_kbase` 内核驱动 DDK 版本匹配（MT6895） | 高（真 GPU） | 版本匹配困难；DDK 分发许可 |
| Mesa **PanVK**（glibc） | ✗ 需 panfrost/panthor 内核驱动 | 中 | 原厂内核只有 `mali_kbase` |
| bionic Mali blob + glibc 桥 | 理论可行 | 高 | 双 libc（`libc`/`malloc`/`pthread` 符号冲突），工程量最大 |
| Zink/VirGL 转发（额外服务） | 需 GL 侧配合 | 低~中 | 多一个进程与拷贝，仍缺 Vulkan WSI |

### 14.3 桥的设计：直接用 Arm 的 `vulkan-wsi-layer`

**Arm `vulkan-wsi-layer`** 是一个 **Vulkan 隐含层（implicit layer）**，专门给「ICD 自己没有 WSI」的场景补上
X11/Wayland surface 与 swapchain —— 其 README 明确点名 **Mali 厂商驱动（无 DRM render node）** 就是目标场景之一。
它实现的接口正好覆盖 DXVK 的需求：

- instance：`VK_KHR_surface`、`VK_KHR_xcb_surface`/`VK_KHR_xlib_surface`、`VK_KHR_wayland_surface`、
  `VK_KHR_get_surface_capabilities2`、`VK_EXT_headless_surface`
- device：`VK_KHR_swapchain`、`VK_KHR_shared_presentable_image`、`VK_KHR_present_id`、`VK_EXT_swapchain_maintenance1`

presenter 与我们的匹配度（Sky1 fork 的三档路由）：

| presenter | 依赖 | 我们有吗 |
| --- | --- | --- |
| Wayland bypass（DMA-BUF 零拷贝） | Xwayland + Wayland 合成器 + `zwp_linux_dmabuf_v1` | ✗ 我们是纯 X |
| DRI3（XCB Present，COPY） | **guest 侧 DRM render node**（`/dev/dri/renderD*`）+ X 服务器 DRI3 | X 服务器 ✓ 有 DRI3，但 guest 侧无 render node ✗（待确认 Xlorie 的 DRI3 是否为标准 GEM 语义） |
| **SHM（MIT-SHM CPU 拷贝）** | XCB + MIT-SHM | ✅ **两个条件都满足 → 首选** |

⇒ **首选 SHM presenter**，装法：编译出 `libVkLayer_window_system_integration.so` + JSON，放进 guest 的
`/usr/share/vulkan/implicit_layer.d/`（loader 自动加载，用 `VK_LOADER_DEBUG=layer` 验证）；
必要时用 `WSI_NO_WAYLAND_BYPASS=1` 之类开关强制走 SHM。
SHM 的代价是每帧一次 CPU 拷贝（2288x1080 ≈ 9.9 MB/帧），AArch64 上可用 NEON 优化拷贝，
先要功能、后谈性能。

### 14.4 分阶段计划（建议顺序，含验收标准）

**W0 —— 功能基线 ✅ 已完成（2026-10-04，真机验证通过）**

结论：**Vulkan → X11 → 内嵌 X 服务器 → EGL → SurfaceView 整条链路已打通**（用 lavapipe 软件 Vulkan）。
真机证据（PGZ110）：

```
# 环境：X 服务器启动参数加 -disable-dri3；VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/lvp_icd.json
$ vkcube                      # 来自 Debian vulkan-tools 1.4.341，已装进 guest /usr/local/bin
Selected WSI platform: xcb
Selected GPU 0: llvmpipe (LLVM 19.1.7, 128 bits), type: Cpu
I/LorieNative: 2   frames in 5.0 seconds =  0.4 FPS     ← vkcube 起来前
I/LorieNative: 128 frames in 5.0 seconds = 25.6 FPS     ← vkcube 呈现中
I/LorieNative: 152 frames in 5.0 seconds = 30.4 FPS
# 窗口树：0x200000 "Vkcube X11" 500x500+100+100  ／ 截图：能看到旋转的 LUNARG 立方体，两帧不同
```

**决定性发现：内嵌 X 服务器必须禁用 DRI3（`-disable-dri3`）**。原因（源码实证）：
Xlorie 的 DRI3 是**单向残缺**的 ——
`static dri3_screen_info_rec lorieDri3Info = { .version = 2, .fds_from_pixmap = FalseNoop, .pixmap_from_fds = loriePixmapFromFds, ... }`
（**不能**导出自己的 pixmap，也没有标准 DRI3 `open`），而 Mesa 的 X11 Vulkan WSI
（`src/vulkan/wsi/wsi_common_x11.c`）**只要发现服务器有 DRI3 就会优先走 DRI3 present**
（`x11_present_to_x11_dri3`）→ present 永远到不了服务器：窗口全黑、零 damage、服务器帧率不涨。
表现极具迷惑性：vkcube **不报错**（1 秒能跑完 5 帧、长跑吃 119~184% CPU），只是画面不见。
排除过程（都失败，记录以免重踩）：`MESA_VK_WSI_DEBUG=noshm`（改不了分支走向）、
`LIBGL_DRI3_DISABLE=1`（那是 GLX 的开关）、`MESA_VK_WSI_PRESENT_MODE=immediate`、
加 Khronos 验证层（未加载成功）。
`-disable-dri3` 后 `xdpyinfo` 扩展从 23 降到 22（DRI3 消失，MIT-SHM/Present 仍在）→ Mesa 回落到
软件 present（PutImage/SHM）→ 立刻出画。
附加开关（同一段代码里，备用）：`-force-sysvshm`、`-disable-gpu-present`、`-legacy-drawing`。

**对 W1 的直接含义（重要）**：Mesa 的 X11 WSI 对**非 sw 设备**（真 GPU ICD）强制要求 DRI3
（`wsi_x11_check_for_dri3()`，sw 设备才豁免）⇒ 真 GPU 在 Mesa 路径下**两条路都不通**：
有 DRI3 → present 残缺；无 DRI3 → Mesa 直接判定不支持 present。
所以 **vulkan-wsi-layer（自带 presenter，不依赖 DRI3）或 C 方案是 GPU 路径的必需品**，
不是「可选优化」。

**W1 —— 真 GPU 的 WSI 桥（主攻）**
1. 先按 §14.2 定 ICD 来源（优先「ARM DDK glibc libmali」，否则评估 bionic 桥）；
2. `VK_ICD_FILENAMES=<icd.json>` 指定后，用 `vulkaninfo` 确认设备枚举与扩展面；
3. ICD 缺 X11 WSI → 编译并装 **vulkan-wsi-layer（SHM presenter）**；ICD 自带 X11 WSI → 直接跳过层；
   注意 §14.4 W0 的结论：**Mesa 自带的 X11 WSI 对非 sw 设备强制 DRI3，在 Xlorie 上必挂**，
   所以只要走 GPU，就基本一定要用 layer 自带的 presenter；
4. 验收：`vulkaninfo` 列出 Mali 设备 + `VK_KHR_xcb_surface`；DXVK 游戏出画面并记录帧率（回填 §11 P0-7）。

**W0.5 —— DXVK 冒烟测试（进行中，已发现两个真 bug）**

W0 只证明了「Vulkan 能出画」，还没证明 **DXVK**（D3D11→Vulkan）。测试程序见
`cache/dxvk_probe.c`（App 侧常驻回归资产，真机放在
`/sdcard/Download/ProtonDroid/games/dxvkprobe/dxvk_probe.exe`）；osu!stable 是 **OpenGL(OpenTK)**、
GooseDesktop 是 GDI/WinForms，都不经过 DXVK，所以必须自备。

**bug 1（已修）：DXVK 从未被启用。**
proton 发行包把 DXVK 放在 `files/lib/wine/dxvk/{aarch64,i386,x86_64}-windows/`，
但模板 prefix 的 `drive_c/windows/system32|syswow64/{d3d8,d3d9,d3d10core,d3d11,dxgi}.dll`
默认指向 **wine 内建**（`lib/wine/<arch>-windows/...`）。Proton 官方是在首次运行时由 `proton`
脚本把这些覆盖点指向 DXVK；我们**直接拉起 `wine`、跳过了那一步**，于是
`WINEDLLOVERRIDES=d3d11=n,b` 判定"找到的是内建 dll"→ DXVK 静默不加载（不会有任何 DXVK 日志）。
修复：`WinePrefix.installDxvk()`（幂等，每次启动调用，prefix 已存在也会纠正；把覆盖点
改成指向 `lib/wine/dxvk/<arch>-windows/<dll>` 的 guest 绝对路径符号链接）。

**bug 2：本 Proton 只支持 32 位 x86 模拟（FEX WOW64）。**
`lib/wine/aarch64-windows/` 里只有 `libwow64fex.dll`（+`wow64.dll`/`wow64win.dll`，`xtajit64.dll`
是 stub），**没有 64 位 x86 模拟器** → x86_64 PE 起不来（探针首次用 x86_64 编，
进程根本不出现，日志只有 `cannot find builtin library for xtajit64.dll`）。
所以 D3D/游戏测试程序都必须编成 **32 位（i686）**。工具链：`cache/toolchain/w64devkit`（2.10.0，
官方 `.7z.exe` 自解压；支持 `-m32`；编译时需把 `TEMP/TMP` 指到可写目录，否则
`Cannot create temporary file in %TEMP%`）。32 位探针发布后进程正常起来，证明 FEX WOW64 可用。

**当前测量结果（32 位探针，lavapipe，X 服务器 `-disable-dri3`）**：
- ✅ DXVK 初始化成功：日志有 `Creating sampler descriptor heap`、`Graphics pipeline libraries supported`、
  `Presenter: Actual swapchain properties: VK_FORMAT_B8G8R8A8_UNORM / VK_PRESENT_MODE_IMMEDIATE_KHR /
  Buffer size 632x453 / Image count 4`（`fixme:vulkan:allocate_external_host_memory Using VK_EXT_external_memory_host`）
- ✅ 3000 次 `IDXGISwapChain::Present` 全部返回成功（探针自己的 `dxvk_probe.log`：`present=3000`）
- ✅ present 期间 **X 服务器帧率升到 24~27 FPS**（说明 damage 确实产生了）
- ❌ **但可见窗口内容仍是纯黑**：像素级验证窗口区域 `distinct=1`、100% `(0,0,0)`；
  同一区域在 vkcube 那张截图里有 3107 种颜色。连拍两帧差分只有鼠标光标（17x22 px）在变。
- ⇒ 结论：DXVK 的 present **到达了服务器（有 damage）但没有改到可见窗口**。
  下一步排查方向：`WINEDEBUG=+vulkan,+x11drv` 看 winevulkan 把 swapchain 的 xcb surface
  挂到了哪个 X 窗口（怀疑不是可见的那个客户窗口，或走了 wine 的共享内存/DIB 路径）；
  对照实验：X 服务器加 `-force-sysvshm`、`-legacy-drawing`、`-disable-gpu-present`；
  以及 `dxvk.conf` 里 `dxvk.numCompilerThreads`/`dxvk.tearFree` 之类无关项要排除。
  这条不通，W1 的 vulkan-wsi-layer 也救不了 DXVK —— 它和 W0 的 vkcube 是两个不同的 present 入口。

**W2 —— 终态（C 方案，见 §8.3）**
`wineandroid.drv` 让 winevulkan 直接拿 `ANativeWindow` → `vkCreateAndroidSurfaceKHR`，
把 X 与 WSI 层一起取消，并顺带解决「CPU 拷贝 presenter」的带宽问题。

### 14.5 改动落点

- **W0/W1 不需要改 wine 本体**：全靠 guest 内的 loader + ICD + layer 三者搭配，
  App 侧只在环境变量里给 `VK_ICD_FILENAMES` / `VK_LAYER_PATH`（`files/extra_env.txt` 即可）。
- 真正需要改 App/gradle 的只有：**把选定 ICD/层作为运行时资产打包**（走 §12 的 manifest 链路更好）。
- 若 SHM 拷贝成瓶颈：要么做 C 方案（wineandroid.drv，直接呈现到 SurfaceView），
  要么给 Xlorie 补「DRI3 → gralloc」的零拷贝路径（大改，需评估 Termux-X11 上游进展，
  例如 [termux-x11#979](https://github.com/termux/termux-x11/issues/979) 里 X server ↔ Android Surface 的同步/帧调度讨论）。

### 14.6 风险与开放问题（开工前先确认）

1. **双 libc**：bionic 的 Mali blob 能否在 glibc guest 进程里 dlopen（先看 `libmali` 的 NEEDED 与符号），
   这个决定 §14.2 里两条高成本路线的取舍。
2. **Xlorie 的 DRI3 语义**：是标准 DRM GEM handle 还是 gralloc fd？决定能否用 DRI3 presenter 走零拷贝。
3. **lavapipe 性能**：2288x1080 下能否跑通 DXVK（可能只有个位数帧率，仅作功能验证）。
4. **FEX + Vulkan 交互**：我们已经见过一次 FEX WOW64 `c0000005`（§11 P0-6c），GPU 路径是否更不稳需要观察。
5. **许可**：`vulkan-wsi-layer` 是 MIT ✓；ARM DDK Mali 用户态 blob 的分发许可必须确认
   （并入 `docs/THIRD_PARTY_NOTICES.md`）。

### 14.7 参考资料

- Arm 上游：<https://gitlab.freedesktop.org/mesa/vulkan-wsi-layer>
- ginkage fork（补 X11 MIT-SHM）：<https://github.com/ginkage/vulkan-wsi-layer>
- Sky1 fork（多 presenter、Mali 场景、`WSI_NO_WAYLAND_BYPASS` 等开关）：<https://github.com/Sky1-Linux/vulkan-wsi-layer>
- Winlator 内部机制（Turnip/bionic 路线，作为对照）：<https://github.com/leegao/winlator-internals>
- Termux-X11 X server ↔ Surface 同步/帧调度：<https://github.com/termux/termux-x11/issues/979>
- Vulkan WSI 规范：<https://docs.vulkan.org/spec/latest/chapters/VK_KHR_surface/wsi.html>

### 14.8 **DXVK present 黑屏卡点（当前唯一卡点；下一会话从这一节开始）**

#### 事实（全部已在真机量过，勿重复验证）

| 观测 | 证据 |
| --- | --- |
| DXVK 初始化成功 | `Creating sampler descriptor heap`、`Graphics pipeline libraries supported`、`Presenter: Actual swapchain properties: VK_FORMAT_B8G8R8A8_UNORM / VK_PRESENT_MODE_IMMEDIATE_KHR / Buffer size 632x453 / Image count 4` |
| Present 全部成功 | 探针自己的 `dxvk_probe.log`：`present=3000`、`done, presented=3000` |
| X 服务器确实收到 damage | present 期间 `LorieNative` 帧率 0.4 → **24~27 FPS** |
| **可见窗口没变** | 窗口区域像素 `distinct=1`、100% `(0,0,0)`；连拍两帧差分只有 17x22 px 的鼠标光标 |
| 同一 X 服务器下 vkcube 正常 | 同区域 vkcube 截图有 **3107** 种颜色（旋转立方体） |

核心矛盾：**同 ICD、同 X 服务器、同尺寸窗口，vkcube 的 present 出画，DXVK 的 present 不出画，但两者都在服务器上产生了帧。**

#### 关键认知：两者不是同一条 present 路径

- **vkcube（Linux 原生）**：`vkCreateXcbSurfaceKHR(自己创建的 X 窗口)` → 宿主 ICD 的 WSI（Mesa `wsi_common_x11`）→ 直接往那个 **X 窗口**写像素。窗口即 X 窗口 ⇒ ✅ 可见。
- **DXVK（Windows PE）**：只会 `vkCreateWin32SurfaceKHR`（窗口是 **HWND**）→ winevulkan 必须翻译成宿主表面，两条路：
  (a) 宿主 ICD 支持 `VK_KHR_xcb_surface`，wine 把 HWND 映射成某个 X 窗口 id；
  (b) 共享内存 + `VK_EXT_external_memory_host`，app 渲染进 wine 自己的内存，再由 wine 用普通 X 请求刷进窗口。
  日志里 `fixme:vulkan:allocate_external_host_memory Using VK_EXT_external_memory_host` 就是 (b) 的指纹。
  **(a)(b) 把像素写进的 drawable 不同，Xlorie 能否"看见"也就不同。**

#### Xlorie 侧的三条硬约束（源码实证，`lorie/src/main/cpp/lorie/InitOutput.c`）

1. 每个 pixmap 都是 `LorieBuffer`（AHardwareBuffer/FD），窗口有自己的 window pixmap；
   **只有 root（屏幕）的 damage 会被合成成一帧推给 App** ⇒ "有帧" ≠ "该窗口内容变了"。
2. `loriePresentFlip()` 在这些情况下直接 `return FALSE`：
   - `!priv || !priv->buffer || priv->mem`（pixmap 是普通内存）
   - root 尺寸 ≠ pixmap 尺寸
   - `desc->type == LORIEBUFFER_FD && priv->imported && TERMUX_X11_FORCE_FLIP != 1`
     （注释原话：*"For some reason it does not work fine with turnip."*）
3. `lorieTryScheduleGpuCopy()` 在 `gpuPresentDisabled || legacyDrawing` 时放弃、回落 CPU 拷贝；
   DRI3 单向残缺：`pixmap_from_fds` ✓ 能导入、`fds_from_pixmap = FalseNoop` ✗、无标准 `DRI3Open`。

#### 三个仍在竞争的解释（含判别实验）

- **H1 · present 写进了"另一个" X 窗口**（wine 的 whole-window / 隐藏窗口 / offscreen pixmap）：
  damage 记在别的 drawable 上，可见窗口自然不动。**最便宜、最可能一击命中。**
- **H2 · 像素停在 wine 客户内存里，刷窗那步没触发**（`WM_PAINT`/`X11DRV` flush 在虚拟桌面拓扑下没走到）：
  日志里那条 external-memory fixme 支持这个方向。
- **H3 · flip 被 Xlorie 拒绝**，回落的拷贝路径对"导入缓冲"也没落地。
- **元凶候选（必须先排除）**：`-disable-dri3` 是为了让 vkcube/Mesa WSI 出画才加的；
  而 **wine 的 win32-surface + Present 很可能正需要 DRI3 的 fd 导入/flip** ⇒ 两个消费者可能要**不同配置**。

#### 诊断顺序（成本从低到高，建议 E1→E2→E3 先跑）

| # | 实验 | 期望信号 | 成本 |
| --- | --- | --- | --- |
| E1 | 列出**全部** X 窗口（含 unmapped）＋几何/映射状态 | 直接找到 present 真正写入的窗口 | 1 次运行 |
| E2 | 把 `-disable-dri3` 改成**文件开关**（免重编），DRI3 on/off × 探针各一轮 | DRI3 开时是否出画 | 改约 10 行 + 2 轮 |
| E3 | **App 进程**注入 `TERMUX_X11_FORCE_FLIP=1`（DDX 用 `getenv` 读，只在 guest env 里设无效） | H3 | 改约 3 行 |
| E4 | `-disable-gpu-present` / `-force-sysvshm` / `-legacy-drawing` 三组对照 | GPU 拷贝路径是否相关 | 3 轮 |
| E5 | `WINEDEBUG=+vulkan,+x11drv`（走 `files/wine_debug.txt`） | 看到 surface/drawable 与真实 X 请求 | 1 轮 |
| E6 | 去掉虚拟桌面包装，直接 `wine dxvk_probe.exe` | H2 | 1 轮 |

#### 已执行的实验结果（2026-10-04，按时间顺序；**勿重复**）

**E1 · 全窗口枚举 + 像素读回（决定性）**
工具已固化：`cache/xwin_dump.py`（递归列全部窗口，含 unmapped，带 map_state/几何/名字）、
`cache/xreadback2.py`（XGetImage 读回指定窗口像素）、`cache/probe_all_windows.sh`（一次读回全部候选窗口）。
⚠️ 坑：`XGetPixel` 的参数是 `(XImage*, x, y)`，一开始误写成 `(dpy, img, x, y)` → 段错误（浪费了一轮）。

- 窗口树（共 18 个，含 unmapped）：wine desktop `0x400006` 1280x720 IsViewable；控制台窗口
  `0xe00003` 665x427；探针窗口 `0x1600003` 640x480（截图里看到的那个）；
  **其子窗口 `0x1800032` 632x453 IsViewable —— 尺寸与 DXVK 日志里的 swapchain Buffer size 完全一致**
  ⇒ present 目标就是**可见客户区**，不是隐藏窗口（**H1 被推翻**）。
- 像素读回（采样 5px 步长）：desktop/outer 只有桌面蓝 `#3296fa` + 窗口装饰 `#e3e3e3` + 大片黑；
  console 与 **swapchain 窗口 = 100% `#000000`** ⇒ **像素没有进入任何一个 X 窗口**（连"写错窗口"都不是）。

**机制（`+x11drv` / `+vulkan` trace）**
- `+x11drv`：`x11drv_client_surface_create Created 0x60074/… for client window 0x1600032`、
  `client_surface_update_geometry … size 632,453`、`needs_offscreen_rendering … alpha_mask 0`、
  `client_surface_update_offscreen … offscreen 0`，且**每次 present 都有一组 `x11drv_client_surface_update`**
  （1283 组 ≈ present 次数）⇒ 图像上传是 **wine 侧 win32u/winex11 的 "client surface"** 负责，且非离屏。
- `+vulkan`：`thunk32_vkQueuePresentKHR` / `win32u_vkQueuePresentKHR` 各 1254 次
  ⇒ present 确实转进了 wine 的 win32u（表面创建走 `win32u_vkCreateInstance` 体系）。

**环境事实（重要，此前不知道）**
- **本设备 Android 内核完全没有 SysV IPC**：`shmget` → errno 38 (ENOSYS)；
  `/dev/shm`、`/proc/sysvipc/`、`/proc/sys/kernel/shmmax` 均不存在。
- 但 Xlorie 仍**广告 MIT-SHM**（`xdpyinfo` 里在）⇒ 任何走 Xlib `XShmCreateImage`（SysV）的客户端必然失败。
- 对照：核心协议 `XPutImage` **实测完美落地**（窗口读回 `#00ff00`）；lavapipe + vkcube（原生 xcb 客户端）
  present 也正常（30 FPS，立方体可见）⇒ **X 服务器与 ICD 本身都能 present**，坏的是 wine 的窗口上传路径。

**假设检验（全部失败，均已排除）**
- 加 `LD_PRELOAD` 垫片让 wine 认为 MIT-SHM 不可用：`cache/noshim.c`（clang `--target=aarch64-linux-gnu
  -shared -nostdlib` 编成 2KB `libnoshim.so`，覆盖 `XShmQueryExtension`/`XShmQueryVersion` 返回 0）。
  注入方式：**新增 `files/guest_preload.txt`** → App 用 `/usr/bin/env LD_PRELOAD=…` **只给 wine** 注入
  （直接把 LD_PRELOAD 塞进 proot 的环境会因 proot 是 bionic 二进制而 "CANNOT LINK EXECUTABLE … not found"；
  也不要试图用 shell wrapper 替换 wine —— wine 会因此找不到自己的目录而完全起不来）。
  已验证垫片加载进所有 wine 进程（`/proc/<pid>/maps` 有 `libnoshim.so`）⇒ **MIT-SHM 假设被否**（仍全黑）。
- `-legacy-drawing`（Xlorie 改用 FD 缓冲/CPU 路径）⇒ 仍全黑。
- `+dri3`（启用 DRI3）⇒ 窗口内容**从纯黑变纯白**（present 走得更远，但拿到的是空 pixmap），
  **仍无 DXVK 画面**。
- `+dri3` + App 进程注入 `TERMUX_X11_FORCE_FLIP=1`（针对 Xlorie "拒绝导入 FD 缓冲的 flip" 那条特判）⇒ 仍纯白。

**当前结论**：wine 的 win32-surface 上传在 Xlorie 上**静默失效**。最大嫌疑是 Xlorie 的非标准能力面：
DRI3 单向残缺（`fds_from_pixmap = FalseNoop`、无标准 `DRI3Open`）、GLX 是桩、Present flip 拒绝导入 FD 缓冲、
无合成器/无 DRM render node —— wine 期望的某条标准路径在这里被静默吞掉。

**剩下两条决定性诊断（下一步按此顺序）**
1. **X 协议追踪**（Python 代理或 `xscope`）：看 wine 到底发的是 `PutImage` / `ShmPutImage` /
   `PresentPixmap` / `CopyArea` 中的哪一种、发往哪个 drawable——据此决定"给 Xlorie 补什么"或"给 wine 打什么补丁"。
2. **Xvfb 对照**（guest 里起标准 X 服务器，DISPLAY=:1，跑同一探针）：
   若 DXVK 在 Xvfb 上正常 ⇒ 问题是 **Xlorie 特有**；若同样失败 ⇒ 问题是 **wine/Proton 侧**。

**方案取舍（等上面两条结论出来再定，当前不动手）**
- (1) 补 Xlorie / 重编 **libXlorie**：工作量中等（要能构建 X 服务器），但**不需要重编 Proton**。
- (2) 给 wine 打补丁 / 重编 **Proton**：补丁很小（例如让 client surface 上传走已验证可用的 `XPutImage`），
  但 Proton 构建重、耗时长。
- (3) **C 方案（wineandroid.drv）**：wine 直接对 `ANativeWindow` 用 `VK_KHR_android_surface`，
  整条 X / win32u 上传链都不需要 —— 功能与性能的终态，**需要重编 Proton**（本就在计划内）。

**新增的免重编调试开关（本轮加的，善用）**
- `files/xserver_args.txt`：X 服务器参数。`+dri3`/`enable-dri3` = 启用 DRI3（默认禁用并加 `-disable-dri3`）；
  其余 `-` 开头行原样追加（`-legacy-drawing`、`-disable-gpu-present`、`-force-sysvshm`、`-check-drawing`）。
- `files/app_env.txt`：注入 **App 进程** 环境（DDX 用 `getenv` 读的开关，如 `TERMUX_X11_FORCE_FLIP`）。
- `files/guest_preload.txt`：只给 **wine 进程** 设 `LD_PRELOAD`。
- `files/extra_env.txt`：guest 环境（覆盖同名键）；`files/wine_debug.txt`：WINEDEBUG。

#### 这个卡点为什么必须先解决

- 它是「**从 D3D 到屏幕的最后一段**」；W0 证明的只是**宿主 ICD 的 WSI 能出画**（vkcube 那条路）。
- **W1 的 vulkan-wsi-layer 是给宿主 ICD 补 WSI 的**。若 winevulkan 对 DXVK 走的是 H2 那条
  "共享内存 + 自己刷窗"的路，则 **WSI 层对 DXVK 毫无帮助** —— 必须在投 W1 的工程量之前定性，
  否则做完 W1 可能发现 D3D 游戏依旧黑屏。
- 终态仍是 **C 方案（wineandroid.drv）**：wine 直接把 `ANativeWindow` 交给 winevulkan，
  一次绕开 X、Present、共享内存整条链 —— 这也是性能最优解。

### 14.9 开工时的第一步（照抄）

```sh
# 0) W0 已完成的验证：guest 里 lavapipe + 禁用 DRI3 的内嵌 X 服务器 → Vulkan 出画
#    （X 服务器参数由 App 的 XServer.ensureStarted() 传入：:0 -ac -nolisten tcp -disable-dri3）
#    guest 里已装好：/usr/local/bin/{vulkaninfo,vkcube}（来自 Debian vulkan-tools 1.4.341）
$PROOT /usr/bin/env -i DISPLAY=:0 PATH=/usr/local/bin:/usr/bin:/bin HOME=/root TMPDIR=/tmp \
  XDG_RUNTIME_DIR=/tmp VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/lvp_icd.json \
  /usr/local/bin/vkcube --c 100000      # 期望：真机画面上出现旋转立方体，LorieNative ~30 FPS
```

---

## 15. 新会话接力提示词（Session handoff prompt）

> 用途：把下面代码块里的内容原样粘贴给一个新会话，它即可凭本文件（尤其 §14.8）继续推进
> 「DXVK present 黑屏」卡点，不需要重新探索环境。

```text
任务：Proton-droid（Android 上用 PRoot + Proton 跑 Windows 游戏）—— 解决「DXVK present 黑屏」卡点。

第一步：完整读 docs/PROJECT_MEMORY.md，重点是 §0（现状）、§2（铁律）、§11（TODO）、§14（Vulkan WSI），
其中 §14.8 是本卡点的完整分析与实验计划（E1–E6），请从那里开始。

背景（已真机验证，不要重复验证）：
- App 已完全脱离 Termux：App → proot(aarch64) → Debian trixie glibc guest → Proton wine-11 →
  内嵌 libXlorie X 服务器（同进程，socket = files/tmp/.X11-unix/X0）→ EGL → SurfaceView；
  真实 .NET 游戏 GooseDesktop 已能活跃渲染（6~9 FPS，帧率低是 guest 执行栈/proot 的锅，不是显示层）。
- W0 已完成：内嵌 X 服务器加 `-disable-dri3` 后，guest 里 lavapipe + vkcube 能在真机出画（~30 FPS）。
  原因：Xlorie 的 DRI3 单向残缺，而 Mesa 的 X11 Vulkan WSI 见到 DRI3 就优先走 DRI3 present → 黑屏。
- W0.5 现状（本卡点）：自编 32 位 D3D11 探针（源码 cache/dxvk_probe.c；真机
  /sdcard/Download/ProtonDroid/games/dxvkprobe/dxvk_probe.exe）已能让 DXVK 初始化成功、
  3000 次 IDXGISwapChain::Present 全部成功、X 服务器帧率升到 24~27 FPS（damage 有产生），
  但**可见窗口内容仍是纯黑**（像素级 distinct=1、100% (0,0,0)）。

目标：定位并修复「DXVK present 产生 damage 但不改可见窗口」，验收标准 =
探针窗口出现随时间变化的颜色（间隔 2~3 秒两张截图在窗口区域存在像素差异，且非全黑）。

按 §14.8 的 E1→E6 顺序执行，每步记录「命令 → 现象 → 结论」再进下一步：
E1 列出全部 X 窗口（含 unmapped）＋几何/映射状态，找 present 真正写入的 drawable；
E2 把 `-disable-dri3` 改成文件开关（免重编），DRI3 on/off 各跑一轮探针；
E3 给 App 进程注入 TERMUX_X11_FORCE_FLIP=1（DDX 用 getenv 读，只在 guest env 设无效）；
E4 `-disable-gpu-present` / `-force-sysvshm` / `-legacy-drawing` 三组对照；
E5 WINEDEBUG=+vulkan,+x11drv 抓 surface/drawable 与真实 X 请求；
E6 去掉虚拟桌面包装，直接 `wine dxvk_probe.exe`。

环境速查（照抄）：
- 设备 PGZ110（Android 15）；adb：D:\XSBDownload\SDK\platform-tools\adb.exe（会偶发掉线，重连即可）
- 构建：$env:GRADLE_USER_HOME='D:\cargoproject\Proton-droid\.gradle-home'；
  $env:ANDROID_USER_HOME='D:\cargoproject\Proton-droid\.android-home'；
  .\gradlew.bat assembleDebug；adb install -r app\build\outputs\apk\debug\app-debug.apk
  （本地固定 debug 签名，install -r 会保留 2.4GB 运行时）
- 免重编调试钩子：files/wine_debug.txt（WINEDEBUG）；files/extra_env.txt（每行 KEY=VALUE，
  **覆盖**同名 guest 环境变量，如 VK_ICD_FILENAMES / DXVK_LOG_LEVEL）
- guest 命令模板（run-as 域；注意 wine 需要 App 域，故仅用于非 wine 的检查）：
  run-as com.protondroid sh -c 'cd files && PROOT_LOADER=$PWD/bin/loader PROOT_TMP_DIR=$PWD/tmp \
    LD_LIBRARY_PATH=$PWD/bin PROOT_NO_SECCOMP=1 ./bin/proot -r $PWD/rootfs/debian/rootfs -0 \
    -b $PWD/tmp:/tmp /usr/bin/env -i DISPLAY=:0 PATH=/usr/bin:/bin HOME=/root TMPDIR=/tmp <cmd>'
- 启动游戏：App 主页「全屏启动游戏」；扫描器只选 games/ 下的一个 exe，要换目标就把其它
  游戏目录临时移到 /sdcard/Download/ProtonDroid/ 下（run-as 的 mv 会失败，用 adb shell mv）
- 读日志：adb exec-out run-as com.protondroid cat files/proton-stdout.log > cache\x.log
  探针自记日志：/sdcard/Download/ProtonDroid/games/dxvkprobe/dxvk_probe.log
- x86_64 PE 跑不了（本 Proton 只有 32 位 FEX WOW64）→ 测试程序必须 -m32 编译；
  mingw：cache\toolchain\w64devkit\w64devkit（编译前把 TEMP/TMP 指到 cache\tmp，
  否则 gcc 报 Cannot create temporary file）
- 图像判据用像素分析（cache/cmp_region.py、cache/diff_regions.py），不要靠肉眼
- ⚠️ PowerShell 引号坑：不要在 adb shell "..." 里写含 |、$PWD、\" 的复杂命令（会被本地解释）。
  一律用 write 工具写成 .sh 推到 /data/local/tmp 再 chmod +x 执行。
- 提交与同步：较大改动后回写 docs/PROJECT_MEMORY.md 并在 commit message 末尾加
  `Memory: 已更新 docs/PROJECT_MEMORY.md §X`；推送：
  git -c http.proxy=http://127.0.0.1:20808 push https://x-access-token:<gh token>@github.com/AMM2034567/Proton-droid.git HEAD:main
  （token 用 gh auth token 取，注意别把 token 打进输出）

约束：
- 不要重复验证 §14.8 表格里已有结论的项；不要为了"看起来有进展"改无关代码。
- 每轮真机实验后立刻把结论写回 §14.8，避免上下文丢失。
- 同类实验连续两轮没进展就上 E5（+vulkan,+x11drv）拿硬证据，不要猜。
- 若最终确认 winevulkan 走的是"共享内存 + 自己刷窗"路径（H2），请在记忆里明确写出
  「W1 的 vulkan-wsi-layer 对 DXVK 无效」，并把结论反馈给 C 方案（wineandroid.drv）的优先级评估。
```

---

## 16. C 方案（wineandroid.drv）实施计划 —— **主线已切换**

> 决策（2026-10-04，用户拍板）：**放弃在 B 方案（内嵌 Xlorie）上继续攻坚，直接上 C 方案**。
> 理由：B 的"最后一米"是 wine 的 win32-surface 上传路径（§14.8），而 Xlorie 是非标准 DDX
> （DRI3 单向残缺、GLX 桩、Present flip 拒绝导入 FD 缓冲）、本质上无法服务标准客户端的 present；
> 继续在上面做兼容是"给一个不标准的 X 服务器打补丁"，而 C 方案一次性取消整条 X 链。
> B 方案的成果**保留**（`XServer`/`Xlorie` 代码、调试开关、探针、性能基线），作为对照与回退。

### 16.1 关键前置事实（已核实，2026-10-04）

1. **上游 wine 自带 Android 驱动**：`dlls/wineandroid.drv/`
   （`WineActivity.java`、`android.h`、`android_native.h`、`device.c`、`init.c`、`window.c`、
   `opengl.c`、`keyboard.c`、`dllmain.c`、`Makefile.in`、`build.gradle.in`、`AndroidManifest.xml`）。
2. **上游构建系统原生支持 Android 构建**（`configure.ac` 实测摘录）：
   ```
   linux-android*)
       enable_wineandroid_drv=${enable_wineandroid_drv:-yes}
       WINE_CHECK_SONAME(GLESv2,glFlush)
       AC_PATH_PROG([GRADLE], [gradle])
       test -n "$GRADLE" || AC_MSG_ERROR([gradle is required for the Android build])
       AC_SUBST([ANDROID_HOME],[$ANDROID_HOME])
       aarch64) exec_prefix='${prefix}/arm64-v8a' ;;
   ...
   dlls/wineandroid.drv/wine-debug.apk: … gradle -q -Psrcdir=$srcdir assembleDebug
   ```
   ⇒ 只要 `--host=aarch64-linux-android` + `gradle` + `$ANDROID_HOME`，wine 就能编出
   **自己带 Java 侧的 APK**。
3. **Java 侧契约**（⚠️ **2026-10-04 更正：这里原来抄的是 wine-11.19 的契约，和 CI 的 `wine_ref=wine-11.0`
   不兼容！** 权威文件见 `cache/refs/wineandroid/WineActivity.java`（wine-11.0 真身，893 行；11.19 的
   对照版留在同目录 `WineActivity.wine-11.19.java`）。切换点在 wine-11.6~11.10 之间）：
   - **wine-11.0**：`private native String wine_init(String[] cmdline, String[] env)`——**wine 跑在 app 进程内**，
     `System.load(<abi>/lib/wine/<so_dir>/ntdll.so)` 后调 `wine_init` 即成为 wine 主线程（**绝不能在 UI 线程**，
     上游另起 `new Thread`）；`wine_init` 阻塞到 wine 退出；**返回值是未初始化的栈内存，别用**。
     Java→native 只注册 5 个：`wine_desktop_changed(int,int)`、`wine_config_changed(int)`、
     `wine_surface_changed(int,Surface,boolean)`、`wine_motion_event(int×5,boolean)`、`wine_keyboard_event(int×4)`。
     native→Java：`createDesktopWindow(int)`、`createWindow(int,boolean,int,float,int)`（签名 `(IZIFI)V`）、
     `destroyWindow(int)`、`windowPosChanged(…17 个 int)`、`setParent(int,int,float,int)`、`setCursor(…)`。
   - **wine-11.19（旧记忆）**：`wine_init()` 无参 + `ProcessBuilder` 另起进程 + `System.load` 三个 `.so` +
     `createDesktopView()`（无参）+ `createWindow(hwnd,is_desktop,opengl,parent)` + 无 scale。
     **混用会直接炸**：11.19 的 Java 配 11.0 的 native ⇒ `GetMethodID("createWindow","(IZIFI)V")` 失败
     ⇒ `FIXME("method createWindow not found")` ⇒ **一个窗口都建不出来**。
   - 每个 HWND 对应 `WineView extends TextureView`（`onSurfaceTextureAvailable` 把 `Surface` 交给 native）
     ⇒ **ANativeWindow 的来源**；每个 HWND 两个 `WineWindowGroup`（整窗 + 客户区），App 必须提供根
     `TopView extends ViewGroup`（`setContentView` 的那个，它的尺寸经 `wine_desktop_changed` 上报为 wine 屏幕尺寸）。
   - 包名**不能改**：`ntdll.so` 里 `WINE_JAVA_CLASS` 硬编码 `"org/winehq/wine/WineActivity"`（loader.c:1931）。
   - ABI 目录映射：`x86→i386-unix`、`x86_64→x86_64-unix`、`arm64-v8a→aarch64-unix`。
   - **`/desktop` 第三字段必须是 `android`**（`programs/explorer/desktop.c:43` 的
     `default_driver = L"mac,x11,wayland"` **不含 android**；第三字段会被拼成 `wine<X>.drv` 去 LoadLibrary）。
     ⇒ 用 `/desktop=shell,,android` 或 `/desktop=shell,1280x720,android`；两字段写法 ⇒ 依次试
     mac/x11/wayland 全部失败 ⇒ `DriverError` ⇒ `null_user_driver` ⇒ **一个窗口都不出**。
4. **构建策略：只走 CI**（用户明确要求：本机带不动 wine 这种体量的构建）。
   本机只做：写脚本/看日志/装产物/真机验证。

### 16.2 里程碑与验收

- **C0 ✅（已完成）侦察**：确认上游 `wineandroid.drv` + `configure.ac` Android 支持 + Java 契约。
- **C1 ✅（2026-10-04 完成）构建管线**：CI（`ubuntu-latest` + NDK 27 + mingw-w64 + gradle 8.7）跑
  `scripts/ci_build_wine_android.sh`，**run #12（37115660926）全绿**（2m47s，增量）。
  验收（已核验 run #11/#12 的 artifact `wine-android-build`）：安装树 `wine-android-arm64-install.tar.gz`
  （174MB，2574 个条目）含 `arm64-v8a/bin/wineserver`、
  `lib/wine/aarch64-unix/{ntdll,win32u,wineandroid,winevulkan,...}.so`、
  `lib/wine/aarch64-windows/{wineandroid.drv,ntdll.dll,kernel32.dll,...}`、`share/wine/**`；
  另附 build 树 tar（183MB，排查用）。**产物落本机**：`D:\cargoproject\Proton-droid\.cache-gh\wine-android-build.zip`
  （解包后的两个 tar 在 `cache\run12\`）。
- **C2 最小可运行**：在我们 App 里跑起 Android wine，让其窗口/表面在 `SurfaceView` 上出画
  （先用 wine 内建程序，如 `winecfg`/`notepad`/`explorer`）。
- **C3 Vulkan/DXVK**：让 winevulkan 用 `VK_KHR_android_surface` 对 ANativeWindow 呈现，DXVK 出画。
  **重要技巧**：测试程序可编成 **ARM64 Windows PE**（用 [llvm-mingw] 的 `aarch64-w64-mingw32` 工具链）
  ⇒ **不需要 FEX 就能验证 D3D11/DXVK 全链**（FEX 留到 C4）⇒ 大幅降低 C3 风险。
- **C4 x86 游戏**：引入 FEX（Android/aarch64 构建）+ WoW64，跑 x86/x86_64 游戏；输入/音频/存档。
- **C5 性能与整合**：零拷贝呈现、帧调度、与既有 UI/游戏扫描/运行时分发整合；与 B 方案的基线对比。

### 16.3 本次落地的产物

- `.github/workflows/wine-android.yml`：手动触发（`workflow_dispatch`，输入 `wine_ref`/`api_level`），
  磁盘清理 → JDK17 → 主机依赖 → gradle 8.7 → NDK 27 → 构建 → 上传 `configure/make` 日志与 artifacts。
- `scripts/ci_build_wine_android.sh`：完整配方
  （深度浅克隆 wine → 先构建 **host 工具** `winebuild/widl/winegcc`（交叉编译必需）→
   `--host=aarch64-linux-android --with-wine-tools=<host build>` + 精简依赖
   （`--without-x/freetype/alsa/pulse/oss/cups/dbus/gnutls/ldap/sane/usb/v4l2/pcsclite/netapi/krb5/gstreamer/opencl`）
   → `make -j` → 收集 wine 树与 APK）。

### 16.4 风险与开放问题

1. **首轮大概率不通过**：`wineandroid.drv` 的代码年代较早（Alexandre 2013-2017 起），
   现代 NDK/API 28 下需要补丁（历史上正有 "wineandroid.drv: experimental bring-up fixes for
   Android 7.1.x" 这类补丁系列）——预期要迭代几轮 CI。
2. **依赖**：第一轮先 `--without-freetype`（字体渲染缺失，可接受）；后续再为 target 交叉编译
   freetype（以及 zlib/libxml2 等按需）。
3. **DXVK/VKD3D**：载荷里的 `aarch64-windows` DXVK 可直接复用（wine 的 win32 表面 → winevulkan →
   `VK_KHR_android_surface`）；若 ABI/符号不匹配则需重编（CI 里加 DXVK 构建）。
4. **W^X**：App 仍须 `targetSdk=28`（见 §2/§8.2 的踩坑），否则 app 私有目录的 `execve`/`PROT_EXEC` 被拒。
5. **Java 侧整合**：我们要把 wine 的 `WineActivity` 移植/裁剪进 Proton-droid（或用其 APK 作为参考），
   并把"每个 HWND 一个 TextureView"的模型与我们的游戏启动 UI 融合。
6. **许可**：wine 是 LGPL-2.1+（动态链接合规）；Android 驱动与 Java 侧一并纳入
   `docs/THIRD_PARTY_NOTICES.md`。

### 16.5 下一步（按顺序）

1. 触发 CI 首轮（`gh workflow run "Build wine for Android (C 方案)" -f wine_ref=wine-11.0`），
   读 `configure-android.log` 里的首个错误 → 打补丁（可能需要在 CI 里 `git apply` 本地补丁）。
2. C1 通过后：把产出的 wine 树装进 App，按 §16.1.3 的契约起窗口（C2）。
3. C2 出画后：接 DXVK + vulkaninfo 验证 `VK_KHR_android_surface`（C3），必要时用 llvm-mingw 编
   ARM64 Windows PE 版 DXVK 探针。

### 16.7 C2 接入前的硬事实与阻塞点（2026-10-04 从上游源码逐行核实）

参考源已落盘：`cache/refs/wineandroid/`（`device.c` / `window.c` / `init.c` / `keyboard.c` / `opengl.c` /
`android_native.h` / `WineActivity.java`(11.0) / `upstream-loader.c` / `upstream-explorer-desktop.c` /
`upstream-win32u-driver.c` / `UPSTREAM_MANIFEST.md` …）。下面每条都有源码位置，别再靠猜。

1. **wine-11.0 的 `wineandroid.drv` 完全没有 Vulkan 代码**（全仓搜 `VK_KHR_android_surface` /
   `AndroidSurfaceCreateInfoKHR` 只命中 vkd3d 自己的 util；`win32u/driver.c:908` 只有 `nulldrv_VulkanInit`，
   且 `wineandroid.drv` 的 driver funcs 表（init.c:297-323）**没有 `.pVulkanInit`**）。
   ⇒ **C3 的 `VkSurfaceKHR` 在 11.0 里没有生产点，是要新写的代码**。建议先插一个 **C2.5**：用
   vkcube-android/vulkaninfo 证明 `VK_KHR_android_surface` 能对同一个 ANativeWindow 出画，再选路线
   （a 让 DXVK 直调原生 ICD；b 给 wineandroid.drv 加 `pVulkanInit` + 给 winevulkan 打补丁；c 放弃 WSI 走 CPU blit）。
2. **CI 产物形态（C1 的真正验收标准）= `make install` 的安装树**，不是 APK、也不只是 build 树：
   `$PREFIX/arm64-v8a/{bin/wine,bin/wineserver}` + `lib/wine/aarch64-unix/*.so`（`ntdll.so`/`win32u.so`/
   `wineandroid.so`）+ `lib/wine/aarch64-windows/*.dll` + `share/wine/**`（`exec_prefix` 默认 `${prefix}/arm64-v8a`，
   configure.ac:1040-1045）。wine 自带的 gradle/APK 规则（configure.ac:3819）用 **AGP 2.2.1 + compileSdk 25 +
   jcenter()**，且 wine-11.0 没有任何规则往 `dlls/wineandroid.drv/{assets,lib}` 填 payload
   ⇒ **APK 必败且无用**，CI 里用 gradle stub 跳过（`WINE_APK_MODE=stub`，默认），别再把它当验收标准。
3. **C 方案不再用 proot 跑 wine**：CI 产物是 bionic（`--host=aarch64-linux-android`），在 glibc rootfs 里
   加载不了。模型是「wine 直接在 app 进程 dlopen，PE 进程由 wine 自己起」。proot 只剩解包杂活。
4. **两条呈现路径**：`opengl` 形参在 native 里叫 `client`，含义是"客户区表面"，不是"用不用 GL"。
   `winecfg`/`notepad`/`explorer` 全走 **CPU 路径**（`android_surface_flush` → `NATIVE_WINDOW_LOCK` →
   `gralloc_lock`；**没有 gralloc 就回退** `native_buffer_wrapper.bits` 的 shm）⇒ **C2 不需要 GL/Vulkan/DXVK**，
   这也是 C2 风险最低的原因。最大技术风险是 Android 14/15 上 `hw_get_module("gralloc")` 是否还在（init.c:443-490）。
5. **输入链路没有时间戳**（`mi.time=0`/`ki.time=0`）；11.0 缺 11.19 的"鼠标键去重补丁"
   （`ACTION_BUTTON_PRESS/RELEASE`+`BUTTON_PRIMARY` 直接 `return true`），建议在纯 Java 侧补上。
6. **真机验收顺序**（含最关键的失败指纹）：`System.load(ntdll.so)` ✓ → logcat `wine` tag 出现
   `desktop_changed: WxH` / `create desktop view` → `onSurfaceTextureAvailable` + `got buffer … fence` →
   **没有** `The graphics driver is missing`（说明 `wineandroid.drv` LoadLibrary 成功）→ **没有**
   `failed to load gralloc module`（有则走 bits 回退）→ 触摸/按键出现 `BUTTONDOWN/MOUSEMOVE/KEYDOWN`。
   ⚠️ 别设 `WINEDEBUGLOG`：它会把**整个 app 进程的 stderr** `dup2` 到文件，logcat 里 wine 的 ERR 全消失。
7. **保留回退路径不删**：`display/XServer.kt`、`ui/GameViewActivity.kt`、`libXlorie.so`、`cpp/proton_bridge.cpp`
   （wine 不走 `nativeSetSurface`）、`ProtonProcessManager.kt`、`WinePrefix.kt`。新增
   `org/winehq/wine/WineActivity.java`（上游 11.0 原样，仅允许 5 处补丁）、`WineAndroidPayload.kt`（解 assets）、
   `DisplayBackend.kt`（`files/display_backend.txt`：`x11` 默认 / `android` 走 C 方案）。


### 16.6 CI 迭代记录（每轮都是真跑，别重复踩）

| 轮 | 耗时 | 结果 | 结论 |
| --- | --- | --- | --- |
| #1 | 1m41s | ❌ host configure 就崩 | 报 `X 32-bit development files not found` ⇒ host 侧 configure 必须显式 `--without-x`（以及其它 `--without-*`） |
| #2 | 2m29s | ⚠️ host configure ✓、**目标 configure ✓**（`Finished. Do 'make' to compile Wine.`）、`make` 崩 | `No rule to make target '<host build>/tools/wine/wine'` ⇒ 目标侧需要 host 侧**完整构建**（不能只编 winebuild/widl/winegcc）；另 `--without-ldap` 是无效选项（会 warning） |
| #3 | 3m5s | ❌ **host 完整 `make` 崩在编译器 ICE**（3/5、4/5 从未执行 ⇒ 零产物） | `dlls/krnl386.exe16/i386-windows/selector.o`：`clang: note: diagnostic msg` + `PLEASE ATTACH THE FOLLOWING FILES TO THE BUG REPORT` ⇒ clang ICE 编 Win16 模块。两条修复：① host/target configure 都加 `--disable-win16 --disable-tests`（Android 跨编译不需要 Win16）；② 2/5 的 `make` 原本没有 `set +e` 保护，`set -euo pipefail` 直接带走脚本，导致目标侧 configure/make 的日志全部丢失 ⇒ 已加保护并检查 `tools/wine/wine` 是否存在。另外 `continue-on-error: true` 让 workflow 顶层显示 success（用户误判「提前结束」）⇒ 已删除；上传路径改为 `wine-out/*.log`（原来只有 configure-android/make-android，host 日志没上传） |
| #4 | 16m14s | ⛔ **人工取消**（已越过全部历史阻塞点：host configure ✓ → **host 完整 `make` ✓**（`--disable-win16` 生效）→ 3/5 交叉 configure ✓ → 4/5 目标 `make` 进行中被打断） | ① 取消**不会执行 post 步骤 ⇒ 缓存没保存**（`actions/caches` 实测：仓库里仍只有 run #3 那个 122MB 的旧缓存）⇒ #5 是冷构建。② 教训：`actions/cache` 的缓存版本含**路径列表**——run #3 是 2 个路径、#4 起是 3 个，所以 `restore-keys` 也匹配不上（旧缓存 `last_accessed_at` 从未更新过，即为证）。**路径列表不要随意改**，否则等于全部冷启动。 |

| #5 | 24m20s | ❌ **目标侧 `wineandroid.drv` 接口漂移（2 处）** + host 侧 clang 又 ICE | ① `init.c:321` 报 `pWindowPosChanged` 类型不匹配：根因是 `android.h:98` 的 `ANDROID_WindowPosChanged` 原型多一个 `BOOL fullscreen`（驱动自己的 `window.c:1059` 定义早已是 6 参数、`gdi_driver.h:431` 也是 6 参数）⇒ **删掉该参数**；② `dllmain.c:126` 把函数指针赋给 `UINT64 start_device_callback`：`ntuser.h:109` 的 `dispatch_callback_params.callback` 本来就是 `UINT64`，winemac 也是 `UINT64 app_icon_callback` + `(UINT_PTR)` 强转 ⇒ **加强转**（不是设计问题）；③ host make 又 clang ICE（clang 18.1.3 SIGSEGV/exit 139）编 `dlls/dinput/i386-windows/dinput.o`（target `i686-unknown-windows-msvc`）⇒ host configure 加 **`--enable-archs=x86_64`** 直接砍掉 i386 PE（host 只要 native 工具，不需要 32 位 PE，顺带更快）；④ **任务失败时 `actions/cache` 的 post 步骤会被 skip（实测 skipped）⇒ 缓存根本没保存** ⇒ 已拆成 `actions/cache/restore` + `actions/cache/save`（`if: always()`、key 带 `run_id`），失败也留缓存供下一轮增量。修法固化成 `scripts/patches/wineandroid-wine11.0.patch`（离线 `git apply --check` 已验证，CI 里先 `git checkout -- dlls/` 复位再 apply，幂等）。已编过的驱动对象：`device.o`/`keyboard.o` ✓；`window.c`/`opengl.c` 尚未编译过（可能还有同类漂移） |

| #6 | 1m13s | ❌ **死在"打补丁"之前，零日志** | 根因是低级的自伤：`SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"` 写在了 `cd "$SRC"`（进 wine 源码树）**之后** ⇒ 相对路径 `./scripts` 不存在 ⇒ `cd ./scripts: No such file or directory` ⇒ `set -e` 直接退出。修法：`SCRIPT_DIR`/`PATCH_DIR` 挪到脚本**最顶部**（任何 `cd` 之前），并给 apply 加显式失败诊断。**两条副产物**：① 新缓存机制生效了（run #6 的 `保存缓存（失败也存…）` 步骤成功，run #7 起能直接恢复 `wine-work/wine` 源码树）；② **运行中/失败的日志获取姿势**：`gh api repos/<o>/<r>/actions/jobs/<job_id>/logs --allow-escape-sequences` 可直接拿全文（`gh run view --log` 会因沙箱拒绝写 `%LocalAppData%\GitHub CLI` 缓存而失败） |

| #7 | 2m00s | ❌ host 侧 clang ICE **搬到 x86_64-windows PE**：`dlls/msvcp100/x86_64-windows/{ios,locale}.o`（wine msvcp90 的内联汇编 RTTI `@__asm_dummy_..._rtti` 把 clang 18.1.3 的 X86 Assembly Printer 搞崩） | `--enable-archs=x86_64` 只挡住 i386，挡不住 x86_64。根因：wine-11.0 `configure.ac` 的 PE 编译器是**自动探测**——`x86_64-w64-mingw32-gcc` → `amd64-w64-mingw32-gcc` → `…-clang` → `clang`（`--enable-sast` 才会默认 clang），我们没装 mingw ⇒ 落到 `/usr/bin/clang` 18.1.3 ⇒ ICE。修法：workflow 里 apt 装 **`gcc-mingw-w64-x86-64`**，脚本顶部也打印选中的 PE 编译器作为证据。附：host 工具守卫按预期生效（`!! host 工具缺失: tools/wine/wine` → exit 1），run #7 的 artifact 只有 23KB（纯日志，无 tar，符合预期） |

| #9 | 39m27s | ❌ 目标侧 `opengl.c:81` 调用 `set_window_opengl_drawable` 未声明 | **host 侧首次全绿**（`host make 退出码: 0`，mingw 修好了 clang ICE；host 全新 configure 到 make 完成约 17 分钟）→ 3/5 交叉 configure ✓ → 4/5 目标编译 9 分钟后崩。根因：`set_window_opengl_drawable()` 在 wine-11.0 里是 **`dlls/win32u/opengl.c` 的 static 函数**（驱动不可见；master 才挪到 `include/wine/opengl_driver.h`，所以代码搜索会误导），而 winex11 在 11.0 里也已无 `update_gl_drawable`（缓存 drawable 由 win32u 自己维护）。且 wineandroid 的 `update_gl_drawable()` **在驱动内零调用者**（纯死代码）⇒ 修法：函数体只留 `NtUserRedrawWindow(...)`，删掉两行不可见的调用。**已验证**：`android.h`/`dllmain.c`/`opengl.c` 三个文件之外，驱动的 `device.o`/`init.o`/`keyboard.o`/`window.o` 都编过了 ⇒ 补丁后应能编完整个驱动。缓存：run #9 的 save 步骤成功，`build-android` 部分产物已入缓存 ⇒ run #10 是增量 |

| #10 | 33m58s | ❌ **链接** `wineandroid.so` 时 `undefined symbol: drawable_mutex`（**驱动所有 .o 都编过了**） | `drawable_mutex` 是旧 wine 由 win32u 导出的全局锁，11.0 里已删；而驱动里**只声明（`android.h:56`）+ 初始化（`init.c:540`）、从不上锁** ⇒ 补丁 v3 删掉这两行。**另一个大发现：host 阶段白花 15 分钟**——`git checkout -- dlls/` 在缓存恢复后（工作区 stat 与 git index 失配）把 `dlls/` 全部源文件按新 mtime 重写 ⇒ make **重编 3159 个文件**（日志实测）。已改成只重置 `dlls/wineandroid.drv/` ⇒ 下一轮 host 应变成秒级。信号：缓存确实生效（`Cache restored from key: …run3711059…`，`PE 编译器: x86_64-w64-mingw32-gcc (GCC) 13-win32`、`已存在，复用` 都出现在日志里） |

| #11 | 3m9s | ⚠️ **C1 产物首次产出**（install tar **175MB** + build tar 183MB）但顶层判失败 | ① `host make 退出码: 0` 只花 **3 秒**（checkout 修复生效：上一轮同一步是 15 分钟 / 3159 个文件）；② 目标 `make` 13 秒后挂在 gradle/APK 规则，**但 `make install 退出码: 0`** ⇒ 安装树已产出（`wine-android-arm64-install.tar.gz`）；③ APK 规则失败真因：Makefile 的 `mv` 取 `build/outputs/apk/wine-debug.apk`（**没有 `debug/` 子目录**），我的 stub 建到了 `debug/` 下 ⇒ 已修 stub 路径；④ artifact 名 `wine-android-build`（351MB）已可下载核验 |

| #12 | 2m47s | ✅ **全绿（C1 完成）** | stub 路径修好后 `make` 也 rc=0；`make install` rc=0。日志可见增量效果：`make-host.log` 只有 64 字节（host make 3 秒）、`make-android.log` 16KB。artifact = `wine-android-build`（351MB：install tar 174MB + build tar 183MB + 三个 log）。**C1 验收判据（已逐项核验）**：install tar 2574 条目，`arm64-v8a/bin/wineserver`、`aarch64-unix/{ntdll,win32u,wineandroid,winevulkan}.so`、`aarch64-windows/{wineandroid.drv,ntdll.dll,kernel32.dll}`、`share/wine/wine.inf` 全部存在 |

**CI 用法**：`gh workflow run "wine-android.yml" -f wine_ref=wine-11.0 -f api_level=28 --ref main`；
日志 `gh run view <id> --log > cache\ci-wine-N.log`；产物 artifact = `wine-android-build`
（现已含 `wine-out/*.log` 全部四个日志 + `artifacts/**`）。
构建步骤已去掉 `continue-on-error`，所以**失败会真的变红**（不再有「顶层 success 但零产物」的假象）；
每个 run 的 Step Summary 会附四个日志的尾部与关键错误行。

**缓存策略（#4 起）**：key = `wine-cache-<ref>-<脚本 hash>`，并配 `restore-keys: wine-cache-<ref>-`
⇒ 改脚本后仍能增量复用 `wine-work/{wine,build-host,build-android}`；脚本里用 `.configure-opts`
戳保证 configure 选项变化时会重新 configure（避免复用旧 config.status 静默出错）。

**⚠️ 日志可见性（#4 的「CI 卡住」假象，务必别再犯）**：脚本里 `make ... | tee log | tail -10`
里的 **`tail` 要等管道结束才吐字**，会把整段构建的输出全部憋住 —— 网页/CLI 上只看到日志停在
configure 结束那一行，看起来像卡死（实际步骤计时器在走）。正确做法：管道只留 `tee`（全量输出），
再套 `stdbuf -oL -eL` 让 make/tee 行缓冲，日志才会真正逐行滚动。REST API
（`gh api .../jobs/<id>/logs`）在步骤结束前一律 404 `BlobNotFound`，只有网页版能实时看。

### 16.8 C2 接入进度（2026-10-04）

**C 方案载荷已发布**：GitHub Release **`wine-android-11.0-r1`**（资产
`wine-android-arm64-11.0-r1-install.tar.gz`，174MB，来自 C1 的 run #12）；
URL 常量 = `WineAndroidPayload.URL_PAYLOAD`。

**App 侧新增/改动**：
1. `app/src/main/java/org/winehq/wine/WineActivity.java`（**917 行** = 上游 wine-11.0 原样 + 5 处补丁）：
   ① WINEPREFIX → `prefix-android`（与 glibc 版 Proton 前缀隔离）；② 默认程序 `wineconsole.exe` → `winecfg.exe`；
   ③ `LD_LIBRARY_PATH` 追加 `<dlldir>/<so_dir>`（aarch64-unix，保证 `ntdll.so` 能 dlopen 同目录 .so）；
   ④ 支持 intent extra `cmdline`（adb 冒烟测试用）；⑤ `loadWineInternal` 开头调
   `WineAndroidPayload.ensureInstalled(this)`。**包名必须保持 `org.winehq.wine`**（`ntdll.so` 里
   `WINE_JAVA_CLASS` 硬编码）。生成脚本（改补丁后重跑）：`.session-recovery/gen-wineactivity.mjs`（临时工具，未入库）。
2. `app/src/main/java/com/protondroid/runtime/WineAndroidPayload.kt`：从 Release（或
   `/sdcard/Download/ProtonDroid/` 公共目录）取 tar.gz，纯 Java 解包（GZIP + 自写 tar reader，
   支持 GNU 长名 `L` 与 PAX 头跳过），解到 `filesDir/{arm64-v8a,share,include}`，给 `arm64-v8a/bin/*`
   补执行位，写 `.wine-android-payload.ok` marker。
   **为什么不塞 APK assets**：上游 `copyAssetFiles()` 在缺 `sums.sum` 时直接 return
   （`readMapFromAssetFile` 捕获 IOException 返回空 Map）⇒ 自管载荷完全兼容，APK 不必胖 400MB。
3. `AndroidManifest.xml`：注册 `org.winehq.wine.WineActivity`（`exported="true"` 仅 C2 冒烟测试用，
   接好入口后改回 false）。**绝不能设 `android:process`** —— wine 必须与 UI 同进程。

**真机冒烟测试（设备连上后）**：
```
adb install -r <CI 出的 APK>
adb push wine-android-arm64-11.0-r1-install.tar.gz /sdcard/Download/ProtonDroid/
adb shell am start -n com.protondroid/org.winehq.wine.WineActivity --es cmdline 'c:\windows\system32\winecfg.exe'
adb logcat -s WineAndroidPayload:* wine:* DEBUG:*
```
**验收判据（§16.7 第 6 条）**：`System.load(…/aarch64-unix/ntdll.so)` 成功 → logcat 出现
`desktop_changed: WxH` / `create desktop view` → `onSurfaceTextureAvailable` + `got buffer … fence`；
**不能出现** `The graphics driver is missing`（drv 加载失败）与 `failed to load gralloc module`（走 bits 回退）。

**两个「写注释把自己写死」的坑（各烧掉一次 CI）**：1. **XML 注释里不能出现连续两个减号** ⇒ 我在 manifest 注释里写了 adb 参数示例 `--es`，直接
   `SAXParseException: The string "--" is not permitted within comments`（`:app:processDebugMainManifest` 挂）。
   **改完 manifest 先在本地 `[xml](Get-Content -Raw …)` 校验再推。**
2. **Kotlin 的块注释可以嵌套**（与 Java 不同！）⇒ 文件头 KDoc 里写了 `…/*.dll` 和 `share/wine/**`，
   其中的 `/*` 会开启嵌套注释，末尾一个 `*/` 只关掉内层 ⇒ `e: Unclosed comment`。**注释里别写 `/*`。**

**载荷事实（实测 `cache/run12` 的 install tar）**：解压后 **1167MB**；2539 文件 + 23 目录 + **12 个软链**
（`bin/{winecfg,wineboot,winedbg,winepath,winefile,notepad,regsvr32,msiexec,msidb,winemine,wineconsole,regedit} -> wine`）。
tar 里**没有** GNU 长名 / PAX / ustar prefix（最长文件名 92 字符）⇒ 自写 reader 走简单路径就够；
`WineAndroidPayload` 已补：软链创建（不支持则退化为复制 `bin/wine`）+ 解压前空间检查（需 ~1.3GB 可用）。

**本地验证流程（别再拿 CI 当编译器）**：
```powershell
cd D:\cargoproject\Proton-droid
$env:GRADLE_USER_HOME="$PWD\.gradle-home"
$env:JAVA_HOME="D:\jdk-17.0.20.101-hotspot"
.\gradlew.bat :app:compileDebugKotlin --offline --console=plain   # 30 秒级，验 Kotlin/manifest
.\gradlew.bat :app:assembleDebug     --offline --console=plain   # 出 app/build/outputs/apk/debug/app-debug.apk（16.3MB）
```
已核验：`WineActivity`（javac）与 `WineAndroidPayload`（Kotlin）都在 APK 的 DEX 里（`classes5/6.dex`）。

### 16.9 C2 真机调试实录（2026-10-04，PGZ110 / Android 15）

**已经跑通的部分（这是 C 方案第一次在真机上把 wine 拉起来）**：
- `WineAndroidPayload` 解包 174MB → 1167MB 到 `files/{arm64-v8a,share,include}`，写入 marker 后跳过重复解包 ✓
- `System.load(ntdll.so)` → `wine_init` → **wineboot 跑起来并成功建出 prefix**
  （`files/prefix-android/drive_c/{windows,system32,users,Program Files}` 都在）✓
- wine 认出了真 GPU：`warn:system:add_gpu Using vulkan GPU pci_id 0x13b5:0000, name "Mali-G610 MC6"` ✓
- `/desktop=shell,,android` 生效：trace 显示 wine **确实去加载 `wineandroid.drv`**（PE 映射正常）

**当前唯一卡点**：`wineandroid.drv` 的 `DllMain(PROCESS_ATTACH)` 返回 FALSE
```
0024:warn:module:process_attach Initialization of L"wineandroid.drv" failed
0024:err:winediag:nodrv_CreateWindow L"The graphics driver is missing. Check your build!"
```
`DllMain` 里只有两处可能失败：① `__wine_init_unix_call()`；② unix `android_init()` 里的
`dlopen("ntdll.so", RTLD_NOW)`（`init.c:528`，该函数唯一的失败返回点）。
已加补丁 v4（`dllmain.c`/`init.c` 各加 ERR 诊断）+ `dlopen` 的 `RTLD_DEFAULT` 兜底，
并在 Java 侧用 `NativeBridge.nativeLoadGlobal`（`dlopen(RTLD_NOW|RTLD_GLOBAL)`）把三个 `.so` 提升为全局可见。

**真机踩坑清单（都已修进代码/脚本）**：
1. **装载器没有执行位** → `err:environ:run_wineboot failed to start wineboot 1`、prefix 建不起来。
   wine-11.0 对 linux-android **不把装载器装到 `bin/`**，它在
   `lib/wine/aarch64-unix/{wine,wine-preloader}` ⇒ 解包器必须给这几个文件补 `+x`（不只是 `bin/`）。
2. **`bin/` 里的 12 个软链（`winecfg`/`wineboot`/… → `wine`）是悬空的**（上游 Android 构建的既知形态），
   解包器仍按 symlink 创建，不影响使用。
3. **`/sdcard` 读不了**：ColorOS 上 shell 连 `appops set … MANAGE_EXTERNAL_STORAGE allow` 都没权限
   （uid 2000 缺 `MANAGE_APP_OPS_MODES`）⇒ 载荷走 **adb → `/data/local/tmp` → `run-as` 拷进
   `files/payload/`** 最稳；代码里公共目录读取失败也必须能优雅回退（原来直接 EACCES 崩了）。
4. **`isInstalled()` 把 marker 也算进判据**，而 marker 是校验之后才写的 ⇒ 永远 "校验失败"。
   已改成只看 `ntdll.so` + `wineserver` 存在。
5. wine 的 stderr **不进 logcat**：把通道写进 `files/winedebug`（内容如 `err+all,warn+all,fixme+all`）
   才会重定向到 `files/log` —— 这是唯一能看到 wine 内部错误的路子（`run-as cat files/log`）。
6. 调试时用 `adb logcat -s ProtonBridge:V wine:V`；抓全量要先 `adb logcat > file` 持续重定向，
   否则 40 秒后缓冲被系统日志冲掉。

#### 16.9.1 续：导出修复见效，进入驱动自身的线程断言

补丁 v5（显式导出 `java_vm`/`java_object`/`java_gdt_sel`）**真机验证有效**：
`Initialization of L"wineandroid.drv" failed` 与 `The graphics driver is missing` **都消失了** ——
驱动第一次真正加载成功。紧接着暴露出上游驱动的第二个问题（还是"从没真跑过"导致的）：

```
dlls/wineandroid.drv/window.c:527: int wait_events(int):
    assertion "GetCurrentThreadId() == desktop_tid" failed
0024:err:seh:NtRaiseException Exception frame is not in stack limits => unable to dispatch exception
```
- `desktop_tid` 在 `init_event_queue()`（window.c:368）里记下"当时那个线程"，而 `init_event_queue` 是从
  `ANDROID_CreateWindow()`（window.c:978，第一次建窗时）调用的；
- `wait_events`/`process_events` 的调用者 `ANDROID_CreateDesktop()`（window.c:1246）由 win32u 在
  **另一个线程**上调用 ⇒ 断言直接 abort（assert 在 debug 构建里是 abort）。

**补丁 v6**：把这两处断言降级为 `WARN`（保留诊断信息，流程继续）。理由：事件管道是普通 fd，
`poll()` 在任何线程都有效；Java→wine 方向是 `write()` 到 pipe，与线程归属无关。
（上游那套 "wineandroid.drv: experimental bring-up fixes for Android" 补丁系列解决的就是这类问题。）

**C2 迭代姿势（已验证很快）**：CI 产出 `wine-android-delta`（约 7MB，只含 `aarch64-unix/*.so`
+ `aarch64-windows/wineandroid.drv`）⇒ `adb push` 到 `/data/local/tmp` ⇒
`run-as … sh -c 'cd files/arm64-v8a/lib/wine && tar xzf …'`（设备自带 `/system/bin/tar`）⇒ 重跑。
一轮约 5 分钟，不用重推 174MB 载荷。

#### 16.9.2 断点与一键续跑（手机暂时断开前的状态，2026-10-04 19:50）

**已完成**：补丁 v6（6 个文件）已推送并触发 CI 构建；`cache\c2-retest.ps1` 已备好
（推增量包 → 设备端 tar 就地覆盖 → 拉起 WineActivity → 打印进程/窗口/wine 日志判据 → 截图）。

**手机插回后的续跑步骤**（一条命令）：
```powershell
# 1) 取最近一次 wine CI 的增量包（约 7MB，比 351MB 全量包快得多）
gh run download <runId> -n wine-android-delta -D cache\delta
# 2) 一键验证（默认用 cache\delta\wine-delta.tar.gz）
pwsh -File cache\c2-retest.ps1
```
判据（脚本会打印）：
- 进程存活（不再闪退）；
- `files/log` 里出现 `wait_events on thread …` 的 **WARN**（而不是 assertion abort）⇒ 补丁 v6 生效；
- 之后应是 `ANDROID_CreateDesktop` 等到 `screen_width`（由 Java `wine_desktop_changed` 送来）而返回，
  进而出现窗口创建痕迹；截图里应能看到虚拟桌面/`winecfg` 窗口。

**若仍失败**：`cache\wine-log-latest.txt` 是完整的 wine stderr（`err+all,warn+all,fixme+all`），
按里面的第一处 `err:` 继续定位——注意 wine 的 ERR/WARN 日志都在这个文件里，logcat 里看不到。
**已 staged（无需手机）**：补丁 v6 的增量包已下到 cache\wine-delta-latest.tar.gz（7.35MB，run 37120771950），
cache\c2-retest.ps1 默认就用它 ⇒ 手机插上后只需 pwsh -File cache\c2-retest.ps1。
校验：v6 的 wineandroid.so 779672B / sha256 C8F78087…（v5 是 779704B / C7B0CF89…，确认窗口改动已进包）。

#### 16.8.1 C2c：C 方案已接进游戏入口（2026-10-04）

不再依赖 adb 手动拉起：
- 新增 `app/src/main/java/com/protondroid/display/DisplayBackend.kt`：
  读 `<filesDir>/display_backend.txt`（`x11` 默认 / `android`），非法或缺失一律回落 `x11` ⇒ **老路径不回归**；
  `set()` 供 UI/调试切换。adb 切换：
  `adb shell "run-as com.protondroid sh -c 'echo android > files/display_backend.txt'"`
- `ui/GameViewActivity.kt`：`onCreate` **先判断后端**——`android` 时**不启 X 服务器**，
  直接 `startActivity(org.winehq.wine.WineActivity)` 并 `finish()`；并把游戏路径转成 wine 路径传
  `cmdline`（`/sdcard/x/a.exe` → `Z:\sdcard\x\a.exe`，wine 默认把 `/` 映射成 `Z:`）。
  `x11` 时完全走原来的内嵌 X 流程。
- `AndroidManifest.xml`：`WineActivity` 的 `exported` 暂时仍为 `true`（调试期 adb 直拉方便），
  C2 真机验收后改回 `false`；注释已写明。

本地验证：`assembleDebug` 通过，APK 16.7MB。

### 16.10 C2 真机全天调试链（2026-10-04 晚）——每步都有真机证据

| 补丁 | 上游缺陷 | 真机症状 | 结果 |
|---|---|---|---|
| v5 | `java_vm/java_object/java_gdt_sel` 没导出（`-fvisibility=hidden`） | `unix init returned 0xc0000005`（段错误）→ driver 初始化失败 | ✅ 驱动**首次初始化成功** |
| v6 | `wait_events/process_events` 断言 `GetCurrentThreadId()==desktop_tid` | `EXCEPTION_WINE_ASSERTION` → 闪退 | ✅ 不再闪退 |
| v7 | `init_event_queue()`+`start_android_device()` 只在 `ANDROID_CreateWindow` 的"桌面窗口"分支调用，而 win32u 先调 `pCreateDesktop` | `desktop_tid` 恒 0000、`screen_width` 恒 0 → 主线程死循环刷 WARN | ✅ 循环消失 |
| v8 | `ANDROID_CreateDesktop` 是 BOOL 却 `return 0` | `explorer: manage_desktop failed to create desktop … error 0` | ✅ 桌面创建成功 |
| v9 | （我们的诊断）win32u 选驱动失败原因不可见 | 仍 `nodrv_CreateWindow … The graphics driver is missing` | 🔄 编译中，见下 |

**v7 的一个重要副产品**：日志里不再出现 `wait timed out` ⇒ **Java 侧桌面视图确实建起来了**，
`wine_desktop_changed` 把屏幕尺寸送到了 wine（`screen_width` 被置位）——即 C 方案的
"Java ↔ wine 事件通道"是通的 ✓。

#### 16.10.1 架构性发现：子进程没有 JVM

进程树（真机 `ps`）：
```
com.protondroid                        ← App 进程，有 JVM
wineserver
C:\windows\system32\explorer.exe       ← 独立进程 fork+exec
c:\windows\system32\winecfg.exe        ← 独立进程
```
wine 的 `CreateProcess` 是 fork+exec ⇒ 子进程里 `java_vm/java_object` 为 NULL ⇒
`wineandroid.drv` 在子进程里无法工作（日志：`nodrv_CreateWindow`，且它去试 mac/x11/wayland 默认列表）。
**结论**：C 方案下目标程序必须作为**主程序在同进程内**运行（`wine_init` 的 `start_main_thread` 就在 App 进程）。
已在 `WineActivity` 去掉 explorer：`cmd = { WINELOADER, cmdline }`（原来是
`{loader, explorer.exe, "/desktop=shell,,android", cmdline}`，那会让目标程序变成子进程）。

#### 16.10.2 驱动选择：win32u 读的是"按显示设备"的 HKLM 键

- `dlls/win32u/driver.c:972` `load_desktop_driver()` 读
  `\Registry\Machine\System\CurrentControlSet\Control\Video\{<GUID>}\0000` 的 `GraphicsDriver`；
  `<GUID>` 取自桌面窗口的显示设备属性，无该属性时用**全零 GUID**（`guid_nullW`）。
- 上游是靠 explorer 的 `/desktop=shell,,android` 第三字段：`programs/explorer/desktop.c:1011-1063`
  打开 `HKCU\Software\Wine\Drivers` 并写 `GraphicsDriver`。
- 我们不走 explorer ⇒ 在 `WineActivity.ensure_android_driver_registry()` 里自己写：
  `user.reg` 的 `[Software\\Wine\\Drivers]` + `system.reg` 里
  `CurrentControlSet` / `ControlSet001` 的 `Control\Video\*` 全部子键（含全零 GUID 键）。
- **仍未生效**（真机仍报 `nodrv`）⇒ 补丁 v9 给 win32u 加了 ERR 诊断，打印它**实际打开的键路径**与读取结果：
  `win32u: cannot open graphics driver key %s` / `win32u: no GraphicsDriver value in key %s`。
  下一轮读 `files/log` 里这两行，就知道该写哪个键（或改用别的机制）。

#### 16.10.3 C2 迭代姿势（每轮 5 分钟）
```
CI 编 wine → gh run download <run> -n wine-android-delta -D cache\delta-vN   # 7MB
tar -xzf 到 cache\wine-delta-latest.tar.gz
adb push → run-as 'cd files/arm64-v8a/lib/wine && tar xzf …'                # 设备自带 /system/bin/tar
adb shell am force-stop com.protondroid && am start -n …WineActivity
run-as cat files/log                                                        # wine 的 ERR/WARN 都在这
```
APK 侧改动（Java）不需要 CI，本地 `assembleDebug` + `adb install -r` 即可（3~8 秒）。

### 16.11 注册表路线已被证实可用（`+reg` trace 铁证），但有别的东西还在跑 explorer 桌面代码

`WINEDEBUG=+reg,+driver` 的真机 trace（`cache/wine-log16.txt`）：
```
0044:trace:reg:NtOpenKeyEx (0x0,L"\Registry\Machine\System\CurrentControlSet\Control\Video\{00000000-0000-0000-0000-000000000000}\0000",...)
0044:trace:reg:NtQueryValueKey (0x44,L"GraphicsDriver",2,...)          ← 读到了我们写的值
0044:trace:driver:load_desktop_driver trying driver L"wineandroid.drv\0000"   ← \0000 只是调试打印的 NUL
0044:warn:module:load_dll Failed to load module L"winemac.drv" …
0044:warn:module:load_dll Failed to load module L"winex11.drv" …
0044:warn:module:load_dll Failed to load module L"winewayland.drv" …
0044:trace:reg:NtSetValueKey (0x50,L"DriverError",…)
0024:err:winediag:nodrv_CreateWindow L"The graphics driver is missing. Check your build!"
```
结论：
1. **注册表这条路是通的**（`ensure_android_driver_registry()` 写的全零 GUID 键被 win32u 正确读到）✓
2. `wineandroid.drv` 被点名后**加载失败**，随后回落 mac/x11/wayland 默认列表，并写入 `DriverError`
   —— 写 `DriverError` 的只有 `programs/explorer/desktop.c:1066` ⇒ **还有 explorer 的桌面代码在跑**
   （残留的进程或前缀里的 Explorer 桌面设置），它那侧没有 JVM ⇒ 加载失败并把错误写进注册表。
3. 之后清空 `files/prefix-android` 重跑，暴露另一个问题：
   `wine: '…/prefix-android' is a 64-bit installation, it cannot be used with a 32-bit wineserver.`
   （清空前缀后第 1 次 wineboot 建好、第 2 次启动时报的；可能是上一次残留的 wineserver 未退干净）

**下一步（按优先级）**：
1. 确保 wine 进程/wineserver 全部退干净再重跑（`am force-stop` 之后确认 `pidof wineserver` 为空），
   看清空前缀后第 3 次启动是否还报 arch 不匹配；
2. 确认没有任何 explorer 桌面路径被触发：`+reg` 里若再出现 `DriverError` + mac/x11/wayland 列表，
   就查是谁调用的（前缀里 `HKCU\Software\Wine\Explorer\Desktop(s)` 残留值）；
3. 补丁 v10（`dlls/win32u/driver.c`：win32u 在"键打不开/没值"时**直接兜底 wineandroid.drv**）已在本地提交
   `611db2d`，等网络恢复由后台任务 `pwsh-206` 推送并构建 —— 它会绕过注册表这一环。

### 16.12 突破：驱动选择问题解决了（v10 兜底 + 前缀由 wineboot 先建）

真机验证（`cache/wine-log20b.txt`，run 37125043802 的 v10 增量包）：
```
0034/0024:err:driver:load_desktop_driver win32u: no GraphicsDriver value for key
    L"\Registry\Machine\System\CurrentControlSet\Control\Video\{18476b37-…}\0000",
    falling back to wineandroid.drv
```
两点关键认知：
1. **那个显示设备的 GUID 是每次运行随机生成的** ⇒ 任何"写死注册表键"的做法都不可能命中 ⇒
   必须走 v10 的 win32u 兜底（`dlls/win32u/driver.c`：键打不开/没值时内置 `wineandroid.drv`）✓ **已验证生效**。
2. **前缀必须由 wineboot 先建**：我们若在 `system.reg` 不存在时就自己造 `user.reg/system.reg`，
   wine 会因为缺 `#arch=` 判定而报
   `is a 64-bit installation, it cannot be used with a 32-bit wineserver`（真机踩过）。
   已修：`ensure_android_driver_registry()` 在前缀没有 `system.reg` 时直接返回。
   wineboot 建好后 `system.reg` 是 1.8MB / 38129 行（我们写的补丁只是追加一个节）。

当前真机状态（2 轮启动后）：
- App 进程存活，栈顶是 `WineActivity`，屏幕上是它自己的 **"Setting up the Windows environment…"** 进度框
  （`wine_init` 会阻塞到主程序退出，所以这个框挂着是正常的）；
- 进程树：`com.protondroid`(App) + `wineserver` + **`explorer.exe`（fork 出去的子进程，没有 JVM）**；
- 该子进程就是 `0044` 那个报 `winemac/winex11/winewayland … Failed to load module` 并写 `DriverError` 的家伙。

**下一步**：
1. 确认主进程里驱动是否真的加载成功（`nodrv_CreateWindow` 是否消失、是否有 `wineandroid` 的窗口创建日志）；
2. **处理 explorer 子进程**：它没有 JVM 必然失败并写 `DriverError`，需要让它不被启动
   （前缀里 `HKCU\Software\Wine\Explorer` 的 `Desktop`/`ShellFolders`、或 wine 启动时自动拉 explorer 的路径），
   否则它会持续污染显示设备的状态；
3. 之后再看 `winecfg` 的窗口能否出现在 TextureView 上。

### 16.13 v11 真机结果：主进程**收到了**桌面尺寸，但之后阻塞（C 方案最接近成功的一次）

补丁 v11（首个窗口就启动设备线程/Java 桌面视图）+ Java 侧主动上报尺寸，真机日志：
```
0024:trace:android:process_events DESKTOP_CHANGED 2294x914      ← 主进程收到尺寸！(之前一直是 0x0)
```
- 这条是**主进程 `0024`**（有 JVM 的那个）打出来的 ⇒ Java 的 `wine_desktop_changed(2294,914)`
  经事件管道送到了驱动、被 `process_events` 处理 ✓ —— **Java ↔ wine 的尺寸通道打通**
- 驱动早先的活动也都正常：`init_gralloc` ✓ `device_thread` ✓ `ANDROID_CreateWindow` ✓
  `create_ioctl_window` ✓（Java 侧窗口真的创建了）
- 但 `DESKTOP_CHANGED` 之后日志**不再增长**（814 行停住），进程仍活着 ⇒ wine 在
  `ANDROID_CreateDesktop` 之后、创建 Java 桌面视图（TopView）之前**阻塞**了
  （Java 侧始终没有 `create desktop view` 日志 ⇒ `createDesktopWindow(hwnd)` 没被调用）
- `fetch_display_metrics screen 0x0 / 1x1` 来自**没有 JVM 的子进程**（explorer/services），与主进程无关

**下一步（定位阻塞点）**：
1. 看主进程线程栈：`adb shell run-as com.protondroid cat /proc/<pid>/task/*/stack` 拿不到的话，
   用 `debuggerd -b <pid>` 或 `kill -3`（Java 栈）＋ wine 的 `+sync`/`+server` 通道；
2. 重点怀疑 `start_android_device()` 里的 `WaitForMultipleObjects(2, handles, FALSE, INFINITE)`
   —— 它等设备线程 signal 事件；若设备线程里的 `android_java_init` 卡住（例如 Java 侧
   `setContentView` 在 UI 线程之外调用、或 progress_dialog 相关），主进程就永远等下去；
3. 也可先临时把 `start_android_device` 的等待改成带超时，观察是否从此处卡住。

**已确认有效的机制（供后续参考）**：
- 驱动选择：v10 win32u 兜底（显示设备 GUID **每次运行随机**，写死注册表键不可能命中）
- 前缀：必须由 wineboot 先建（否则 wine 报 32-bit wineserver arch 不匹配）
- 目标程序 = 主程序（同进程、有 JVM）；explorer 等 fork 子进程天然不可用

### 16.14 C2 打通：从"驱动起不来"到"桌面视图就位、等待渲染"

补丁 v11 真机结果（`cache/wine-log28.txt` + logcat）：
```
I/wine: create desktop view 00010020        ← 驱动回调 Java，TopView 创建成功（上游 Java 的 create_desktop_window）
I/wine: report desktop size 2294x1032       ← 我们补的主动上报
I/wine: fallback report desktop size 2294x1032
wine: 0024:trace:android:process_events DESKTOP_CHANGED 2294x1032
wine: 0024:trace:android:process_events CONFIG_CHANGED dpi 480
```
⇒ **全链路第一次跑通**：驱动加载(v10) → 事件队列+设备线程(v11) → Java 桌面视图 → 尺寸/DPI 回送 → 驱动处理事件。
屏幕表现：进度框消失（`progress_dialog.dismiss()` 被调用）、变成 **TopView 的黑屏**。

**至此 C 方案已跨过的所有坎（每一条都有真机证据）**：
1. 载荷安装 + 装载器可执行位
2. `wineandroid.drv` 初始化（导出 `java_vm`/`java_object`/`java_gdt_sel`）
3. 线程断言（`wait_events/process_events`）
4. 事件队列/设备线程的初始化顺序
5. `ANDROID_CreateDesktop` 返回 TRUE
6. 驱动选择（win32u 兜底 `wineandroid.drv`；显示设备 GUID 每次随机）
7. 前缀由 wineboot 先建（否则 32-bit wineserver arch 报错）
8. 主程序必须是目标程序（fork 子进程没有 JVM）
9. 设备线程/Java 桌面视图改为首个窗口就启动；尺寸主动上报

**当前状态 = 黑屏（TopView 已就位，但没有内容画出来）**，wine 日志停在第 817 行（`CONFIG_CHANGED`）
⇒ 主程序 `winecfg.exe` 还没把可见窗口/表面画出来。

**下一步**：
1. 看 Java 侧有没有 `create window`/`wine_surface_changed` 的日志（上游 Java 的 `create_window`/`wine_surface_changed`
   负责给每个 HWND 建 `WineWindow` + SurfaceView ✓ 这是像素来源）；
2. 驱动侧 `+android` 里 `create_ioctl_window` 之后是否卡在等 Java 的 ioctl 返回（device.c 的 app_ioctl 往返）；
3. 若窗口没建：查 `winecfg.exe`（主程序）为何停在窗口创建前——可以临时把主程序换成更简单的
   `notepad.exe`/`winemine.exe` 看是否出画面，以区分"驱动渲染问题"还是"winecfg 自身问题"。

### 16.14.1 黑屏的直接原因：窗口矩形是垃圾值（监视器几何没建立）

`cache/wine-log28.txt` 统计（`ANDROID_WindowPosChanging` 的窗口矩形）：
```
0x0                出现 84 次
31785845x31785532  出现  9 次     ← 明显是未初始化内存
6357209x6357112    出现  2 次
0x121 / 4x948 / 0x8 …               ← 都不是正常窗口尺寸
```
- Java 侧窗口**确实在建**：`create hwnd 00010020` 之类 ✓；驱动侧 `create_ioctl_window … opengl=0` 一大串 ✓
- 尺寸事件**确实在送达并被处理**（`DESKTOP_CHANGED 2294x1032`、`CONFIG_CHANGED dpi 480` 多次）✓
- 但**这些窗口是在尺寸到达之前创建的**（矩形全是垃圾/0），之后日志停在第 817 行、进程仍活着
  ⇒ 主线程已无事可做/在等待，而可见窗口（winecfg）没有被正确创建/定位
- 屏幕因此是 TopView 的黑屏（进度框已 dismiss ⇒ 视图链路是通的）

**结论**：现在是"**时序 + 监视器几何**"问题，不是渲染/驱动问题。

**下一步（按性价比排序）**：
1. **让驱动在建窗之前就拿到尺寸**：把 Java 的上报时机提前到 `wine_init` 之前（在主线程里先调一次
   `wine_desktop_changed`），或在驱动侧让 `ANDROID_CreateDesktop`/`ANDROID_CreateWindow` 等到尺寸就绪
   （现在是"先建窗、后知道尺寸"）；
2. **确认监视器几何真的建立了**：给 `ANDROID_UpdateDisplayDevices`（device.c）加 TRACE，
   看 `init_monitors(width,height)` 是否被调用、`screen_width/height` 是否被采用
   （垃圾矩形强烈暗示 monitor info 从未初始化）；
3. **换一个更简单的主程序**（`notepad.exe` / `winemine.exe`）验证"驱动能否画出任何窗口"，
   把"驱动渲染问题"与"winecfg 自身问题"分开。

### 16.15 v12 死锁（撤销）与 v13 结果；下一步：把屏幕尺寸从"事件"改成"启动前就带进来"

**v12（驱动 init 时起设备线程）真机结果：死锁，已撤销**
```
006c:err:sync:RtlpWaitForCriticalSection … ntdll/loader.c: loader_section … blocked by 0048, retrying (60 sec)
```
`0048` 是我们在 `android_init` 里启动设备线程的路径：它**持有 loader 锁**，而设备线程（→Java
`create_desktop_window`）还要加载模块 ⇒ loader 锁自死锁。**结论：设备线程不能从驱动 init 启动。**

**v13（Java 侧在设备线程同步上报尺寸）真机结果：回到 v11 行为，窗口仍是 0/垃圾尺寸**
```
w=0 h=0 ×30 ; w=31785845 h=31785532 ×9 ; w=869 h=0 ×1
```
即：v13 让上报更早了一点，但**仍然晚于"wine 创建最初那批窗口"**——因为设备线程/Java 桌面视图
本身就是"首个窗口创建"时才起来的（v11），存在**循环依赖**：最初那批窗口必然在建尺寸之前建出来，
它们的零/垃圾尺寸又被后续沿用。

**下一步（推荐）：不要在"事件/回调"里传尺寸，改为在启动前用环境变量带进驱动**
1. Java（`WineActivity`）在 `wine_init` 之前把真实尺寸写进 env：
   `env.put( "WINE_ANDROID_SCREEN", width + "x" + height )`（`loadWineInternal` 里已有 env 表）；
2. wine 侧在 `dlls/wineandroid.drv/init.c` 的 `android_init`（unix，安全——只设全局量，不启动线程）
   读该变量，直接 `screen_width/screen_height = …`，并在**创建任何窗口之前**把监视器几何建好
   （`init_monitors(screen_width, screen_height)` 或在 `ANDROID_UpdateDisplayDevices` 里采用该值；
   注意 `fetch_display_metrics` 现在在"有 JVM"时直接 return，正是它返回 0 的来源）；
3. 这样第一批窗口就有正确尺寸，屏幕上应能看到 wine 窗口。

**其它已确认的事实（不要再重复踩）**：
- 设备线程/Java 桌面视图只能从"首个窗口"起（v11）；从驱动 init 起会 loader 死锁（v12）；
- `init_monitors()` 只在 DESKTOP_CHANGED 事件处理里被调用（window.c:447），对**已存在**窗口无效；
- 补丁落点要小心：锚点会先在头文件的声明处命中（v12 第一版）、`dllmain.c` 不含 `android.h`（第二版）、
  `window.c` 的函数只进 unix 库而 PE 链接不到（第三版）——这些都是本轮实际踩过的坑。

### 16.16 【重要】上游已放弃"wine 跑在 JVM 进程里"——改成分离进程模型（附链接）

调研发现（2026-10-03，网络检索；以下为外部资料，需按"未验证"对待）：

1. **Wine 11.6 开始复活 Android 驱动**（Phoronix 报道）——即 `wineandroid.drv` 不再是死代码。
2. **MR !10569（已合并，2026-04-06 提出，05-15 编辑）**：
   <https://gitlab.winehq.org/wine/wine/-/merge_requests/10569>
   标题：*wineandroid: rework Android integration and switch to **split process model***
   原文要点：
   - "The existing implementation assumes that Wine runs directly inside the JVM process. This causes a
     number of problems on modern Android systems, including **seccomp restrictions applied to app processes**
     and conflicts between Wine execution context assumptions and the JVM runtime."
   - 改造内容：桌面视图改为经 **ioctl 传输**创建（含**显式传递 event pipe fd**）；JNI 初始化改造使
     `wineandroid.so` 可被 JVM 直接加载；activity 生命周期跟随桌面客户端连接；
     **把 Android 侧启动入口从 ntdll 移到 wineandroid.drv，并把 Wine 作为独立进程启动**。
   - ⇒ JVM 进程只保留 Android/UI 侧的驱动部分，Wine 独立进程通过传输层通信。
3. **MR !10683（Twaik Yont，2026-04-16，含在 !10569 里的前 4 个提交）**：
   <https://gitlab.winehq.org/wine/wine/-/merge_requests/10683>
   1) 修 64 位 JNI `setCursor` 崩溃；2) **targetSdkVersion 降到 28**（避开 Android 10+ W^X 限制）；
   3) 修 Wine loader 路径 + `LD_LIBRARY_PATH` 支持直接从 APK 加载库；
   4) 环境设置移到 Java 层（`setenv`）并简化 `wine_init`。

**对我们的意义（初步）**：
- 我们一直在修的很多问题，上游已经/正在修：loader 路径（= 我们的补丁 #6）、JNI 崩溃、W^X/targetSdk28、
  "子进程没有 JVM"（新模型让 Wine 独立进程、不再依赖 JVM ✓✓）。
- 我们手工打补丁的 `wine-11.0` 基线**太旧**（11.0 早于这些改造）⇒ 应考虑把 CI 基线换成
  **包含 !10569/!10683 的版本（11.6+ / master）**，并采用新的分离进程模型，而不是继续给 11.0 打补丁。
- 待确认：包含这些 merged MR 的具体 Wine 版本号；新模型的 App 集成方式（如何启动 Wine 独立进程、
  传输层接口）；我们现有 7 个补丁里哪些可以删掉。

### 16.18 【决策情报】生态调研：直呈的正确实现不是 wineandroid.drv，而是 Vulkan layer + AHB

第二份调研（生态/架构，含大量一手链接）要点：

**① 已实现"直呈"的项目都不是用 wineandroid.drv 做 WSI**
- **Winlator Ludashi-Plus 的 DAC（Direct Android Compositing）**：Vulkan **隐式 layer** 拦截
  `CreateSwapchainKHR/AcquireNextImageKHR/QueuePresentKHR/WaitForPresentKHR`，帧写进 AHardwareBuffer，
  再用 `ASurfaceTransaction_setBuffer` 交给 SurfaceFlinger（BYNC_FD 栅栏同步），**完全绕开 X11**。
  记录明确：**LD_PRELOAD 与 ICD wrapper 两条路都失败**（`winevulkan` 把 device 级函数指针缓存在自己的
  dispatch table 里，绕开 dlsym 链）——只有 Vulkan layer 成功。代码放在 `dlls/wineandroid.drv/vulkan_ahb.c`，
  但 `vulkan.c` 注释为 "mostly unused when layer is active"。
  <https://github.com/TripleJ160/Winlator-Ludashi-Plus/blob/ludashi-3.0/architecture-analysis.md>
- **GameNative**：自研 `ASurfaceRenderer` → SurfaceFlinger（同路线）+ 自己的 Mesa fork（修 AHB import/ion heap/
  swapchain blit）；PR "add fence and color format conversion to prevent surfaceflinger crash"。
  <https://github.com/utkarshdalal/GameNative/pull/1620> 、<https://github.com/GameNative/mesa/pull/6>
- WinNative：内嵌 Wayland compositor + `winewayland.drv` + dma-buf（仅 Adreno）。

**② wineandroid.drv 的客观风险（维护者原话）**
- Bugzilla **56843**：根因是 **Gralloc V1 在 Android 10+ 不可用**；修法要走 `app_process`+Binder 反向传
  `Surface`，非常规且脆弱。<https://bugs.winehq.org/show_bug.cgi?id=56843>
- MR9874 讨论中维护者：wineandroid **"not been maintained for a long while … at least a few years"**，
  当前补丁 **"hiding an underlying issue rather than fixing it properly"**、怀疑存在未知 Wine 回归且
  **无法 bisect**；同期 winex11 做了 compensating changes 而 wineandroid 被落下。
- 窗口/WSI 驱动是绑定 `win32u` 的 unixlib ⇒ **跨 Wine 版本不可移植**（对比：Vulkan layer 挂在 loader 上，
  与 Wine 版本耦合小）。

**③ Mali（我们的机器是 Mali-G610）黑屏的成熟修法** ← 直接对应我们 B 方案当年的 DXVK 黑屏
- **必须加 `bcn_layer`**：Mali/Xclipse/PowerVR 若无 BCn 支持会"崩溃/显存爆/**黑屏**/缺纹理"；
  文档原话："Black screen in a game → try a different wrapper — this is the classic case the catalog exists for."
- Winlator 的 X11 display server **硬编码 `HAL_PIXEL_FORMAT_BGRA_8888`**；**Mali 无法把该格式的 AHB
  import 进 Vulkan**（`vkGetAndroidHardwareBufferPropertiesANDROID` 失败）⇒ 直渲永远失败、退化 blit。
  已知 hack：对驱动谎报为 `R8B8G8A8`（物理仍 BGRA 序），且**绝不用 `vkCmdBlitImage`**（会自动 swizzle），
  改用 `vkCmdCopyImage`。<https://leegao.github.io/winlator-internals/wrapper/2026/07/28/wsi-woes-mali.html>
- 其它：`vkWaitForPresentKHR` 对绕过 present 的管线会**死等**（FIFO 游戏第 3 帧冻结的真凶，需在 layer 里直接返回
  `VK_SUCCESS`）；AHB 池格式统一 `HAL_PIXEL_FORMAT_BGRA_8888` ↔ `VK_FORMAT_B8G8R8A8_UNORM`；
  `image_count` 必须 `min(sc->image_count, pool)` 否则会命中 `VK_NULL_HANDLE` 冻在第一帧；
  必须注入 `VK_KHR_external_fence_fd`；per-slot 命令缓冲+fence；**先开 `DXVK_LOG_LEVEL=info`** 再调 hang。
- 注意"字节相同 wrapper"陷阱：多个 wrapper 其实是同一文件（换了会得出"没用"的错误结论）。

**④ 我们的最大资产被确认是对的**：Hangover 的 benchmark 与机制论证 —— **"只模拟应用、不模拟 wine" 比
"wine+应用一起模拟"更快**（原话："it proofs that only emulating the application is faster than emulating
wine+application"）。我们的 C 基线正是 **native aarch64 wine（PE 侧 ARM64）** ⇒ 结构上就是 Hangover 那条路 ✓。
（注意：x86 游戏本体仍需要 FEX/Box64 翻译，收益在于不再翻译 Wine。）

**⑤ 路线选项（供决策）**
- **A**：跟上游 master 的 wineandroid 双进程模型（已在跑 master 全量构建）。风险：上游自认该驱动腐烂、
  补丁在掩盖根因、无法 bisect；绑定 win32u 导致每次 Wine 升级可能重做。
- **D（新推荐）**：**保留 native aarch64 wine**，把**显示**改为两条可选后端：
  ① 现有 X11（内嵌 Xlorie）路线 —— 用上面的 Mali 修法（bcn_layer/格式/日志）**先把 B 方案黑屏救活**；
  ② 直呈后端 —— 照 Ludashi-Plus/GameNative 做 **Vulkan 隐式 layer + AHB + SurfaceControl**（不依赖
  wineandroid.drv，且与 Wine 版本耦合小）。
- **A+D**：master 的 wineandroid 作为"实验性后端"并行推进，但不作为唯一路径。

### 16.19 迁移方案（子代理核实版）+ 决定性事实：C 方案没有 Vulkan 路径

#### A. 决定性事实（直接改变路线选择）
- **C 方案（wineandroid.drv）不能跑 Vulkan/DXVK**：`dlls/wineandroid.drv/init.c` 的 `android_drv_funcs`
  **未设置 `.pVulkanInit`**（11.11 与 master 逐字段核对过），且 `dlls/winevulkan/loader.c` 是
  **Win32 surface 专用**（只有 `VK_KHR_win32_surface`；Android 需要 `VK_KHR_android_surface`）。
  ⇒ D3D11/D3D12/DXVK 在 C 方案上无路径（与 GPU 品牌无关）。
- **B' 方案（native aarch64 wine + winex11.drv + 内嵌 X）能跑 Vulkan**：走 `VK_KHR_xlib_surface` ✓
  ⇒ DXVK 可用。GPU 差异：**Adreno = 有开源 Mesa Turnip（最有利）**；**Mali（我们的 PGZ110/G610）= 用厂商
  Vulkan + 必须 `bcn_layer` + BGRA AHB import 限制的已知修法**。
- 结论：**骁龙/Adreno 越多，越应走 B'/D 路线**；C 方案无法承载"跑现代 Windows 游戏"的项目目标。

#### B. 若要跟上游 C 方案，正解是 **wine-11.11**（不是 master）
- **wine-11.11（2026-06-12）= 第一个含完整"分离进程模型"的发布版**（MR !10569 四个 commit 于 2026-05-26
  合入 master，最后一个 `862dea14` "Split Android driver and Wine into separate processes"）。
  11.10 及以前仍是 in-process；**master 不可用**：`user_driver_funcs` 在 2026-06-16 起又加了
  `pCreateClientSurface`，2026-09-14 又给三个 funcs 加 `size` 字段 ⇒ 驱动签名与 11.11 不兼容。
- 新模型分工：**App(JVM) = 显示服务器侧**（`System.load` ntdll.so/win32u.so/wineandroid.so →
  UI 线程调 `wine_init()` → `device.c: wine_init_jni()` 在 `ALooper_forThread()` 上建**抽象 Unix socket
  `\0\Device\WineAndroid`（AF_UNIX SOCK_SEQPACKET）**）；**Java 用 `ProcessBuilder` 起独立 wine 进程**
  （`{loader, "explorer.exe", "/desktop=shell,,android", cmdline}`）。
- 传输：所有窗口/缓冲 ioctl 走该 socket；**event pipe fd 与 AHB fd 用 SCM_RIGHTS 传**；
  尺寸流 = Java `TopView.onSizeChanged` → `wine_desktop_changed` → event pipe → wine 进程
  `process_events` 更新 `screen_width/height` + `init_monitors()`。⇒ **旧模型的"0 尺寸/黑屏"应自动消失**。
- 我们的 9 组补丁里 **8 组可删**（都是迁就 in-process/旧接口的），只需考虑保留 `win32u/driver.c` 的
  驱动兜底（且要按 11.11 上下文重写）。
- 仍需注意：`WineActivity` 类必须留在 **`org.winehq.wine.WineActivity`**（`init.c` 的 `JNI_OnLoad` 用
  `FindClass("org/winehq/wine/WineActivity")` 硬编码）；minSdk/targetSdk 都必须 **28**；
  CI 需要**真正的 aarch64 PE 交叉编译器（llvm-mingw，NDK clang 不够）**、`ANDROID_HOME`、gradle stub 仍可用；
  安装树必须**额外打包 6 个第三方 .so**（freetype/gmp/gnutls/lber/ldap/cups）否则 wine 进程 dlopen 失败。
- 已知未修问题：**桌面分辨率会被 explorer 兜成 800x600**（未合并 MR !10712；规避：命令行改用
  `/desktop=shell,-1x-1,android`）。

#### C. 当前状态
- CI 已触发 `master` 全量构建（run 37129679666，进行中）——按上述结论，**应改跑 `wine-11.11`**；
- 我们手工补的 wine-11.0 路径（设备上已验证到"驱动活着、Java 桌面视图已建、尺寸通道通"）可作为
  B' 方案的对照基线保留。

### 16.20 路线定案：走 D（B' 变体）—— native aarch64 wine + 内嵌 X + winex11.drv

**用户 2026-10-03 决定**：按 D 路线开工（放弃继续投入 wineandroid.drv 作为显示层）。

**为什么（一句话）**：C 方案（wineandroid.drv）**没有 Vulkan 路径**（`android_drv_funcs` 未设 `.pVulkanInit`；
`winevulkan` loader 只有 `VK_KHR_win32_surface`）⇒ DXVK/D3D11/D3D12 无解；而现代/老游戏都依赖 Vulkan
（老游戏走 DXVK-d3d9 / D8VK / wined3d+Zink），所以显示层必须落在能提供 `VK_KHR_xlib_surface` 的 X11 上。

**D 路线的落地次序**：
1. **CI 产出带 `winex11.drv` 的 native aarch64 (bionic) wine** ← 当前卡点
2. App 走内嵌 X（`libXlorie.so`）+ winex11；用 Mali 修法救画面：
   - **必须 `bcn_layer`**（Mali/Xclipse 无 BCn 支持会黑屏/显存爆）
   - Winlator 的 X11 display server 硬编码 `HAL_PIXEL_FORMAT_BGRA_8888`，Mali 无法把该 AHB import 进 Vulkan
     ⇒ 已知 hack：对驱动谎报 `R8B8G8A8`（物理仍 BGRA 序）+ **用 `vkCmdCopyImage` 而非 `vkCmdBlitImage`**
   - 诊断口诀：**先开 `DXVK_LOG_LEVEL=info`**（hang 类问题必做）
3. 之后再做 **Vulkan 隐式 layer + AHardwareBuffer + SurfaceControl** 直呈（照 Ludashi-Plus DAC /
   GameNative ASurfaceRenderer），作为性能后端
4. wine-11.11 的 C 方案（分离进程模型）只作**实验后端**，Vulkan 明确 out of scope

**第 1 步的技术前提（已确认）**：`winex11.drv` 要链接 X11 客户端库，而 aarch64-linux-android 是 **bionic**
（无系统 libX11）⇒ 需引入 **Termux 的 bionic X11 包**作为 sysroot 补充（aarch64 `.deb`），
并在 configure 里把 `--without-x` 换成 `--with-x` + 指向该 sysroot 的 `PKG_CONFIG_PATH/CFLAGS/LDFLAGS`。
已派子代理核实**最小必需库清单**（直接读 wine-11.11 的 `configure.ac` 与 `dlls/winex11.drv/Makefile.in`）、
Termux 包名与下载 URL、以及 CI 的最小改动（≤6 处）。

**老/新游戏兼容性（本路线）**：
- D3D9→DXVK d3d9；D3D8→D8VK；D3D7/DDraw/更老→wined3d(GL/GLES)+Zink；现代→DXVK/VKD3D ✓
- x86(32 位) 老游戏 → wine 的 wow64 后端换 **`wowbox64.dll`**（Hangover 做法）；x86_64 → **FEX/Box64**；
  上游 xtajit 在 bionic 未验证，不作首选
- Win16/DOS 时代 → 需 OTVDM/DOSBox（out of scope）；带反作弊的网游不支持
- **Adreno 用户更有利**（Mesa Turnip）；Mali 需 `bcn_layer` + 格式修法


## 17. 工作原则（用户明确要求，2026-10-03）

> **动工前先检索，不要凭印象写代码/下结论。** 能查到的一律查（上游源码、官方文档、issue/bugzilla、
> MR/提交记录、社区实现），并把**来源链接**记进本文档；查不到或无法核实的，必须显式标注
> **「待真机/待验证」**，不得当作事实使用。

具体执行规则（本项目内强制）：
1. **先取证，再动手**：任何"上游是怎么做的/为什么这么写"的判断，都要落到**可核对的来源**
   （上游文件路径 + 行号，或 URL），不允许用记忆或推测代替。
2. **优先读一手材料**：上游源码（`gitlab.winehq.org/wine/wine` 的 raw 文件）、上游 release notes、
   Bugzilla、wine-devel/wine-gitlab 邮件列表、MR 讨论；其次才是博客/聚合站点。
3. **区分「已核实」与「推测」**：本文档里已核实的写"（已核实+链接）"，推测的写"（推测，待验证）"。
   CI/真机能验证的判据要写清楚（例如 `llvm-readelf -d | grep NEEDED`、`files/log` 关键字）。
4. **优先复用已验证的社区实现**：同一问题若已有项目解决（如 Winlator/GameNative 的 Vulkan layer+AHB、
   Termux 的 bionic X11 包），先研究其做法与坑，再决定自研；不要从零试错。
5. **版本/基线要落到具体 tag**：写清"哪个 release 起包含该改动"及其证据（MR 合并日期/commit/tag 日期），
   不要把滚动 master 当基线。
6. **踩坑要回写**：真机/CI 上出现的失败与修法，连同判据一起写进本文档对应小节，避免重复踩。

**这条原则在本项目已经产生过实际收益（示例）**：
- 检索发现**我们撞的黑屏是上游已修的 Bug 59213**（commit `d98061e1`），避免了继续自行试错；
- 检索发现**上游 MR !10569 已把 wineandroid 改成"分离进程模型"**，说明我们手工补的 in-process 模型已废弃；
- 检索发现**成功的"直呈"实现都不走 wineandroid.drv**（而是 Vulkan layer + AHardwareBuffer + SurfaceControl）；
- 检索（读 wine-11.11 `configure.ac`/`aclocal.m4`）确定 **`winex11.drv` 的最小 X11 依赖**与
  **必须用 `--x-includes/--x-libraries` 而非 `LDFLAGS`** 的关键细节。

### 16.21 ✅ D 路线里程碑 1：带 `winex11.drv` 的 native aarch64（bionic）wine 构建成功

**证据**（CI run 37130788104，`with_x=1`，completed/success；日志见 `cache/x11-ci.log`，7.4MB 全量）：
```
15:02:15  aarch64-linux-android28-clang -o dlls/winex11.drv/winex11.so -shared -Wl,-Bsymbolic \
            -Wl,-soname,winex11.so -Wl,-z,defs  dlls/winex11.drv/{bitblt,brush,clipboard,desktop,
            display,event,graphics,init,keyboard,mouse,opengl,palette,pen,vulkan,window,wintab,
            x11drv_main,xim,xinerama,xrandr,xrender,xvidmode}.o …
15:02:15  winegcc -o dlls/winex11.drv/aarch64-windows/winex11.drv aarch64-windows -Wl,--winebuiltin \
            -shared dlls/winex11.drv/aarch64-windows/dllmain.o dlls/winex11.drv/version.res …
15:02:26  dlls/winex11.drv/winex11.so  ← 出现在 `make install` 的文件清单中
```
⇒ **unix 侧 `winex11.so` 与 PE 侧 `winex11.drv` 都编译并安装成功**，即 D/B' 路线的显示层前提已具备
（`VK_KHR_xlib_surface` / DXVK 需要的就是它）。

**关键落地细节（已核实，来源见 §16.20 与子代理报告）**：
- X11 sysroot 来自 Termux aarch64 `.deb`（`xorgproto` + `libx11` + `libxext` + libxfixes/libxcursor/libxi/
  libxrender/libxrandr + libxcb/libxau/libxdmcp/libandroid-support），CI 里按 `Packages.gz` 索引解析包名，
  `dpkg-deb -x` 后拍平到 `$X11_SYSROOT/usr/`；缺任何一个头/库直接 `exit 1`（防静默降级）。
- configure 必须用 **`--x-includes=$X11_SYSROOT/usr/include --x-libraries=$X11_SYSROOT/usr/lib`**
  （wine 用裸 `AC_PATH_X`；交叉编译时自动探测不做文件系统搜索 ⇒ 只给 `--with-x` 会 `have_x=no`）；
  且必须走 `X_LIBS` 而非 `LDFLAGS`（`WINE_CHECK_SONAME` 把 `-L` 放进 `LIBS`，靠 `LDFLAGS` 会 `-l` 先于 `-L`）。
- 另加：`READELF=llvm-readelf`、`LDD=true`、`PKG_CONFIG_LIBDIR`、`-D__ANDROID_UNAVAILABLE_SYMBOLS_ARE_WEAK__`、
  `--without-wayland --without-xinerama --without-xcomposite --without-xxf86vm`。

**下一步（D 路线第 3 步）**：
1. CI 产物侧还要补：**把 X11 运行时 DSO 打进包**（`libX11.so`(+`.so.6` 软链)/`libXext.so`/`libxcb.so`/
   `libXau.so`/`libXdmcp.so`/`libandroid-support.so` + 可选 `libXfixes/libXcursor/libXi/libXrender/libXrandr`），
   以及 **`share/X11/locale`**（否则 `XSupportsLocale/xim_init` 降级）；App 侧设 `XLOCALEDIR`。
2. App 侧：内嵌 X（`libXlorie.so`）+ `winex11.drv`，Mali 修法（`bcn_layer`、BGRA/RGBA 格式、
   先开 `DXVK_LOG_LEVEL=info`）。
3. **两个必须真机验证的点**（CI 覆盖不到）：① Termux 版 libX11 的 xtrans 补丁把 X socket 路径改成
   `@TERMUX_PREFIX@/tmp/.X11-unix/X`（不是 `/tmp/.X11-unix/X`）⇒ 需软链或确认抽象 socket 兜底；
   ② `share/X11/locale` 必须随包部署 + `XLOCALEDIR`。
### 16.22 X11 运行时打包的失败是"我的硬断言"造成的（已定位，待修）

**取证过程（用新加的 wine-android-logs 小产物）**：
- `make-android.log` 末行：`Wine build complete.` ✓
- `make-install.log` 含 `dlls/winex11.drv/winex11.so` ✓
⇒ **构建与安装都成功了**；失败发生在脚本 5.5/5 我新加的 X11 运行时打包段：
  我凭印象硬断言 `libX11.so/libXext.so/libxcb.so/libXau.so/libxdmcp.so/libandroid-support.so`
  必须存在（缺则 `exit 1`），但 Termux 包里这些文件名/位置未必如我所想 ⇒ **把成功判成了失败**。
  （又一次印证 §17 工作原则：不要凭印象写。）

**待落地的修复**（脚本 `scripts/ci_build_wine_android.sh` 第 319-325 行那一块）：
1. 去掉对 `libxcb/libXau/libxdmcp/libandroid-support` 的硬断言，只保留链接期 NEEDED 的 `libX11.so/libXext.so`；
2. 其余缺失只告警；
3. 把 `$X11ROOT/usr/lib` 的**实际内容**与关键文件定位写进 `wine-out/x11-runtime.log`
   （新加的"纯日志"小产物会收走）⇒ 下一轮就能按真实清单处理，不再靠猜。

**工具层的坑（下次直接用脚本改，不要反复用编辑工具）**：`scripts/ci_build_wine_android.sh` 行尾/空白
与编辑工具的匹配反复不成功 ⇒ 用 Node 脚本按 `indexOf/slice` 精确替换（本文件已多次成功这样做）。
