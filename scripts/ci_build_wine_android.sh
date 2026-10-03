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

log "1.5/5 给 wine 打本地补丁（wineandroid.drv 与 wine-11.0 内部接口漂移）"
# wineandroid.drv 在上游多年无人编译，接口已与 wine-11.0 漂移（详见 scripts/patches/ 里的说明与记忆 §16.6）。
# 幂等：先把它复位到 pristine（缓存恢复的源码树可能已经打过补丁），再逐个 apply。
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PATCH_DIR="$SCRIPT_DIR/patches"
if ls "$PATCH_DIR"/*.patch >/dev/null 2>&1; then
  git -C "$SRC" checkout -- dlls/ 2>/dev/null || true
  for p in "$PATCH_DIR"/*.patch; do
    echo "--- apply $(basename "$p")"
    git -C "$SRC" apply --verbose "$p"
  done
  git -C "$SRC" diff --stat
else
  echo "(没有补丁，跳过)"
fi

log "2/5 构建 host 工具（x86_64 linux，跨编译需要 winebuild/widl/winegcc 以及 tools/wine/wine）"
mkdir -p "$HOSTBUILD" && cd "$HOSTBUILD"
# 注意：host 侧只需要 tools/，但 configure 仍会检查 X 等依赖 —— 必须显式 --without-x 等，
# 否则会因缺 32 位 X 开发包直接 configure: error（run #1 就栽在这里）。
# --disable-win16：run #3 的完整 host 构建崩在 dlls/krnl386.exe16（Win16，i386-windows PE）：
#   clang ICE → make: *** [Makefile:224695: dlls/krnl386.exe16/i386-windows/selector.o] Error 1
#   Win16 与 Android 跨编译无关，直接关掉（configure.ac 有该选项）。
# --disable-tests：省掉回归测试，缩短 CI 时间。
# --enable-archs=x86_64：host 侧不做 i386 PE（run #5 的 host make 又崩在 i386-windows：
#   clang 18.1.3 ICE（SIGSEGV，exit 139）编 dlls/dinput/i386-windows/dinput.o，
#   target i686-unknown-windows-msvc）。host 只需要 native 工具 + tools/wine/wine，不需要 32 位 PE，
#   直接砍掉 i386 架构既绕开这个 clang bug，也让 host 构建快很多。
# 缓存恢复回来的 build 目录可能来自旧版脚本：选项变了就必须重新 configure（下面用 .configure-opts 戳判断）。
HOST_MINIMAL_OPTS=(
  --without-x --without-freetype --without-alsa --without-pulse --without-oss
  --without-coreaudio --without-cups --without-dbus --without-gnutls
  --without-sane --without-usb --without-v4l2 --without-pcsclite --without-netapi
  --without-krb5 --without-gstreamer --without-opencl
  --disable-win16 --disable-tests --enable-archs=x86_64
)
HOST_OPTS_STR="${HOST_MINIMAL_OPTS[*]}"
if [ ! -f config.status ] || [ "$(cat .configure-opts 2>/dev/null || true)" != "$HOST_OPTS_STR" ]; then
  # 注意：这里**不能**再接 `| tail -N`。tail 要等管道结束才吐字，会把整段构建的输出全部憋住，
  # 网页/CLI 看到的就只有"卡住不动"（run #4 就是这样：60 多行的日志停在 configure 结束处）。
  # tee 已经落盘到 $OUT/*.log，控制台全量输出反而更好。
  stdbuf -oL -eL "$SRC/configure" "${HOST_MINIMAL_OPTS[@]}" 2>&1 | stdbuf -oL -eL tee "$OUT/configure-host.log"
  echo "$HOST_OPTS_STR" > .configure-opts
fi
# 目标侧 build 需要 host 侧的 tools/wine/wine（run #2: "No rule to make target .../build-host/tools/wine/wine"），
# 所以这里必须做**完整 host 构建**，不能只挑几个工具。
# 但 make 失败不能直接把脚本带走（run #3 就是这样丢掉 3/5、4/5 的全部日志）：先记录退出码。
set +e
stdbuf -oL -eL make -j"$JOBS" 2>&1 | stdbuf -oL -eL tee "$OUT/make-host.log"
MAKE_HOST_RC=${PIPESTATUS[0]}
set -e
echo "host make 退出码: $MAKE_HOST_RC"
ls -l tools/wine/wine tools/winebuild/winebuild tools/widl/widl 2>/dev/null || true
for t in tools/wine/wine tools/winebuild/winebuild tools/widl/widl; do
  if [ ! -e "$t" ]; then
    echo "!! host 工具缺失: $t（完整日志见 $OUT/configure-host.log / $OUT/make-host.log）"
    exit 1
  fi
done

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
# --disable-win16/--disable-tests 同上：Android 上不需要 Win16，也省时间。
TGT_OPTS=(
  --host=aarch64-linux-android
  --with-wine-tools="$HOSTBUILD"
  --prefix="$PREFIX"
  --without-x
  --without-freetype
  --without-alsa --without-pulse --without-oss --without-coreaudio
  --without-cups --without-dbus --without-gnutls
  --without-sane --without-usb --without-v4l2 --without-pcsclite
  --without-netapi --without-krb5 --without-gstreamer --without-opencl
  --disable-win16 --disable-tests
)
TGT_OPTS_STR="${TGT_OPTS[*]}"
if [ ! -f config.status ] || [ "$(cat .configure-opts 2>/dev/null || true)" != "$TGT_OPTS_STR" ]; then
  stdbuf -oL -eL "$SRC/configure" "${TGT_OPTS[@]}" 2>&1 | stdbuf -oL -eL tee "$OUT/configure-android.log"
  echo "$TGT_OPTS_STR" > .configure-opts
fi

log "4/5 编译（这一步最久，CI 上约 20~60 分钟；日志实时滚动，不再 tail 缓冲）"
# wine 的 Android 目标里有一条 gradle 规则要产出 dlls/wineandroid.drv/wine-debug.apk（configure.ac:3819）。
# 已核实的两个事实（见 cache/refs/wineandroid/）：
#   1) build.gradle.in 写死 AGP 2.2.1 + compileSdkVersion 25 + buildToolsVersion 25.0.3 + jcenter()，
#      还要 rsvg-convert —— 在 JDK17 + Gradle 8.7 的 runner 上必然失败；
#   2) wine-11.0 里没有任何规则往 dlls/wineandroid.drv/{assets,lib} 填 payload
#      （build.gradle.in 的 assets.srcDirs/jniLibs.srcDirs 指向的就是这两个空目录）
#      ⇒ 就算侥幸产出 APK 也不含 wine，跑了没用。
# 我们真正要的是 `make install` 的安装树，所以默认用 stub gradle 让这条规则"成功"跳过；
# 需要真打 APK 时设 WINE_APK_MODE=real（默认 stub）。
if [ "${WINE_APK_MODE:-stub}" = "stub" ]; then
  STUB_BIN="$WORK/bin-stub"
  mkdir -p "$STUB_BIN"
  cat > "$STUB_BIN/gradle" <<'STUB'
#!/bin/sh
# stub gradle：只满足 wine 的 wine-debug.apk 规则，不做真正的 Android 构建
echo "[stub gradle] skip real APK build (wine-11.0 的 APK 规则用 AGP 2.2.1/jcenter，不可用且不含 payload): $*"
mkdir -p build/outputs/apk/debug
: > build/outputs/apk/debug/wine-debug.apk
exit 0
STUB
  chmod +x "$STUB_BIN/gradle"
  export PATH="$STUB_BIN:$PATH"
  echo "已启用 gradle stub（PATH 前置 $STUB_BIN；WINE_APK_MODE=real 可关闭）"
fi
set +e
stdbuf -oL -eL make -j"$JOBS" 2>&1 | stdbuf -oL -eL tee "$OUT/make-android.log"
MAKE_RC=${PIPESTATUS[0]}
set -e
echo "make 退出码: $MAKE_RC"

log "4.5/5 make install（安装树 = C2 真正要用的产物形态）"
# exec_prefix 在 Android 分支默认是 $prefix/arm64-v8a（configure.ac:1040-1045）
set +e
stdbuf -oL -eL make install 2>&1 | stdbuf -oL -eL tee "$OUT/make-install.log"
INSTALL_RC=${PIPESTATUS[0]}
set -e
echo "make install 退出码: $INSTALL_RC"

log "5/5 收集产物"
mkdir -p "$OUT/artifacts"
# ① 安装树（C2 用这个）：$PREFIX/arm64-v8a/{bin,lib} + share/wine/**
if [ -d "$PREFIX" ]; then
  tar czf "$OUT/artifacts/wine-android-arm64-install.tar.gz" -C "$PREFIX" . 2>/dev/null || true
else
  echo "!! 安装树不存在: $PREFIX（make install 失败？见 $OUT/make-install.log）"
fi
# ② build 树（排查用，去掉目标文件）
tar czf "$OUT/artifacts/wine-android-arm64-build.tar.gz" -C "$TGTBUILD" \
  --exclude='*.o' --exclude='*.a' --exclude='.git' . 2>/dev/null || true
# ③ APK：stub 模式下那个 0 字节占位文件不算产物，不收集
if [ "${WINE_APK_MODE:-stub}" = "real" ]; then
  APK=$(find "$TGTBUILD" "$SRC" -name 'wine-debug.apk' -size +1k 2>/dev/null | head -1 || true)
  [ -n "$APK" ] && cp -v "$APK" "$OUT/artifacts/wine-debug.apk" || echo "(未生成 APK)"
else
  echo "(跳过 APK：stub 模式；wine-11.0 的 APK 不含 payload，C2 用安装树)"
fi
ls -lh "$OUT/artifacts" || true

echo "汇总: make=$MAKE_RC make-install=$INSTALL_RC"
[ "$MAKE_RC" = "0" ] || exit "$MAKE_RC"
exit "$INSTALL_RC"
