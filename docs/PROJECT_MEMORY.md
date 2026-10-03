# Proton-droid 项目记忆（Project Memory）

> 最后更新：2026-10-04（B 方案真机打通、游戏出画面；Vulkan WSI 桥已规划，见第 14 节）
> 状态：**B 方案（内嵌 libXlorie）已真机验证** —— 内嵌 X 服务器 ↔ guest ↔ wine 全链路打通，
> 真实 .NET 游戏 GooseDesktop 已在 App 内活跃渲染（§8.2 第 5 条、§11 P0-6）。
> 3D 游戏（DXVK）仍不通，卡在 **Vulkan WSI**（`VK_KHR_xcb_surface is not supported`）→ 方案见 **第 14 节（未动工）**。
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
7. ⬜ 记录性能基线（帧率、CPU/GPU 占用），作为 C 方案的对比依据。
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

## 14. Vulkan WSI 桥（规划中 —— **未动工**）

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

**W0 —— 功能基线（成本最低，先做）**
在 guest 里用 **lavapipe** + Mesa 自带 X11 WSI，把
`DXVK → Vulkan → X11 surface → 内嵌 X 服务器 → EGL → SurfaceView` 整条链路跑通（哪怕只有几帧）。
验收：
1. `DISPLAY=:0 vulkaninfo --summary` 能看到 lavapipe 设备且 **含 `VK_KHR_xcb_surface`**；
2. DXVK 日志出现设备创建、`Present` 计数增长；
3. 真机截图出现 3D 画面（不是黑屏/纯色）。
可调开关：`VK_ICD_FILENAMES`（锁 lavapipe）、`VK_LOADER_DEBUG=all`、`DXVK_LOG_LEVEL=info`、
必要时 `MESA_VK_WSI_PRESENT_MODE=immediate`；变量都能经 `files/extra_env.txt` 免重编注入。

**W1 —— 真 GPU 的 WSI 桥（主攻）**
1. 先按 §14.2 定 ICD 来源（优先「ARM DDK glibc libmali」，否则评估 bionic 桥）；
2. `VK_ICD_FILENAMES=<icd.json>` 指定后，用 `vulkaninfo` 确认设备枚举与扩展面；
3. ICD 缺 X11 WSI → 编译并装 **vulkan-wsi-layer（SHM presenter）**；ICD 自带 X11 WSI → 直接跳过层；
4. 验收：`vulkaninfo` 列出 Mali 设备 + `VK_KHR_xcb_surface`；DXVK 游戏出画面并记录帧率（回填 §11 P0-7 性能基线）。

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

### 14.8 开工时的第一步（照抄）

```sh
# 0) 确认 guest 侧 lavapipe + X11 WSI 是否现成（W0 验证，不改代码）
run-as com.protondroid sh -c 'cd files && \
  PROOT_LOADER=$PWD/bin/loader PROOT_TMP_DIR=$PWD/tmp LD_LIBRARY_PATH=$PWD/bin PROOT_NO_SECCOMP=1 \
  ./bin/proot -r $PWD/rootfs/debian/rootfs -0 -b $PWD/tmp:/tmp \
  /usr/bin/env DISPLAY=:0 PATH=/usr/bin:/bin VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/lvp_icd.aarch64.json \
  VK_LOADER_DEBUG=all vulkaninfo --summary'
# 期望：看到 lavapipe 设备 + VK_KHR_xcb_surface；若报 surface 扩展缺失，就是 W1 要补的层。
```


