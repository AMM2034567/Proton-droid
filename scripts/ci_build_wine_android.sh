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

# 必须在任何 cd 之前算好绝对路径：run #6 就是因为在 cd "$SRC" 之后才用相对 $BASH_SOURCE 拼
# ./scripts（那时 CWD 已在 wine 源码树里）⇒ `cd ./scripts` 失败 ⇒ set -e 直接退出，连日志都没留下。
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PATCH_DIR="$SCRIPT_DIR/patches"

[ -d "$TOOLCHAIN" ] || { echo "!! 找不到 NDK toolchain: $TOOLCHAIN"; ls -d "${ANDROID_HOME:-/usr/local/lib/android/sdk}"/ndk/* 2>/dev/null || true; exit 1; }
command -v gradle >/dev/null || echo "!! 警告: PATH 里没有 gradle，APK 目标会失败（wine configure 会直接报错）"
# PE 侧编译器：wine configure 的自动探测顺序是 x86_64-w64-mingw32-gcc → ... → clang。
# 没有 mingw 就用 /usr/bin/clang，而 clang 18.1.3 在 wine msvcp90 的内联汇编 RTTI 上会 ICE
# （run #5 dinput/i386-windows、run #7 msvcp100/x86_64-windows）⇒ 必须装 mingw。
if command -v x86_64-w64-mingw32-gcc >/dev/null 2>&1; then
  echo "PE 编译器: $(x86_64-w64-mingw32-gcc --version | head -1)"
else
  echo "!! 警告: 没找到 x86_64-w64-mingw32-gcc，PE 会退回 clang（已知会 ICE）"
fi

mkdir -p "$WORK" "$OUT"

log "1/5 取 wine 源码 ($WINE_REF)"
if [ ! -d "$SRC/.git" ]; then
  git clone --depth 1 --branch "$WINE_REF" https://github.com/wine-mirror/wine "$SRC"
else
  echo "已存在，复用"
fi
cd "$SRC" && git log --oneline -1

log "1.5/5 给 wine 打本地补丁（仅 wine-11.0 基线；master 已含上游分离进程改造，不再打补丁）"
# 说明：我们的补丁是为了把 wineandroid.drv 从"多年无人编译"的状态救活（详见记忆 §16.6/§16.15）。
# 上游从 wine 11.6 起在复活该驱动，MR !10569（已合并）把它改成**分离进程模型**，
# 并修了 loader 路径/APK 内库查找/64 位 JNI 崩溃等 => 新基线上这些补丁要么已过时、要么会冲突。
# 因此：只有 wine-11.0 基线才打补丁；其它 ref（master/新 tag）默认不打，可用 FORCE_PATCHES=1 强制。
APPLY_PATCHES="${APPLY_PATCHES:-}"
if [ -z "$APPLY_PATCHES" ]; then
  case "$WINE_REF" in
    wine-11.0|wine-11.0*) APPLY_PATCHES=1 ;;
    *) APPLY_PATCHES=0 ;;
  esac
fi
log "   基线 $WINE_REF => APPLY_PATCHES=$APPLY_PATCHES"
if [ "$APPLY_PATCHES" = "1" ] && ls "$PATCH_DIR"/*.patch >/dev/null 2>&1; then
  # ⚠️ 只复位 dlls/wineandroid.drv/，**不要** `git checkout -- dlls/`：
  # 缓存恢复后工作区的 stat 与 git index 失配，整目录 checkout 会把 dlls/ 下所有源文件按新 mtime 重写，
  # make 就会重编 3000+ 个文件（run #10 实测：host 阶段 3159 个编译命令、白花 15 分钟）。
  git -C "$SRC" checkout -- dlls/wineandroid.drv/ dlls/ntdll/unix/loader.c dlls/win32u/driver.c || echo "!! checkout 失败（继续尝试 apply）"
  for p in "$PATCH_DIR"/*.patch; do
    echo "--- apply $(basename "$p")"
    if ! git -C "$SRC" apply --verbose "$p"; then
      echo "!! 补丁应用失败: $p"
      echo "!! git status:"; git -C "$SRC" status --porcelain | head -20
      exit 1
    fi
  done
  git -C "$SRC" diff --stat
else
  echo "(没有补丁，跳过；PATCH_DIR=$PATCH_DIR)"
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
# 指纹必须包含"PE 编译器是谁"：缓存恢复回来的 config.status 可能是没装 mingw 时生成的，
# 只比选项字符串会漏掉工具链变化（那样会沿用 clang 的配置继续编 → 再次 ICE）。指纹变了就整个重建。
HOST_PE_CC="$(command -v x86_64-w64-mingw32-gcc || echo clang)"
HOST_OPTS_STR="${HOST_MINIMAL_OPTS[*]} | pe=$HOST_PE_CC"
if [ ! -f config.status ] || [ "$(cat .configure-opts 2>/dev/null || true)" != "$HOST_OPTS_STR" ]; then
  if [ -f config.status ]; then
    echo "host 配置或 PE 工具链变化 ⇒ 清空 $HOSTBUILD 重新 configure"
    cd "$WORK"; rm -rf "$HOSTBUILD"; mkdir -p "$HOSTBUILD"; cd "$HOSTBUILD"
  fi
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

# ── 2.8/5 bionic X11 sysroot（WITH_X=1 时）───────────────────────────────────────
# aarch64-linux-android 是 bionic，NDK 不提供任何 X 客户端库 ⇒ 从 Termux 的 aarch64 .deb 取。
# 只需编译期头文件 + 可链接的 .so；运行时真正需要的 DSO 会另外打包（见 5/5）。
if [ "${WITH_X:-0}" = "1" ]; then
  X11ROOT="${X11_SYSROOT:-$WORK/x11sysroot}"
  TERMUX_MIRROR="${TERMUX_MIRROR:-https://packages.termux.dev/apt/termux-main}"
  X11_PKGS="xorgproto libx11 libxext libxfixes libxcursor libxi libxrender libxrandr libxcb libxau libXdmcp libandroid-support"
  log "2.8/5 准备 bionic X11 sysroot（Termux .deb → $X11ROOT）"
  mkdir -p "$X11ROOT" "$WORK/x11deb"
  wget -q "$TERMUX_MIRROR/dists/stable/main/binary-aarch64/Packages.gz" -O "$WORK/Packages.gz"
  gunzip -c "$WORK/Packages.gz" > "$WORK/Packages"
  for p in $X11_PKGS; do
    fn=$(awk -v pkg="$p" '/^Package: /{cur=$2} /^Filename: /{if(cur==pkg){print $2; exit}}' "$WORK/Packages")
    [ -n "$fn" ] || { echo "!! Termux 索引里找不到 $p"; exit 1; }
    wget -q "$TERMUX_MIRROR/$fn" -O "$WORK/$(basename "$fn")"
    dpkg-deb -x "$WORK/$(basename "$fn")" "$WORK/x11deb/$p"
    cp -a "$WORK/x11deb/$p/data/data/com.termux/files/usr/." "$X11ROOT/usr/"
  done
  for f in X11/Xlib.h X11/Xutil.h X11/Xresource.h X11/Xmd.h X11/Xproto.h \
           X11/extensions/XInput2.h X11/extensions/Xfixes.h X11/extensions/Xrender.h \
           X11/extensions/randr.h X11/Xcursor/Xcursor.h; do
    [ -f "$X11ROOT/usr/include/$f" ] || { echo "!! 缺头文件 $f"; exit 1; }
  done
  for f in libX11.so libXext.so libXfixes.so libXCursor.so libXi.so libXrender.so libXrandr.so; do
    [ -e "$X11ROOT/usr/lib/$f" ] || [ -e "$X11ROOT/usr/lib/libXcursor.so" ] || { echo "!! 缺 $f"; exit 1; }
  done
  [ -e "$X11ROOT/usr/lib/libX11.so.6" ] || ln -sf libX11.so "$X11ROOT/usr/lib/libX11.so.6"
  ls -l "$X11ROOT/usr/lib"/libX*.so* 2>/dev/null | head -20 || true
fi

log "3/5 交叉配置 aarch64-linux-android"
export CC="$TOOLCHAIN/bin/aarch64-linux-android${ANDROID_API}-clang"
export CXX="$TOOLCHAIN/bin/aarch64-linux-android${ANDROID_API}-clang++"
export AR="$TOOLCHAIN/bin/llvm-ar"
export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
export STRIP="$TOOLCHAIN/bin/llvm-strip"
export NM="$TOOLCHAIN/bin/llvm-nm"
if [ "${WITH_X:-0}" = "1" ]; then
  export CPPFLAGS="--sysroot=$TOOLCHAIN/sysroot -I${X11_SYSROOT:-$WORK/x11sysroot}/usr/include -D__ANDROID_UNAVAILABLE_SYMBOLS_ARE_WEAK__"
  # WINE_CHECK_SONAME 用 $READELF 反查 DT_NEEDED；交叉编译下 ldd 不可用
  export READELF="$TOOLCHAIN/bin/llvm-readelf"
  export LDD=true
  # 用 LIBDIR（替换默认搜索路径）而不是 PATH，避免误吃主机的 X11 .pc
  export PKG_CONFIG_LIBDIR="${X11_SYSROOT:-$WORK/x11sysroot}/usr/lib/pkgconfig:${X11_SYSROOT:-$WORK/x11sysroot}/usr/share/pkgconfig"
else
  export CPPFLAGS="--sysroot=$TOOLCHAIN/sysroot"
fi
export LDFLAGS="--sysroot=$TOOLCHAIN/sysroot"
"$CC" --version | head -1

mkdir -p "$TGTBUILD" && cd "$TGTBUILD"
# --disable-win16/--disable-tests 同上：Android 上不需要 Win16，也省时间。
#
# WITH_X=1 ⇒ 构建 winex11.drv（D/B' 路线：内嵌 X 服务器 + X11 后端，Vulkan 才能走 VK_KHR_xlib_surface）。
# ⚠️ aarch64-linux-android 是 bionic，**没有系统 libX11** ⇒ 必须提供 X11 sysroot：
#    用 Termux 的 bionic X11 包（aarch64 .deb）解出来的目录，路径通过 X11_SYSROOT 传入。
X_OPTS=()
if [ "${WITH_X:-0}" = "1" ]; then
  X_OPTS+=( --with-x --with-xinput2 )
  if [ -n "${X11_SYSROOT:-}" ]; then
    X_OPTS+=( --x-includes="$X11_SYSROOT/usr/include" --x-libraries="$X11_SYSROOT/usr/lib" )
    export PKG_CONFIG_PATH="$X11_SYSROOT/usr/lib/pkgconfig:$X11_SYSROOT/usr/share/pkgconfig:${PKG_CONFIG_PATH:-}"
    export CPPFLAGS="$CPPFLAGS -I$X11_SYSROOT/usr/include"
    export LDFLAGS="$LDFLAGS -L$X11_SYSROOT/usr/lib -Wl,-rpath-link,$X11_SYSROOT/usr/lib"
    echo "WITH_X=1, X11_SYSROOT=$X11_SYSROOT"
  else
    echo "!! WITH_X=1 但没有 X11_SYSROOT：bionic 上必然找不到 libX11，configure 会失败"
  fi
else
  X_OPTS+=( --without-x )
fi
TGT_OPTS=(
  --host=aarch64-linux-android
  --with-wine-tools="$HOSTBUILD"
  --prefix="$PREFIX"
  "${X_OPTS[@]}"
  --without-freetype
  --without-alsa --without-pulse --without-oss --without-coreaudio
  --without-cups --without-dbus --without-gnutls
  --without-sane --without-usb --without-v4l2 --without-pcsclite
  --without-netapi --without-krb5 --without-gstreamer --without-opencl
  --without-wayland --without-xinerama --without-xcomposite --without-xxf86vm
  --disable-win16 --disable-tests
)
TGT_PE_CC="$(command -v aarch64-w64-mingw32-clang || command -v aarch64-w64-mingw32-gcc || echo clang)"
TGT_OPTS_STR="${TGT_OPTS[*]} | pe=$TGT_PE_CC | ndk=$(basename "$TOOLCHAIN") | x=${WITH_X:-0}"
if [ ! -f config.status ] || [ "$(cat .configure-opts 2>/dev/null || true)" != "$TGT_OPTS_STR" ]; then
  if [ -f config.status ]; then
    echo "目标配置或工具链变化 ⇒ 清空 $TGTBUILD 重新 configure"
    cd "$WORK"; rm -rf "$TGTBUILD"; mkdir -p "$TGTBUILD"; cd "$TGTBUILD"
  fi
  stdbuf -oL -eL "$SRC/configure" "${TGT_OPTS[@]}" 2>&1 | stdbuf -oL -eL tee "$OUT/configure-android.log"
  echo "$TGT_OPTS_STR" > .configure-opts
fi
if [ "${WITH_X:-0}" = "1" ]; then
  if grep -q "X .*development files not found" "$OUT/configure-android.log"; then
    echo "!! configure 没认到 X11 ⇒ winex11.drv 不会构建"; exit 1
  fi
  grep -q "winex11.drv" "$TGTBUILD/Makefile" || { echo "!! Makefile 里没有 winex11.drv"; exit 1; }
  echo "✓ configure 已接受 X11（winex11.drv 进入构建）"
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
# 注意路径：Makefile 的 mv 取的是 build/outputs/apk/wine-debug.apk（没有 debug/ 子目录，
# run #11 就是因为我建成了 debug/ 子目录才 mv 失败）
mkdir -p build/outputs/apk/debug
: > build/outputs/apk/wine-debug.apk
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

# ④ WITH_X=1：X11 运行时 DSO + locale 单独打包（App 侧解到 filesDir 后进 LD_LIBRARY_PATH）
#    依据：dlls/winex11.drv 链接期只 NEEDED libX11.so/libXext.so，其余（libXi/libXcursor/libXfixes）
#    是运行时 dlopen(SONAME_LIBxxx)；libxcb/libxau/libXdmcp/libandroid-support 是 libX11.so 自己的
#    DT_NEEDED ⇒ 必须一起带上，否则真机 dlopen 直接失败或静默降级。
#    share/X11/locale 也必须带（否则 XSupportsLocale/XSetLocaleModifiers 失败 → xim_init 降级；
#    App 侧需设 XLOCALEDIR 指向它）。
if [ "${WITH_X:-0}" = "1" ]; then
  X11ROOT="${X11_SYSROOT:-$WORK/x11sysroot}"
  log "5.5/5 打包 X11 运行时（DSO + locale）"
  rm -rf "$OUT/artifacts/x11-runtime"
  mkdir -p "$OUT/artifacts/x11-runtime/lib"
  for f in libX11.so libX11.so.6 libXext.so libXfixes.so libXcursor.so libXi.so \
           libXrender.so libXrandr.so libxcb.so libXau.so libXdmcp.so libandroid-support.so; do
    [ -e "$X11ROOT/usr/lib/$f" ] && cp -a "$X11ROOT/usr/lib/$f" "$OUT/artifacts/x11-runtime/lib/" \
      || echo "(x11-runtime: 缺 $f，跳过)"
  done
  if [ -d "$X11ROOT/usr/share/X11/locale" ]; then
    mkdir -p "$OUT/artifacts/x11-runtime/share/X11"
    cp -a "$X11ROOT/usr/share/X11/locale" "$OUT/artifacts/x11-runtime/share/X11/"
  else
    echo "!! 缺 share/X11/locale ⇒ 真机 XSupportsLocale 会降级（xim_init）"
  fi
  tar czf "$OUT/artifacts/x11-runtime-arm64.tar.gz" -C "$OUT/artifacts/x11-runtime" . 2>/dev/null || true
  ls -l "$OUT/artifacts/x11-runtime/lib" | head -20 || true
  # 清单写进 wine-out/*.log（"纯日志"小产物会收走）⇒ 下次能直接看到 Termux 实际提供了哪些文件。
  # 教训（§16.22）：之前凭印象硬断言 libandroid-support.so 等一定存在，把**已经成功**的构建判成了失败。
  {
    echo "=== x11-runtime 清单（X11ROOT=$X11ROOT）==="
    echo "--- 已打包 ---"; ls -l "$OUT/artifacts/x11-runtime/lib" 2>/dev/null || true
    echo "--- X11ROOT/usr/lib 实际内容 ---"
    ls -l "$X11ROOT"/usr/lib/libX* "$X11ROOT"/usr/lib/libx* "$X11ROOT"/usr/lib/libandroid* 2>/dev/null || true
    echo "--- 关键文件定位 ---"
    find "$X11ROOT" \( -name 'libX11.so*' -o -name 'libandroid-support*' -o -name 'libxcb.so*' \) 2>/dev/null | head -20 || true
  } > "$OUT/x11-runtime.log" 2>&1
  # 只对链接期 NEEDED 的 libX11/libXext 硬断言；其余缺失只告警（真机上会 dlopen 失败或静默降级）
  for f in libX11.so libXext.so; do
    [ -e "$OUT/artifacts/x11-runtime/lib/$f" ] || { echo "!! X11 运行时缺关键 $f（详见 wine-out/x11-runtime.log）"; exit 1; }
  done
  echo "✓ X11 运行时打包完成: $(ls "$OUT/artifacts/x11-runtime/lib" 2>/dev/null | wc -l) 个文件（清单见 wine-out/x11-runtime.log）"
fi

ls -lh "$OUT/artifacts" || true

echo "汇总: make=$MAKE_RC make-install=$INSTALL_RC"
[ "$MAKE_RC" = "0" ] || exit "$MAKE_RC"
exit "$INSTALL_RC"
