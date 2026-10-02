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
