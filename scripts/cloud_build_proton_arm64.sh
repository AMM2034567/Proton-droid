#!/usr/bin/env bash
# ==============================================================================
# Proton-droid: Cloud Build Script for Proton 11 ARM64
# 
# This script is designed to run on a Linux cloud server (x86_64 or aarch64)
# with Docker or Podman installed.
# Recommended Specs: >= 8 Cores CPU, >= 16GB RAM, >= 80GB NVMe Disk
# ==============================================================================

set -euo pipefail

COLOR_GREEN="\033[1;32m"
COLOR_YELLOW="\033[1;33m"
COLOR_RED="\033[1;31m"
COLOR_BLUE="\033[1;34m"
COLOR_RESET="\033[0m"

log_info()  { echo -e "${COLOR_BLUE}[INFO]${COLOR_RESET} $*"; }
log_ok()    { echo -e "${COLOR_GREEN}[OK]${COLOR_RESET} $*"; }
log_warn()  { echo -e "${COLOR_YELLOW}[WARN]${COLOR_RESET} $*"; }
log_error() { echo -e "${COLOR_RED}[ERROR]${COLOR_RESET} $*"; exit 1; }

echo -e "${COLOR_GREEN}"
echo "=========================================================="
echo "    Proton-droid: Proton 11 (ARM64 + FEX) Cloud Builder  "
echo "=========================================================="
echo -e "${COLOR_RESET}"

# 1. 检查运行环境与依赖
log_info "1. 检查基础环境依赖..."

# 检查容器引擎
CONTAINER_ENGINE=""
if command -v podman &>/dev/null; then
    CONTAINER_ENGINE="podman"
elif command -v docker &>/dev/null; then
    CONTAINER_ENGINE="docker"
else
    log_error "未检测到 Docker 或 Podman，请先在服务器上安装容器引擎！(e.g., sudo apt install docker.io)"
fi
log_ok "使用容器引擎: ${CONTAINER_ENGINE}"

# 检查 git
if ! command -v git &>/dev/null; then
    log_error "未找到 git，请先安装 git (sudo apt install git)"
fi

# 检查磁盘空间（至少需要 50GB 空闲）
FREE_GB=$(df -BG . | awk 'NR==2 {print $4}' | sed 's/G//')
log_info "当前目录剩余磁盘空间: ${FREE_GB} GB"
if [ "${FREE_GB}" -lt 50 ]; then
    log_warn "建议至少预留 50GB~80GB 磁盘空间以供交叉编译与缓存，当前空间可能偏紧！"
fi

# 2. 拉取 Valve 官方 SteamRT4 ARM64 交叉编译 SDK 镜像
STEAMRT_ARM64_IMAGE="registry.gitlab.steamos.cloud/proton/steamrt4/sdk/arm64-llvm:4.0.20260331.220802-2"
log_info "2. 预拉取 Valve 官方 SteamRT4 SDK 镜像 (可能需要下载较大数据量)..."
log_info "镜像地址: ${STEAMRT_ARM64_IMAGE}"

${CONTAINER_ENGINE} pull "${STEAMRT_ARM64_IMAGE}" || {
    log_warn "直接拉取失败，尝试不带精确 tag 的版本或检查网络连接..."
}

# 3. 准备源码目录
WORKSPACE_DIR="$(pwd)/proton-build-workspace"
mkdir -p "${WORKSPACE_DIR}"
cd "${WORKSPACE_DIR}"

PROTON_REPO_DIR="${WORKSPACE_DIR}/proton-source"

if [ ! -d "${PROTON_REPO_DIR}/.git" ]; then
    log_info "3. 克隆 Valve Proton 官方仓库 (branch: proton_11.0)..."
    git clone --recurse-submodules -b proton_11.0 https://github.com/ValveSoftware/Proton.git "${PROTON_REPO_DIR}"
else
    log_info "3. 检测到已有 Proton 源码，更新子模块..."
    cd "${PROTON_REPO_DIR}"
    git fetch origin proton_11.0
    git checkout proton_11.0
    git submodule update --init --recursive
    cd "${WORKSPACE_DIR}"
fi

# 4. 创建外部构建目录
BUILD_DIR="${WORKSPACE_DIR}/build"
mkdir -p "${BUILD_DIR}"
cd "${BUILD_DIR}"

log_info "4. 配置构建系统 (Target Arch: arm64)..."
"${PROTON_REPO_DIR}/configure.sh" \
    --container-engine="${CONTAINER_ENGINE}" \
    --target-arch=arm64 \
    --build-name=proton-droid-arm64 \
    --enable-ccache

# 5. 执行构建
log_info "5. 开始编译 Proton 11 ARM64 (make redist)..."
log_info "编译可能耗时 30~90 分钟，取决于服务器 CPU 核心数与磁盘性能..."

make -j"$(nproc)" redist

# 6. 打包输出
DIST_OUTPUT="${BUILD_DIR}/redist"
if [ -d "${DIST_OUTPUT}" ]; then
    PACKAGE_NAME="proton-droid-arm64-$(date +%Y%m%d).tar.gz"
    log_info "6. 编译成功！正在打包产物为: ${PACKAGE_NAME} ..."
    tar -czvf "${WORKSPACE_DIR}/${PACKAGE_NAME}" -C "${DIST_OUTPUT}" .
    log_ok "=========================================================="
    log_ok "产物打包完成！路径:"
    log_ok "${WORKSPACE_DIR}/${PACKAGE_NAME}"
    log_ok "大小: $(du -sh "${WORKSPACE_DIR}/${PACKAGE_NAME}" | cut -f1)"
    log_ok "=========================================================="
    log_info "下载此文件并解压推送到安卓手机上的 /data/local/tmp/proton-droid 即可开始运行测试！"
else
    log_error "编译完成但未在 ${DIST_OUTPUT} 找到产物，请检查上方编译日志！"
fi
