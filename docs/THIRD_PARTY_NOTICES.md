# 第三方组件与许可（Third-Party Notices）

本 APK 内置了若干第三方二进制/代码。分发时**必须**随之提供对应的许可证与源码获取方式。
本文件是合规清单；正式发布前请逐项确认。

| 组件 | 位置 | 来源 | 许可 | 义务 |
| --- | --- | --- | --- | --- |
| `proot`、`loader`、`loader32` | `app/src/main/assets/proot/` | Termux 官方包 `proot_5.1.107.96_aarch64.deb`（proot-me/PRoot + Termux 补丁） | **GPL-2.0-or-later** | 提供源码（上游仓库）与许可证全文 |
| `libtalloc.so.2` | `app/src/main/assets/proot/` | Termux 官方包 `libtalloc_2.5.0_aarch64.deb`（Samba talloc） | **LGPL-3.0-or-later** | 提供许可证；动态链接，需保留替换库的可能 |
| `libandroid-shmem.so` | `app/src/main/assets/proot/` | Termux 官方包 `libandroid-shmem_0.7_aarch64.deb` | 见其包内 copyright（MIT 风格） | 保留版权声明 |
| `libXlorie.so` | `app/src/main/jniLibs/arm64-v8a/` | 从 `com.termux.x11` APK（版本 `1.03.01-0e1ebb4-01.10.26`，versionCode 15）提取，源码 [termux/termux-x11](https://github.com/termux/termux-x11)（Xorg server 派生：xserver/pixman/xkbcomp/xorgproto…） | **GPL-3.0-or-later** | **必须**提供完整对应源码与许可证（Xorg 派生 + 本项目对其 Java 胶水的最小移植） |
| Java 胶水（`com.termux.x11.CmdEntryPoint` / `LorieView` / `MainActivity`） | `app/src/main/java/com/termux/x11/` | 依据 termux/termux-x11 上游实现裁剪改写 | 同上，**GPL-3.0-or-later** | 发布时随源码提供 |
| Proton 11 ARM64 运行库（`proton-droid-arm64-*.tar.gz`） | 运行期下载，不进 APK | Valve Software / Proton | Valve 的 Steam 客户端许可（**非**开源许可） | 分发编译产物前需确认授权；APK 内不打包可降低风险 |
| Debian glibc rootfs（`rootfs.tar.gz`） | 运行期下载，不进 APK | Debian 项目 | 各软件包各自的许可证 | 发布 rootfs 时随附 `/usr/share/doc/*/copyright` |

## 当前状态（2026-10-03）

- ✅ APK 内已含 `proot` / `libtalloc` / `libandroid-shmem` / `libXlorie.so`
- ⚠️ 尚未在仓库内附 GPL 许可证全文与源码获取说明
- ⚠️ `libXlorie.so` 为**预编译二进制**：GPLv3 要求提供“对应源码”（corresponding source），
  包括其构建脚本（termux/termux-x11 的 `lorie` 模块 + recipes）。建议：
  1. 仓库根目录加 `LICENSE`（本项目自身许可，需定）与 `licenses/` 目录放各组件全文；
  2. App 内提供「开源许可」页面，列出组件、版本、许可证与源码 URL；
  3. Release 说明中给出 `libXlorie.so` 的版本与对应 commit（`0e1ebb4`）。

> 待办（记忆 §11 P0 附带项）：把上述 3 条落地，否则正式版分发存在 GPL 合规风险。
