#!/data/data/com.termux/files/usr/bin/bash
# ==============================================================================
# Proton-droid: Universal Game Launcher (Virtual Desktop Enabled)
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

# 4. 获取目标游戏绝对路径
TARGET_EXE="$1"
if [ -z "$TARGET_EXE" ]; then
    if [ -f "/sdcard/Download/ProtonDroid/games/goose/GooseDesktop.exe" ]; then
        TARGET_EXE="/sdcard/Download/ProtonDroid/games/goose/GooseDesktop.exe"
    elif [ -f "/sdcard/Download/ProtonDroid/games/osu/osu!.exe" ]; then
        TARGET_EXE="/sdcard/Download/ProtonDroid/games/osu/osu!.exe"
    fi
fi

GAME_DIR=$(dirname "$TARGET_EXE")
EXE_NAME=$(basename "$TARGET_EXE")

echo "=================================================="
echo "Proton 11 ARM64 正在加载目标程序:"
echo "游戏目录: $GAME_DIR"
echo "可执行文件: $EXE_NAME"
echo "=================================================="

# 5. 使用 Wine Virtual Desktop (1280x720) 强制创建实体显示视窗
# 这能彻底解决无边框/透明窗口在 Android X11 下变成黑屏的问题
export PROOT_NO_SECCOMP=1
proot-distro login --shared-tmp debian -- bash -c "
export DISPLAY=:0
export PULSE_SERVER=127.0.0.1
cd '$GAME_DIR'
python3 /opt/proton/proton_standalone.py run explorer /desktop=ProtonDroid,1280x720 './$EXE_NAME'
"
