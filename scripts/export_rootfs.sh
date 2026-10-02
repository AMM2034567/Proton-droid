#!/data/data/com.termux/files/usr/bin/bash
# ==============================================================================
# Proton-droid: Universal Rootfs Exporter (Internal Container Archiver)
# ==============================================================================

set -e

echo "=================================================="
echo "正在从 Debian 容器内部提取并打包 glibc Rootfs..."
echo "供 Proton-droid 独立 APK 自主运行使用..."
echo "=================================================="

# 直接在容器内以根目录 / 为基准打包，排除虚拟文件系统与外部挂载
proot-distro login --shared-tmp --bind /sdcard:/sdcard debian -- bash -c '
set -e
echo "正在高速压缩 Linux glibc 核心环境 (约需 15 秒)..."
tar --exclude="./opt/proton" \
    --exclude="./sdcard" \
    --exclude="./proc" \
    --exclude="./sys" \
    --exclude="./dev" \
    --exclude="./tmp" \
    -czf /sdcard/Download/ProtonDroid/rootfs.tar.gz -C / .

echo "=================================================="
echo "打包成功！"
ls -lh /sdcard/Download/ProtonDroid/rootfs.tar.gz
echo "=================================================="
'

echo "所有必备资源已齐备！现在可以打开 Proton-droid App 一键装配！"
