# Proton-droid 路线 B 深度架构解析

本文详细解析 Proton-droid 采用的 **路线 B（Proton 11 + FEX + ARM64 原生架构）** 的底层技术原理，以及对比传统 Winlator/Box64 方案的技术优越性。

---

## 一、 为什么选择路线 B（原生 ARM64 + ARM64EC）？

### 传统移动端方案（Winlator / Mobox 模式）的弊端
1. **整栈全模拟**：
   - 游戏代码是 x86_64；
   - Wine 也是 x86_64；
   - DXVK（Direct3D 9/11 转译器）也是 x86_64；
   - 整个应用层所有指令全部要被外部模拟器（如 Box64）拦截并翻译，性能瓶颈极大。
2. **多线程互斥损耗严重**：
   - x86 采用强内存模型（TSO），ARM 采用弱内存模型。整个 Wine 在弱内存模型上由模拟器仿真原子操作和锁机制时，开销倍增。

---

### 路线 B：Valve Proton 11 ARM64 架构精髓

Proton 11 通过引入 **FEX-Emu 作为 Wine UnixLib 插件** 和 **ARM64EC (ARM64 Emulation Compatible)** 彻底重构了该调用链：

```
+-------------------------------------------------------------+
|               Windows 游戏 PE 进程空间 (x86_64)               |
|                                                             |
|  [ 游戏代码 (x86_64) ]                                      |
|          |                                                  |
|          v (仅游戏逻辑经过 FEX JIT 翻译)                      |
|  [ FEX Core JIT (进程内模块) ]                                |
|          |                                                  |
|          v (无缝调用 ARM64EC 导出的 Win32 API)               |
|  +-------------------------------------------------------+  |
|  |   Proton 11 原生 ARM64 / ARM64EC 组件                  |  |
|  |                                                       |  |
|  |   • DXVK 2.x (arm64ec-windows)                        |  |
|  |     --> 将 Direct3D 指令转译为 Vulkan (原生机器码执行)  |  |
|  |   • VKD3D-Proton (arm64ec-windows)                    |  |
|  |     --> 将 Direct3D 12 转译为 Vulkan (原生机器码执行) |  |
|  |   • wine-core & wineserver (aarch64-unix 原生)        |  |
|  |   • GStreamer / FFmpeg (aarch64-unix 原生多媒体解码)   |  |
|  +-------------------------------------------------------+  |
+-------------------------------------------------------------+
                               |
                               v (标准 Vulkan 1.3 调用)
                [ 移动 GPU: ARM Mali-G610 驱动 ]
```

### 关键突破点：
1. **DXVK 以原生 ARM 运行**：
   DirectX 到 Vulkan 的繁重指令生成和着色器解析工作，以 **100% ARM 原生机器码** 执行，不再经过任何 CPU 仿真，这是帧率大幅超越旧方案的根本原因。
2. **FEX UnixLib 深度挂载**：
   FEX 编译为 `wow64fex` / `arm64ecfex` 动态库，直接实现 Wine 的 `__wine_unix_call_funcs` 接口，在底层调度 CPU 上下文，跳过了传统外部进程调度的上下文切换损耗。
3. **内核级 TSO 加速**：
   通过 `prctl(PR_SET_MEM_MODEL, PR_SET_MEM_MODEL_TSO)` 激活硬件内存屏障优化，最大化释放 CPU 性能。

---

## 二、 Mali GPU (天玑 8100 / G610) 针对性适配

高通 Adreno 拥有开源的 Turnip 驱动，而天玑芯片使用的是 ARM 原厂专有闭源驱动。在诊断中，我们确认了你的设备具备 **Vulkan 1.3** 支持。

针对 Mali GPU 的特点，Proton-droid 做了以下定制适配：
1. **`dxvk.conf` 调优**：
   - `d3d11.relaxedBarriers = True`：缓解 Mali 瓦片渲染管线（Tile-Based）对于屏障同步过于激进的卡顿。
   - `dxgi.syncInterval = 0`：关闭垂直同步等待，减少移动端双重缓冲延迟。
2. **扩展兼容防护**：
   - 关闭未被 Mali 支持的 `NVAPI` 扩展查询。
   - 依赖 DXVK 的内部回退机制替代缺失的 `VK_EXT_transform_feedback`。

---

## 三、Android 侧启动链路（修复后的实际实现）

```
MainActivity ──Intent──> GameViewActivity ──startForegroundService──> ProtonForegroundService
                                                                            │
                                                     ProtonProcessManager.launchGame()
                                                                            │
                            fork() + execve()  （JNI，错误通过 CLOEXEC 管道回传 errno）
                                                                            │
                            files/bin/proot  ← APK assets 释放的 aarch64 静态工具链
                                                                            │
                     proot -r <guestRoot> -0 --kill-on-exit -b ... (ptrace 接管)
                                                                            │
                     Debian 13 glibc guest  (/sdcard 1:1 bind)
                                                                            │
                     /opt/proton/files/bin-arm64/wine explorer /desktop=ProtonDroid,1280x720 <game.exe>
                                                                            │
                                            DISPLAY=:0 ──> Termux-X11 (X 服务器)
```

### 三个必须遵守的硬性约束

1. **`targetSdk` 必须为 28**：Android 10+ 的 W^X 规则会同时禁止
   *执行* 私有目录内的二进制（`execve` 返回 `EACCES`）与 *可执行映射*
   （`mmap PROT_EXEC`，Wine 加载 PE 镜像时报 `noexec filesystem?`）。
   只有 `targetSdk <= 28` 的应用才会落入允许这两件事的 `untrusted_app_27` 域。
2. **PRoot 工具链必须是与设备同架构的原生 ELF**：`proot` / `loader` /
   `libtalloc.so.2` / `libandroid-shmem.so` 均为 **aarch64**；
   上游 `proot-me/proot` release 只提供 x86_64 资产，不能直接使用。
   安装器会读取 ELF `e_machine` 做校验（`183 = AArch64`，`62 = x86-64`）。
3. **guest rootfs 的层次不能假定**：`rootfs.tar.gz` 可能是
   `debian/rootfs/...` 这种带前缀的打包结果，因此
   `ProtonLayout.resolveGuestRoot()` 会探测真正的 Linux 根，
   并把 Proton 载荷放进 `<guestRoot>/opt/proton`。

### 已知的运行期环境要点

| 环境变量 / 参数 | 作用 |
| --- | --- |
| `PROOT_LOADER` / `PROOT_LOADER_32` | 指向 assets 释放出的 `loader` / `loader32`，否则 PRoot 无法注入 |
| `PROOT_TMP_DIR` | Android 没有 `/tmp`，必须指向应用私有可写目录 |
| `LD_LIBRARY_PATH` | 同时包含 PRoot 自身依赖目录（libtalloc / libandroid-shmem）与 guest 内 Proton 库目录 |
| `WINEDLLPATH` | `<proton>/files/lib/vkd3d:<proton>/files/lib/wine`，Wine 据此定位内置 DLL 与 nls 数据 |
| `-b .../files/share/wine:/usr/share/wine` | Proton 载荷编译期数据目录就是 `/usr/share/wine`，不 bind 会导致 `failed to load l_intl.nls` 并崩溃 |
| `-0 --kill-on-exit` | 伪 root 身份；游戏退出时一并回收 PRoot 子进程 |

### 显示输出说明

`GameViewActivity` 的 `SurfaceView` 通过 NDK 持有 `ANativeWindow` 句柄，
但 Windows 游戏画面由 **Wine 的 X11 后端**渲染，因此实际显示在
**Termux-X11** 窗口中：应用启动前会用 `NativeBridge.checkX11Display(0)`
探测 `@/tmp/.X11-unix/X0`（抽象 socket，不受文件系统权限限制），
未检测到时会直接提示用户先执行 `termux-x11 :0`。

