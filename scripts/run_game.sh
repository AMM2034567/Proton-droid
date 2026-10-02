#!/data/data/com.termux/files/usr/bin/bash
# ==============================================================================
# Proton-droid: Universal Game Launcher
# ==============================================================================

# 1. 确保音频服务运行
if ! pgrep -x "pulseaudio" > /dev/null; then
    pulseaudio --start --load="module-native-protocol-tcp auth-ip-acl=127.0.0.1 auth-anonymous=1" --exit-idle-time=-1 2>/dev/null || true
fi

# 2. 确保 Termux-X11 服务运行
if ! pgrep -f "termux-x11" > /dev/null; then
    termux-x11 :0 -ac &
    sleep 1
fi

# 3. 唤醒 Termux-X11 窗口
am start -n com.termux.x11/com.termux.x11.MainActivity 2>/dev/null || true

# 4. 获取目标游戏路径
TARGET_EXE="$1"
if [ -z "$TARGET_EXE" ]; then
    # 默认自动检测 osu 安装器
    TARGET_EXE="/sdcard/Download/ProtonDroid/games/osu/osu!install.exe"
fi

echo "=================================================="
echo "Proton 11 ARM64 正在加载目标程序:"
echo "$TARGET_EXE"
echo "=================================================="

# 5. 在容器内通过 Proton 独立启动器运行
proot-distro login --shared-tmp debian -- bash -c "
export DISPLAY=:0
export PULSE_SERVER=127.0.0.1
cd /opt/proton
python3 proton_standalone.py run '$TARGET_EXE'
"
