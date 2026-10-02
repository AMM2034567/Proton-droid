#!/data/data/com.termux/files/usr/bin/bash
# ==============================================================================
# Proton-droid: Automated Setup & Test Runner for Termux
# ==============================================================================

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo -e "${GREEN}====================================================${NC}"
echo -e "${GREEN}       Proton-droid: 真机首次一键装配与测试工具       ${NC}"
echo -e "${GREEN}====================================================${NC}"

# 1. 确保 Termux 依赖完整 (避免重复 pkg update)
if ! command -v proot-distro &>/dev/null || ! command -v termux-x11 &>/dev/null; then
    echo -e "${BLUE}[1/4] 安装 Termux 必要组件 (proot-distro, x11)...${NC}"
    pkg install -y proot-distro x11-repo pulseaudio termux-x11-nightly || true
else
    echo -e "${GREEN}[1/4] Termux 核心组件已就绪，跳过重复安装${NC}"
fi

# 2. 安装 Debian glibc 容器环境 (通过实际 login 检测最稳健)
if proot-distro login debian -- true 2>/dev/null; then
    echo -e "${GREEN}[2/4] Debian 容器已就绪！${NC}"
else
    echo -e "${YELLOW}[2/4] 正在安装 Debian 容器 (仅首次需要)...${NC}"
    proot-distro install debian
fi

# 3. 部署并同步启动器
echo -e "${BLUE}[3/4] 正在同步并部署最新版启动器到容器...${NC}"
proot-distro login --shared-tmp --bind /sdcard:/sdcard debian -- bash -c '
set -e
mkdir -p /opt/proton
if [ ! -f /opt/proton/files/bin-arm64/wine ]; then
    echo "正在释放 Proton 11 ARM64 运行库到 /opt/proton ..."
    tar -xzf /sdcard/Download/ProtonDroid/proton-droid-arm64.tar.gz -C /opt/proton
fi

# 每次都覆盖更新最新的独立启动器
cp -f /sdcard/Download/ProtonDroid/proton_standalone.py /opt/proton/
chmod +x /opt/proton/proton_standalone.py

# 修复 machine-id 警告
if [ ! -f /etc/machine-id ]; then
    echo "c1077ec7f601bda3d3724143452f31c3" > /etc/machine-id
fi
echo "Proton 核心与独立启动器配置完成！"
'

# 4. 启动音频与图形会话
echo -e "${BLUE}[4/4] 启动 Termux-X11 与音频服务...${NC}"

# 启动音频服务 (如果未运行)
if ! pgrep -x "pulseaudio" > /dev/null; then
    pulseaudio --start --load="module-native-protocol-tcp auth-ip-acl=127.0.0.1 auth-anonymous=1" --exit-idle-time=-1 2>/dev/null || true
fi

# 启动 Termux-X11 服务 (如果未运行)
if ! pgrep -f "termux-x11" > /dev/null; then
    termux-x11 :0 -ac &
    sleep 1
fi

# 呼出前台 Termux-X11 窗口
am start -n com.termux.x11/com.termux.x11.MainActivity 2>/dev/null || true

echo -e "${GREEN}====================================================${NC}"
echo -e "${GREEN}   正在启动 Wine 虚拟桌面！请切换到 Termux-X11 界面   ${NC}"
echo -e "${GREEN}====================================================${NC}"

# 在 Debian 容器内运行 Wine
proot-distro login --shared-tmp --bind /sdcard:/sdcard debian -- bash -c '
export DISPLAY=:0
export PULSE_SERVER=127.0.0.1
cd /opt/proton
python3 proton_standalone.py winecfg
'
