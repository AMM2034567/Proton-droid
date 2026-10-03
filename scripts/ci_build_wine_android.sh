#!/usr/bin/env bash
# CI 侧构建 aarch64-android 版 wine（含 wineandroid.drv）—— 不要在本地 PC 上跑！
#
# 上游依据（wine configure.ac）：
#   linux-android*)  enable_wineandroid_drv=yes; 需要 gradle 与 $ANDROID_HOME
#   规则：dlls/wineandroid.drv/wine-debug.apk: ... gradle assembleDebug
#
# 用法（CI 里由 .github/workflows/wine-android.yml 调用）：
#   WINE_REF=wine-11.0 ANDROID_NDK_HOME=... ./scripts/ci_build_wine_android.sh
set -euo pipefail

WINE_REF="${WINE_REF:-wine-11.0}"
ANDROID_API="${ANDROID_API:-28}"
JOBS="${JOBS:-$(nproc)}"
WORK="${WORK:-$PWD/wine-work}"
OUT="${OUT:-$PWD/wine-out}"
NDK="${ANDROID_NDK_HOME:-${ANDROID_HOME:-/usr/local/lib/android/sdk}/ndk/27.2.12479018}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
SRC="$WORK/wine"
HOSTBUILD="$WORK/build-host"
TGTBUILD="$WORK/build-android"
PREFIX="$OUT/wine-android-arm64"

log() { echo -e "\n=== $* ==="; }

[ -d "$TOOLCHAIN" ] || { echo "!! 找不到 NDK toolchain: $TOOLCHAIN"; ls -d "${ANDROID_HOME:-/usr/local/lib/android/sdk}"/ndk/* 2>/dev/null || true; exit 1; }
command -v gradle >/dev/null || echo "!! 警告: PATH 里没有 gradle，APK 目标会失败（wine configure 会直接报错）"

mkdir -p "$WORK" "$OUT"

log "1/5 取 wine 源码 ($WINE_REF)"
if [ ! -d "$SRC/.git" ]; then
  git clone --depth 1 --branch "$WINE_REF" https://github.com/wine-mirror/wine "$SRC"
else
  echo "已存在，复用"
fi
cd "$SRC" && git log --oneline -1

log "2/5 构建 host 工具（x86_64 linux，跨编译 wine 需要 winebuild/widl/winegcc）"
mkdir -p "$HOSTBUILD" && cd "$HOSTBUILD"
# 注意：host 侧只需要 tools/，但 configure 仍会检查 X 等依赖 —— 必须显式 --without-x 等，
# 否则会因缺 32 位 X 开发包直接 configure: error（首轮 CI 就栽在这里）。
HOST_MINIMAL_OPTS=(
  --without-x --without-freetype --without-alsa --without-pulse --without-oss
  --without-coreaudio --without-cups --without-dbus --without-gnutls
  --without-sane --without-usb --without-v4l2 --without-pcsclite --without-netapi
  --without-krb5 --without-gstreamer --without-opencl
)
if [ ! -f config.status ]; then
  "$SRC/configure" "${HOST_MINIMAL_OPTS[@]}" 2>&1 | tee "$OUT/configure-host.log" | tail -20
fi
# 目标侧 build 需要 host 侧的 tools/wine/wine（"No rule to make target .../build-host/tools/wine/wine"），
# 所以这里直接做一次**完整 host 构建**，别只挑几个工具。
make -j"$JOBS" 2>&1 | tee "$OUT/make-host.log" | tail -10
ls -l tools/wine/wine tools/winebuild/winebuild tools/widl/widl 2>/dev/null || true

log "3/5 交叉配置 aarch64-linux-android"
export CC="$TOOLCHAIN/bin/aarch64-linux-android${ANDROID_API}-clang"
export CXX="$TOOLCHAIN/bin/aarch64-linux-android${ANDROID_API}-clang++"
export AR="$TOOLCHAIN/bin/llvm-ar"
export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
export STRIP="$TOOLCHAIN/bin/llvm-strip"
export NM="$TOOLCHAIN/bin/llvm-nm"
export CPPFLAGS="--sysroot=$TOOLCHAIN/sysroot"
export LDFLAGS="--sysroot=$TOOLCHAIN/sysroot"
"$CC" --version | head -1

mkdir -p "$TGTBUILD" && cd "$TGTBUILD"
if [ ! -f config.status ]; then
  "$SRC/configure" \
    --host=aarch64-linux-android \
    --with-wine-tools="$HOSTBUILD" \
    --prefix="$PREFIX" \
    --without-x \
    --without-freetype \
    --without-alsa --without-pulse --without-oss --without-coreaudio \
    --without-cups --without-dbus --without-gnutls \
    --without-sane --without-usb --without-v4l2 --without-pcsclite \
    --without-netapi --without-krb5 --without-gstreamer --without-opencl \
    2>&1 | tee "$OUT/configure-android.log" | tail -40
fi

log "4/5 编译（这一步最久，CI 上约 20~60 分钟）"
set +e
make -j"$JOBS" 2>&1 | tee "$OUT/make-android.log" | tail -60
MAKE_RC=${PIPESTATUS[0]}
set -e
echo "make 退出码: $MAKE_RC"

log "5/5 收集产物"
mkdir -p "$OUT/artifacts"
# wine 的 Android 构建把安装树放在 prefix/<abi>（configure.ac 里 exec_prefix 默认 $prefix/arm64-v8a）
tar czf "$OUT/artifacts/wine-android-arm64-build.tar.gz" -C "$TGTBUILD" \
  --exclude='*.o' --exclude='*.a' --exclude='.git' . 2>/dev/null || true
APK=$(find "$TGTBUILD" "$SRC" -name 'wine-debug.apk' 2>/dev/null | head -1 || true)
[ -n "$APK" ] && cp -v "$APK" "$OUT/artifacts/wine-debug.apk" || echo "(未生成 APK)"
ls -lh "$OUT/artifacts" || true

exit "$MAKE_RC"
