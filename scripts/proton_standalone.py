#!/usr/bin/env python3
"""
Proton-droid Standalone Launcher (Enhanced)
===========================================
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
            self.bin_dir = self.files_dir / "bin"

        self.wine_bin = self.bin_dir / "wine"
        self.wineserver_bin = self.bin_dir / "wineserver"
        self.lib_dir = self.files_dir / "lib"
        self.default_pfx_arm64 = self.files_dir / "share" / "default_pfx_arm64"
        
        # 3. 确定目标 Wine Prefix 路径
        if pfx_dir:
            self.pfx_dir = Path(pfx_dir).resolve()
        else:
            user_home = Path(os.environ.get("HOME", "/root"))
            self.pfx_dir = user_home / ".proton_droid_pfx"

    def ensure_prefix(self):
        """确保 Wine Prefix 容器已正确初始化，并正确解析所有符号链接"""
        kernel32_link = self.pfx_dir / "drive_c" / "windows" / "system32" / "kernel32.dll"
        c_drive_link = self.pfx_dir / "dosdevices" / "c:"

        # 检查现有 Prefix 是否健康（是否存在断链的 kernel32.dll）
        prefix_valid = False
        if kernel32_link.exists() and c_drive_link.exists():
            prefix_valid = True

        if not prefix_valid:
            log("PFX", f"正在重新初始化 Proton 运行容器: {self.pfx_dir}")
            if self.pfx_dir.exists():
                shutil.rmtree(str(self.pfx_dir), ignore_errors=True)
            self.pfx_dir.mkdir(parents=True, exist_ok=True)
            
            if self.default_pfx_arm64.exists():
                log("PFX", "正在从 prebuilt default_pfx_arm64 重构绝对路径符号链接...")
                self.clone_default_prefix()
            else:
                log("PFX", "未检测到预建模板，执行 wineboot 初始化新 Prefix...")
                env = self.build_environment()
                subprocess.run([str(self.wine_bin), "wineboot", "-u"], env=env, check=True)
                subprocess.run([str(self.wineserver_bin), "-w"], env=env, check=True)
            
            log("PFX", "Prefix 初始化成功就绪！")

    def clone_default_prefix(self):
        """精准复制 default_pfx 并将相对符号链接解析为实际绝对路径"""
        src_root = str(self.default_pfx_arm64)
        dst_root = str(self.pfx_dir)

        for src_dir, dirs, files in os.walk(src_root):
            rel_dir = os.path.relpath(src_dir, src_root)
            dst_dir = dst_root if rel_dir == '.' else os.path.join(dst_root, rel_dir)
            os.makedirs(dst_dir, exist_ok=True)

            for f in files:
                src_file = os.path.join(src_dir, f)
                dst_file = os.path.join(dst_dir, f)

                if os.path.islink(src_file):
                    # 获取相对链接内容并计算出真实的绝对路径
                    link_target = os.readlink(src_file)
                    abs_target = os.path.normpath(os.path.join(src_dir, link_target))

                    if os.path.lexists(dst_file):
                        os.unlink(dst_file)
                    # 重新建立指向绝对目标的软链接
                    os.symlink(abs_target, dst_file)
                else:
                    if os.path.lexists(dst_file):
                        os.unlink(dst_file)
                    shutil.copy2(src_file, dst_file)

        # 确保 dosdevices 正确建立驱动器映射
        dosdevices_dir = os.path.join(dst_root, "dosdevices")
        os.makedirs(dosdevices_dir, exist_ok=True)

        c_drive = os.path.join(dosdevices_dir, "c:")
        if os.path.lexists(c_drive):
            os.unlink(c_drive)
        os.symlink("../drive_c", c_drive)

        z_drive = os.path.join(dosdevices_dir, "z:")
        if os.path.lexists(z_drive):
            os.unlink(z_drive)
        os.symlink("/", z_drive)

    def build_environment(self):
        """构建针对 Android ARM64 及 Mali GPU 优化的环境变量"""
        env = dict(os.environ)

        # 核心 Wine 与进程路径
        env["WINEPREFIX"] = str(self.pfx_dir)
        env["WINESERVER"] = str(self.wineserver_bin)
        env["WINELOADER"] = str(self.wine_bin)
        env["PATH"] = f"{self.bin_dir}:{env.get('PATH', '')}"

        # 动态链接库搜索路径
        ld_paths = [
            str(self.lib_dir / "wine" / "aarch64-unix"),
            str(self.lib_dir),
            env.get("LD_LIBRARY_PATH", "")
        ]
        env["LD_LIBRARY_PATH"] = ":".join(filter(None, ld_paths))

        # 1. CPU 仿真配置 (FEX-Emu)
        env["FEX_APP_CONFIG_LOCATION"] = str(self.files_dir / "share" / "fex-emu")
        env["FEX_TSOENABLED"] = "1"
        env["FEX_MULTIBLOCK"] = "1"
        env["FEX_MAXINST"] = "500"

        # 2. Wine 核心调度配置
        env["WINEDEBUG"] = "-all"
        env["WINE_LARGE_ADDRESS_AWARE"] = "1"
        # 容器内若没有 futex_waitv 则优雅回退到 wineserver 同步
        env.pop("WINEFSYNC", None)

        # 3. 图形栈配置 (DirectX -> DXVK 映射)
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

        # 4. Mali GPU 专用调优
        env["DXVK_ENABLE_NVAPI"] = "0"
        env["DXVK_LOG_LEVEL"] = "none"
        env["VKD3D_DEBUG"] = "none"

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

        # 5. 显示与音频
        if "DISPLAY" not in env:
            env["DISPLAY"] = ":0"
        if "PULSE_SERVER" not in env:
            env["PULSE_SERVER"] = "127.0.0.1"

        return env

    def run(self, args):
        self.ensure_prefix()
        env = self.build_environment()

        log("RUN", f"正在使用 Wine ARM64 启动: {' '.join(args)}")
        cmd = [str(self.wine_bin)] + args
        try:
            return subprocess.call(cmd, env=env)
        except KeyboardInterrupt:
            log("RUN", "接收到中断信号，退出 Wineserver...")
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
        sys.exit(runner.run(sys.argv[1:]))

if __name__ == "__main__":
    main()
