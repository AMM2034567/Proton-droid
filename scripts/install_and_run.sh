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

# 1. 确保 Termux 依赖完整 (proot-distro, x11-repo, pulseaudio)
echo -e "${BLUE}[1/4] 检查并安装 Termux 基础组件...${NC}"
pkg update -y
pkg install -y proot-distro x11-repo pulseaudio termux-x11-nightly || true

# 2. 安装 Debian glibc 容器环境 (如果尚未安装)
echo -e "${BLUE}[2/4] 准备 Debian (glibc aarch64) 运行容器...${NC}"
if ! proot-distro list 2>/dev/null | grep -q "debian.*installed"; then
    echo -e "${YELLOW}正在安装 Debian 容器，这需要大约 1~2 分钟...${NC}"
    proot-distro install debian
else
    echo -e "${GREEN}Debian 容器已安装！${NC}"
fi

# 3. 在 Debian 容器中安装 glibc 图形依赖并解压 Proton
echo -e "${BLUE}[3/4] 在容器内释放 Proton 11 ARM64 核心并配置依赖...${NC}"

proot-distro login --shared-tmp --bind /sdcard:/sdcard debian -- bash -c '
set -e
apt-get update -y
apt-get install -y python3 libx11-6 libxext6 libvulkan1 libgl1 libfreetype6 libfontconfig1 x11-utils procps

mkdir -p /opt/proton
if [ ! -f /opt/proton/files/bin-arm64/wine ]; then
    echo "正在解压 Proton 11 ARM64 运行库到 /opt/proton (约需 20~30 秒)..."
    tar -xzf /sdcard/Download/ProtonDroid/proton-droid-arm64.tar.gz -C /opt/proton
fi

cp -f /sdcard/Download/ProtonDroid/proton_standalone.py /opt/proton/
chmod +x /opt/proton/proton_standalone.py
echo "Proton 核心与独立启动器已就绪！"
'

# 4. 启动 Termux-X11 并进入 Wine 桌面测试
echo -e "${BLUE}[4/4] 启动 Termux-X11 图形会话并运行 Wine 测试...${NC}"

# 启动音频服务
pulseaudio --start --load="module-native-protocol-tcp auth-ip-acl=127.0.0.1 auth-anonymous=1" --exit-idle-time=-1 2>/dev/null || true

# 启动 Termux-X11 服务
termux-x11 :0 -ac &
sleep 2

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
