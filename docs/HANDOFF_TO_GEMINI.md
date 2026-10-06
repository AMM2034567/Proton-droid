# 交接给 Gemini / Antigravity —— Proton-droid（C→D 方案迁移）

> 用法：把下面「## 给 Gemini 的提示词」整段复制给 Gemini 作为首条消息；细节让它按提示词里的路径去读文件。

---

## 一、会话与日志记录（存档信息）

| 项 | 值 |
|---|---|
| 项目工作目录 | `D:\cargoproject\Proton-droid`（git 仓库，远端 `https://github.com/AMM2034567/Proton-droid`）|
| **本次会话 id** | `session-e533cda9-1322-41ae-aa95-667cfb07ed7f`（DSH；`$env:DSH_HOME=C:\Users\李金玲\.dsh`）|
| 被中断的旧会话 id | `session-f8e7dcc2-3e2d-4283-9e89-3e259e12fee5`（损坏：seq 5359 缺 `role:"user"`；已恢复）|
| 旧会话恢复产物 | `.session-recovery/out/session.jsonl`（**5409 事件**）+ `.session-recovery/` 下的解析/补丁生成脚本 |
| **项目记忆（核心）** | `docs/PROJECT_MEMORY.md`（**145KB**，§1–§17；所有结论带来源链接与真机证据）|
| 本次新增小节 | §16.8–§16.25（C 方案全链路调试、上游调研、D 路线定案与里程碑）、**§17 工作原则** |
| 关键日志 | `cache/x11-ci.log`(7MB, 带 X 构建全量)、`cache/x11-ci2.log`、`cache/v12*.log`、`cache/wine-log*.txt`(真机 wine stderr) |
| 关键产物 | `cache/x11-runtime-arm64-11.0-r2.tar.gz`(1MB)、`cache/wine-unix-x11-11.0-r1.tar.gz`(8.19MB)、`cache/wine-delta-latest.tar.gz` |
| Release（App 拉取源） | tag `wine-android-11.0-r1`：安装树 174MB + `wine-unix-x11-11.0-r1`(8.19MB) + `x11-runtime-arm64-11.0-r1`(0.99MB，**缺 libXdmcp.so，勿用**) + **`x11-runtime-arm64-11.0-r2`(1MB，已修，用这个)** |

---

## 二、给 Gemini 的提示词（直接粘贴）

```
你是 Proton-droid 项目的接手工程师。工作目录 D:\cargoproject\Proton-droid（Windows，pwsh；git 远端
https://github.com/AMM2034567/Proton-droid）。先做三件准备工作，再动手：

【强制工作原则（用户明确要求，已写进 docs/PROJECT_MEMORY.md §17）】
动工前**必须检索取证**，不要凭印象：上游源码、官方文档、Bugzilla、MR/邮件列表、社区实现；
每条结论附来源（文件路径+行号 或 URL）；查不到/无法核实的显式标注「待验证」。
本项目已因此发现：我们撞的黑屏是上游已修 Bug 59213、上游 MR !10569 已把 wineandroid 改成"分离
进程模型"、成功的"直呈"实现都不走 wineandroid.drv 而是 Vulkan layer+AHB、winex11 的 X11 最小依赖与
`--x-includes/--x-libraries` 的正确用法。

【必读文件】
1) docs/PROJECT_MEMORY.md —— 全部历史与技术积累（§1–§17）。重点：
   §16.16–§16.19（上游调研与路线定案）、§16.20–§16.25（D 路线里程碑与 App 侧方案）、
   §16.23（三 SoC：骁龙 Adreno / 联发科 Mali / 三星 Exynos(Xclipse) 适配差异）、§17（工作原则）。
2) docs/HANDOFF_TO_GEMINI.md —— 本交接文档（含命令速查）。
3) scripts/ci_build_wine_android.sh + .github/workflows/wine-android.yml —— 构建管线。

【项目目标与已定路线】
目标：Android 上原生跑 Windows 游戏（原生 arm64，不做 x86 模拟 wine 本体）。
- 已放弃 C 方案（wineandroid.drv 直呈）：它**没有 Vulkan 路径**（android_drv_funcs 未设 .pVulkanInit；
  winevulkan loader 只有 VK_KHR_win32_surface）⇒ DXVK/D3D11/D3D12 无解；且上游维护者自认该驱动腐烂。
- 现走 **D 路线**：**native aarch64（bionic）wine + 内嵌 X 服务器（Termux-X11 的 libXlorie）+ winex11.drv**，
  Vulkan 走 `VK_KHR_xlib_surface` ⇒ DXVK 可用。CPU 侧 x86/x86_64 游戏将来交给 FEX/Box64。

【当前状态（截至交接）】
✅ CI 能构建出**带 winex11.drv 的 native aarch64 wine**（`with_x=1`；里程碑 1/2，证据见 §16.21/§16.24）
✅ X11 运行时（11 个 DSO + share/X11/locale）已打包并发布为 `x11-runtime-arm64-11.0-r2.tar.gz`
✅ App 载荷层已接入：`WineAndroidPayload.ensureX11Installed(context)`（幂等；覆盖包→files/arm64-v8a/lib/wine/，
   X11 DSO 拍平到 files/arm64-v8a/lib/，locale→files/share/X11/locale）——已本地编译通过
⏳ 待做：**显示接线**（第 3 步-2）：新增 `native-x11` 后端，在 `GameViewActivity` 里启动内嵌 X（XServer +
   LorieView），再用 `NativeBridge.forkAndExec` 起 native wine：
     command = <files>/arm64-v8a/lib/wine/aarch64-unix/wine
     args    = ["c:\\windows\\system32\\winecfg.exe"]（冒烟）
     env     = DISPLAY=:0, WINEPREFIX=<files>/prefix-x11(新前缀), WINEDLLPATH=<files>/arm64-v8a/lib/wine,
               LD_LIBRARY_PATH=<files>/arm64-v8a/lib:<files>/arm64-v8a/lib/wine/aarch64-unix:<nativeLibDir>,
               XLOCALEDIR=<files>/share/X11/locale, WINEDEBUG(读 files/winedebug), WINEDEBUGLOG=<files>/log-x11
   ⚠️ 不得改动现有 `x11`（B 方案 proot x86_64 wine）与 `android`（C 方案 WineActivity）两条分支的行为。
⏳ 之后：真机冒烟 → Mali 修法（我们的 PGZ110 是 Mali-G610：必装 `bcn_layer`；Mali 不能 import
   HAL_PIXEL_FORMAT_BGRA_8888 → blit 回退；先开 DXVK_LOG_LEVEL=info）→ 三 SoC 设置档位（§16.23）。

【真机前置条件（务必先做）】
1) 开发者选项开 **"Disable child process restrictions"**（phantom process killer 会以 signal 9 杀 wine 子进程；
   Samsung One UI 已验证有效；这极可能是之前"explorer/winecfg 子进程静默消失"的原因，见 §16.23）
2) App 保持 **targetSdk=28**（否则 Android 10+ 的 W^X 让 app 私有目录无法 execve；上游 wine 也这么做）
3) 电池设为"不受限制"

【三个必须真机验证的点（CI 覆盖不到）】
1) X socket 路径：Termux 版 libX11 带 xtrans 补丁 ⇒ socket 是 `@TERMUX_PREFIX@/tmp/.X11-unix/X0`
   （**不是** /tmp/.X11-unix/X0）⇒ 需软链或确认抽象 socket 兜底
2) `share/X11/locale` 必须随包部署并设 `XLOCALEDIR`（否则 XSupportsLocale/xim_init 降级）
3) winex11.so 的 dlopen 依赖链（libX11/libXext/libxcb/libXau/libXdmcp/libandroid-support）都在 LD_LIBRARY_PATH 里

【工作方式与环境约定】
- **构建一律走 CI**（本地不出 wine 产物）；CI 是编译器。触发：
  `gh workflow run "wine-android.yml" -f wine_ref=wine-11.0 -f api_level=28 -f with_x=1 --ref main`
  （`with_x=0` = 原 wineandroid 构建；`wine_ref=wine-11.11` 可切到上游分离进程模型，会自动不打本地补丁）
- **禁止下 351MB 大包**：CI 另有小产物 `wine-android-delta`(≈8MB) 与 `wine-android-logs`(几 MB)；
  失败排查一律用 `wine-android-logs`（`gh run download <id> -n wine-android-logs -D cache/xlogs`）。
  注意 `gh api .../jobs/<id>/logs` 会在 ~1MB/7k 行处**截断**，看不到尾部错误。
- 本地 App 编译：`$env:GRADLE_USER_HOME="$PWD\.gradle-home"; $env:JAVA_HOME="D:\jdk-17.0.20.101-hotspot";
  .\gradlew.bat :app:assembleDebug --offline --console=plain`（几秒）；APK 在 app/build/outputs/apk/debug/。
- 真机：adb 在 `D:\XSBDownload\SDK\platform-tools\adb.exe`；设备能力受限（ColorOS：shell 无
  MANAGE_APP_OPS_MODES，不能 appops/pm grant）⇒ 载荷用 `adb push /data/local/tmp` + `run-as ... cp`；
  wine 的 stderr 不进 logcat，靠 `<files>/winedebug` 文件 + `WINEDEBUGLOG` 落到 `<files>/log` 再看。
- 提交推送：网络常抖动（github:443 时断），push 失败就重试（脚本里用循环重试 6~8 次）；
  提交信息里的换行在 PowerShell 里要用文件 + `git commit -F` 传。
- 工程习惯：改脚本用 Node 脚本按 indexOf/slice 精确替换（这个仓库的 shell 脚本用编辑工具常被 CRLF/空白卡住）；
  Kotlin 块注释**会嵌套**（写 `lib/*.so` 这种会触发 Unclosed comment）。

【D 路线 CI 的关键技术点（已在脚本里，别改坏）】
- 目标 configure 必须用 `--x-includes=$X11_SYSROOT/usr/include --x-libraries=$X11_SYSROOT/usr/lib`
  （wine 用裸 AC_PATH_X；交叉编译下只给 --with-x 会 have_x=no；且必须走 X_LIBS 而非 LDFLAGS）
- X11 sysroot 来自 Termux aarch64 .deb（按 Packages.gz 索引解析包名→下载→dpkg-deb -x→拍平到 $X11_SYSROOT/usr/）
  ⚠️ 包名 **libxdmcp 是小写**，文件名 **libXdmcp.so** 是大写 D（这个大小写把 r1 资产搞坏过）
- 另需 `READELF=llvm-readelf`、`LDD=true`、`PKG_CONFIG_LIBDIR`、`-D__ANDROID_UNAVAILABLE_SYMBOLS_ARE_WEAK__`
- 补丁只在 `wine_ref=wine-11.0` 基线时应用（scripts/patches/wineandroid-wine11.0.patch，7 文件）

【验收判据】
- CI：`make-android.log` 末行 `Wine build complete.` + `make-install.log` 含 `dlls/winex11.drv/winex11.so`
- App：`:app:assembleDebug` 成功；未影响另外两条后端
- 真机：logcat 出现 wine 进程 pid、`<files>/log-x11` 有 wine 输出、界面上出现窗口（而非黑屏）
```

---

## 三、命令速查（PowerShell）

```powershell
# 触发带 X 的构建
gh workflow run "wine-android.yml" -f wine_ref=wine-11.0 -f api_level=28 -f with_x=1 --ref main
# 看最新 run 状态
gh run list --workflow "wine-android.yml" --limit 3
# 拿小产物（增量 / 纯日志）
gh run download <runId> -n wine-android-delta -D cache\xdelta
gh run download <runId> -n wine-android-logs  -D cache\xlogs
# 本地编译 APK
$env:GRADLE_USER_HOME="$PWD\.gradle-home"; $env:JAVA_HOME="D:\jdk-17.0.20.101-hotspot"
.\gradlew.bat :app:assembleDebug --offline --console=plain
# 真机：装 APK / 换显示后端 / 拉 wine 日志
& 'D:\XSBDownload\SDK\platform-tools\adb.exe' install -r app\build\outputs\apk\debug\app-debug.apk
& adb shell "run-as com.protondroid sh -c 'echo native-x11 > files/display_backend.txt'"
& adb shell "run-as com.protondroid cat files/log-x11"
```

---

## 四、下一步（按序）

1. **显示接线**（第 3 步-2）：`DisplayBackend.NATIVE_X11 = "native-x11"` + `GameViewActivity` 新分支
   （内嵌 X → `forkAndExec` native wine）→ 本地编译 → 提交
2. **真机冒烟**：装 APK → 设 `display_backend.txt=native-x11` → 看 `<files>/log-x11` 与屏幕
3. **Mali 修法**（按 §16.23）：`bcn_layer`、BGRA/RGBA 处理、`DXVK_LOG_LEVEL=info`
4. **三 SoC 设置档位**：Adreno→Turnip（BCn 关模拟）；Mali/Xclipse→厂商驱动 + wrapper + bcn_layer
   （Xclipse 另有 5 项适配，见 §16.23）
5. 之后才是 x86 游戏的 FEX/Box64 接入与性能调优
