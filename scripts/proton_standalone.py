#!/usr/bin/env python3
"""
Proton-droid Standalone Launcher
================================
A decoupled, lightweight runner derived from Valve's Proton for Android/ARM64.
Bypasses Steam Client dependencies, launches Windows games directly via
native Wine ARM64 + in-process FEX-Emu + ARM64EC DXVK/VKD3D.

Usage:
    python3 proton_standalone.py run /path/to/game.exe [args...]
    python3 proton_standalone.py winecfg
    python3 proton_standalone.py taskmgr
    python3 proton_standalone.py cmd
"""

import os
import sys
import shutil
import subprocess
import platform
from pathlib import Path

def log(tag, msg):
    print(f"[{tag}] {msg}", flush=True)

class ProtonDroidRunner:
    def __init__(self, proton_base_dir=None, pfx_dir=None):
        # 1. 确定 Proton 产物根目录
        if proton_base_dir is None:
            self.base_dir = Path(__file__).resolve().parent
            if not (self.base_dir / "files").exists() and (self.base_dir.parent / "files").exists():
                self.base_dir = self.base_dir.parent
        else:
            self.base_dir = Path(proton_base_dir).resolve()

        self.files_dir = self.base_dir / "files"
        
        # 2. 检查 ARM64 架构二进制文件
        self.bin_dir = self.files_dir / "bin-arm64"
        if not self.bin_dir.exists():
            # 回退到常规 bin
            self.bin_dir = self.files_dir / "bin"

        self.wine_bin = self.bin_dir / "wine"
        self.wineserver_bin = self.bin_dir / "wineserver"
        self.wineboot_bin = self.bin_dir / "wineboot"
        self.lib_dir = self.files_dir / "lib"
        self.default_pfx_arm64 = self.files_dir / "share" / "default_pfx_arm64"
        
        # 3. 确定目标 Wine Prefix 路径
        if pfx_dir:
            self.pfx_dir = Path(pfx_dir).resolve()
        else:
            user_home = Path(os.environ.get("HOME", "/data/local/tmp"))
            self.pfx_dir = user_home / ".proton_droid_pfx"

    def ensure_prefix(self):
        """确保 Wine Prefix 容器已正确初始化"""
        if not (self.pfx_dir / "drive_c").exists():
            log("PFX", f"正在初始化 Proton 运行容器: {self.pfx_dir}")
            self.pfx_dir.mkdir(parents=True, exist_ok=True)
            
            # 如果存在预构建的 ARM64 模板 prefix，直接全量复制加速启动
            if self.default_pfx_arm64.exists():
                log("PFX", "正在从 prebuilt default_pfx_arm64 镜像克隆环境...")
                shutil.copytree(str(self.default_pfx_arm64), str(self.pfx_dir), dirs_exist_ok=True)
            else:
                log("PFX", "执行 wineboot 初始化新 Prefix...")
                env = self.build_environment()
                subprocess.run([str(self.wine_bin), "wineboot", "-u"], env=env, check=True)
                subprocess.run([str(self.wineserver_bin), "-w"], env=env, check=True)
            
            log("PFX", "Prefix 初始化就绪！")

    def build_environment(self):
        """构建针对 Android ARM64 及 Mali GPU 优化的环境变量"""
        env = dict(os.environ)

        # 核心目录注入
        env["WINEPREFIX"] = str(self.pfx_dir)
        env["PATH"] = f"{self.bin_dir}:{env.get('PATH', '')}"

        # 动态库加载路径
        ld_paths = [
            str(self.lib_dir / "wine" / "aarch64-unix"),
            str(self.lib_dir),
            env.get("LD_LIBRARY_PATH", "")
        ]
        env["LD_LIBRARY_PATH"] = ":".join(filter(None, ld_paths))

        # 1. CPU 仿真配置 (FEX-Emu)
        env["FEX_APP_CONFIG_LOCATION"] = str(self.files_dir / "share" / "fex-emu")
        env["FEX_TSOENABLED"] = "1"         # 硬件内存一致性模拟加速
        env["FEX_MULTIBLOCK"] = "1"         # 多基本块 JIT 编译
        env["FEX_MAXINST"] = "500"

        # 2. Wine 核心调度优化
        env["WINEDEBUG"] = "-all"           # 关闭繁重调试日志以获得最佳 FPS
        env["WINEFSYNC"] = "1"              # 启用 Futex 同步
        env["WINE_LARGE_ADDRESS_AWARE"] = "1"

        # 3. 图形栈配置 (DXVK & Mali 针对性调优)
        # 强制 Direct3D 9/10/11 优先使用 原生 ARM64EC DXVK
        dll_overrides = {
            "d3d11": "n,b",
            "dxgi": "n,b",
            "d3d9": "n,b",
            "d3d10core": "n,b",
            "d3d12": "n,b"
        }
        override_str = ";".join([f"{k}={v}" for k, v in dll_overrides.items()])
        existing_overrides = env.get("WINEDLLOVERRIDES", "")
        env["WINEDLLOVERRIDES"] = f"{override_str};{existing_overrides}" if existing_overrides else override_str

        # 4. Mali GPU 专用 Vulkan 兼容开关
        env["DXVK_ENABLE_NVAPI"] = "0"
        env["DXVK_LOG_LEVEL"] = "none"
        env["VKD3D_DEBUG"] = "none"

        # 自动生成/写入针对 Mali GPU 的 dxvk.conf
        dxvk_conf_path = self.pfx_dir / "dxvk.conf"
        if not dxvk_conf_path.exists():
            with open(dxvk_conf_path, "w") as f:
                f.write(
                    "# Auto-generated for Android Mali GPU\n"
                    "dxgi.syncInterval = 0\n"
                    "d3d11.maxTessFactor = 64\n"
                    "d3d11.relaxedBarriers = True\n"
                    "dxvk.tearFree = False\n"
                )
            env["DXVK_CONFIG_FILE"] = str(dxvk_conf_path)

        # 5. 显示与输入
        if "DISPLAY" not in env:
            env["DISPLAY"] = ":0"           # 默认指向 Termux-X11 服务端口

        return env

    def run(self, args):
        self.ensure_prefix()
        env = self.build_environment()

        log("RUN", f"正在使用 Wine ARM64 启动: {' '.join(args)}")
        cmd = [str(self.wine_bin)] + args
        try:
            return subprocess.call(cmd, env=env)
        except KeyboardInterrupt:
            log("RUN", "接收到中断信号，正在退出 Wineserver...")
            subprocess.run([str(self.wineserver_bin), "-k"], env=env)
            return 0

def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)

    runner = ProtonDroidRunner()
    verb = sys.argv[1]

    if verb == "run":
        if len(sys.argv) < 3:
            log("ERROR", "用法: python3 proton_standalone.py run /path/to/game.exe")
            sys.exit(1)
        sys.exit(runner.run(sys.argv[2:]))
    elif verb in ("winecfg", "taskmgr", "cmd", "explorer"):
        sys.exit(runner.run([verb]))
    else:
        # 直接把参数当作可执行程序传递
        sys.exit(runner.run(sys.argv[1:]))

if __name__ == "__main__":
    main()
