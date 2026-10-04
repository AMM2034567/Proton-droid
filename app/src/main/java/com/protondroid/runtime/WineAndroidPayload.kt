package com.protondroid.runtime

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * C 方案（wineandroid.drv）载荷安装器。
 *
 * 把 CI 产出的「wine aarch64-android 安装树」（GitHub Release 资产，见 .github/workflows/wine-android.yml）
 * 解到 filesDir 下，布局与上游 WineActivity(11.0) 的约定完全一致：
 *
 *   <filesDir>/arm64-v8a/bin/{wine,wineserver}            ← get_wine_abi() 判据：<abi>/bin/wineserver 可执行
 *   <filesDir>/arm64-v8a/lib/wine/aarch64-unix/ntdll.so   ← System.load(...) 的目标
 *   <filesDir>/arm64-v8a/lib/wine/aarch64-windows 目录    ← PE 侧 DLL（WINEDLLPATH=<libdir>/wine）
 *   <filesDir>/share/wine 目录                            ← wine 数据文件
 *
 * 注意：WineActivity 只在自己那套 assets/`files.sum`+`sums.sum` 存在时才做 assets 解包；
 * 找不到 `sums.sum` 会直接 return（readMapFromAssetFile 捕获 IOException 返回空 Map），
 * 所以这里自管载荷、不往 APK assets 里塞 400MB 是安全的。
 *
 * ── D 路线（native aarch64 wine + 内嵌 X + winex11.drv）────────────────────────
 * 主载荷之外再叠两份小产物（同一 Release tag，入口见 [ensureX11Installed]）：
 *
 *   wine-unix-x11-11.0-r1.tar.gz  → 解到 <filesDir>/arm64-v8a/lib/wine/
 *        顶层就是 `aarch64-unix/`、`aarch64-windows/`（tar 里**没有** `./` 前缀）⇒ 正好落位，
 *        覆盖后得到带 winex11.so / winex11.drv 的安装树。
 *   x11-runtime-arm64-11.0-r2.tar.gz → <filesDir>/arm64-v8a/lib/（DSO **拍平**）+ <filesDir>/share/X11/locale
 *        顶层是 `./lib/`、`./share/`；lib/ 里的 DSO 必须和 wine 的库同目录才能进 LD_LIBRARY_PATH，
 *        所以解包时用「目标前缀重映射」（[remapX11Entry]）把 `lib/<x>.so` 改写成 `arm64-v8a/lib/<x>.so`，
 *        软链 `libX11.so.6 -> libX11.so` 由 tar 读取器照旧创建（不支持软链时退化为复制）。
 *
 * 校验用 [x11MissingFiles]：winex11.so / winex11.drv / libX11.so / share/X11/locale + 其余 X11 DSO，
 * 缺哪个人话报哪个，绝不静默跳过。marker 各一份（[MARKER_X11_OVERLAY] / [MARKER_X11_RUNTIME]）。
 */
object WineAndroidPayload {

    /** 所有产物共用同一个 Release tag。换版本时同时改这里和各 TARBALL_*。 */
    const val RELEASE_BASE_URL =
        "https://github.com/AMM2034567/Proton-droid/releases/download/wine-android-11.0-r1"

    /** C1 产物（GitHub Release）。 */
    const val TARBALL_NAME = "wine-android-arm64-11.0-r1-install.tar.gz"
    const val URL_PAYLOAD = "$RELEASE_BASE_URL/$TARBALL_NAME"

    /** D 路线覆盖包：`aarch64-unix/{winex11.so,…}` + `aarch64-windows/{winex11.drv,…}` → 覆盖到 lib/wine/。 */
    const val TARBALL_X11_OVERLAY = "wine-unix-x11-11.0-r1.tar.gz"
    const val URL_X11_OVERLAY = "$RELEASE_BASE_URL/$TARBALL_X11_OVERLAY"

    /** D 路线 X11 运行时：`./lib/` 下的 DSO 与 `libX11.so.6` 软链 + `./share/X11/locale`。 */
    const val TARBALL_X11_RUNTIME = "x11-runtime-arm64-11.0-r2.tar.gz"
    const val URL_X11_RUNTIME = "$RELEASE_BASE_URL/$TARBALL_X11_RUNTIME"

    private const val MARKER = ".wine-android-payload.ok"
    private const val MARKER_X11_OVERLAY = ".wine-unix-x11.ok"
    private const val MARKER_X11_RUNTIME = ".x11-runtime.ok"

    /** 需要可执行位的路径前缀（相对 filesDir）。 */
    private val EXECUTABLE_PREFIXES = listOf("arm64-v8a/bin/")

    /**
     * 需要可执行位的具体文件名（相对 filesDir 的路径）。
     * ⚠️ 真机踩过：wine 的装载器**不在** bin/，而在 lib/wine/aarch64-unix/{wine,wine-preloader}。
     * 少了执行位，wine 就没法 exec 装载器去启动 wineboot，表现为
     * `err:environ:run_wineboot failed to start wineboot 1` + prefix 建不起来。
     */
    private val EXECUTABLE_FILES = listOf(
        "arm64-v8a/lib/wine/aarch64-unix/wine",
        "arm64-v8a/lib/wine/aarch64-unix/wine-preloader",
        "arm64-v8a/bin/wineserver",
    )

    /**
     * X11 运行时里的 DSO（路径相对 <filesDir>，即 filesDir/arm64-v8a/lib/<name>）。
     * tar 里这些文件的权限是 `-rwx------`(0700)，落地后统一补成 0755，保证能 dlopen（只读也够，但别缺 r）。
     * `libX11.so.6` 在 tar 里是软链 → `libX11.so`（用 File.exists() 校验会跟随软链，断链即失败）。
     */
    private val X11_LIBS = listOf(
        "libX11.so", "libX11.so.6", "libxcb.so", "libXau.so", "libXext.so",
        "libXcursor.so", "libXfixes.so", "libXi.so", "libXrender.so", "libXrandr.so",
        "libandroid-support.so",
    )

    /**
     * libxcb.so 的 DT_NEEDED 里有 `libXdmcp.so`，但已发布的 `x11-runtime-arm64-11.0-r2.tar.gz` **没有**这个文件
     * （实测：tar 的 lib/ 下只有 10 个 .so + 1 个软链；ELF `DT_NEEDED` 解析确认 libxcb.so → libXdmcp.so）。
     * 缺它 dlopen(libX11.so) 会直接失败 ⇒ 这里**只告警不判失败**（补包属于 wine/CI 侧），
     * 后续 X11 后端可以调 [x11MissingOptionalLibs] 决定报错还是降级。
     */
    private val X11_OPTIONAL_LIBS = listOf("libXdmcp.so")

    private val X11_EXECUTABLE_FILES = X11_LIBS.map { "arm64-v8a/lib/$it" }

    fun abiDir(context: Context): File = File(context.filesDir, "arm64-v8a")

    fun ntdllSo(context: Context): File =
        File(abiDir(context), "lib/wine/aarch64-unix/ntdll.so")

    fun wineserver(context: Context): File = File(abiDir(context), "bin/wineserver")

    /** WINEDLLPATH 的父目录：覆盖包就解到这里（内含 aarch64-unix/ 与 aarch64-windows/）。 */
    fun wineLibDir(context: Context): File = File(abiDir(context), "lib/wine")

    /** X11 DSO 的落地目录，wine 启动时进 LD_LIBRARY_PATH。 */
    fun x11LibDir(context: Context): File = File(abiDir(context), "lib")

    fun winex11So(context: Context): File =
        File(wineLibDir(context), "aarch64-unix/winex11.so")

    fun winex11Drv(context: Context): File =
        File(wineLibDir(context), "aarch64-windows/winex11.drv")

    fun libX11So(context: Context): File = File(x11LibDir(context), "libX11.so")

    /** Xlib 的 locale 目录（App 侧要设 XLOCALEDIR 指向它）。 */
    fun x11LocaleDir(context: Context): File = File(context.filesDir, "share/X11/locale")

    fun isInstalled(context: Context): Boolean {
        val marker = File(context.filesDir, MARKER)
        return marker.isFile && ntdllSo(context).isFile && wineserver(context).isFile
    }

    /** 覆盖包是否已就绪：marker + winex11.so + winex11.drv 都在才算（缺一个就要重解）。 */
    fun isX11OverlayInstalled(context: Context): Boolean =
        File(context.filesDir, MARKER_X11_OVERLAY).isFile &&
            winex11So(context).isFile &&
            winex11Drv(context).isFile

    /** X11 运行时是否已就绪：marker + libX11.so + share/X11/locale 目录。 */
    fun isX11RuntimeInstalled(context: Context): Boolean =
        File(context.filesDir, MARKER_X11_RUNTIME).isFile &&
            libX11So(context).isFile &&
            x11LocaleDir(context).isDirectory

    /** D 路线载荷是否齐备（覆盖包 + 运行时 + 依赖闭包里的必查项全在）。 */
    fun isX11Installed(context: Context): Boolean =
        isX11OverlayInstalled(context) && isX11RuntimeInstalled(context) && x11MissingFiles(context).isEmpty()

    /**
     * D 路线校验判据（缺失项，人话描述）。**不许静默跳过**：调用方拿空列表才算齐。
     * 用 `exists()` 而不是 `isFile()` 是为了让软链 `libX11.so.6` 也能被校验（跟随软链，断链即失败）。
     */
    fun x11MissingFiles(context: Context): List<String> {
        val missing = mutableListOf<String>()
        val unixSide = winex11So(context)
        if (!unixSide.isFile) {
            missing += "${unixSide.absolutePath}（覆盖包 wine-unix-x11 的 aarch64-unix/winex11.so）"
        }
        val peSide = winex11Drv(context)
        if (!peSide.isFile) {
            missing += "${peSide.absolutePath}（覆盖包 wine-unix-x11 的 aarch64-windows/winex11.drv）"
        }
        val libX11 = libX11So(context)
        if (!libX11.exists()) {
            missing += "${libX11.absolutePath}（X11 运行时 DSO，winex11.so 的 dlopen 依赖）"
        }
        val localeDir = x11LocaleDir(context)
        if (!localeDir.isDirectory) {
            missing += "${localeDir.absolutePath}（X11 运行时 locale 目录，Xlib 的 XLOCALEDIR）"
        }
        X11_LIBS.filter { it != "libX11.so" }.forEach { name ->
            val f = File(x11LibDir(context), name)
            if (!f.exists()) missing += "${f.absolutePath}（X11 运行时 DSO）"
        }
        return missing
    }

    /** [X11_OPTIONAL_LIBS] 里缺哪些（缺了 dlopen 大概率失败，但已发布包确实没带 ⇒ 只告警）。 */
    fun x11MissingOptionalLibs(context: Context): List<String> =
        X11_OPTIONAL_LIBS.filterNot { File(x11LibDir(context), it).isFile }

    /** 校验 D 路线载荷，缺项直接抛（中文，逐条列出缺哪个文件）。 */
    fun verifyX11Installed(context: Context, onProgress: (String) -> Unit = {}) {
        val missing = x11MissingFiles(context)
        check(missing.isEmpty()) {
            "D 路线 X11 载荷不完整，缺少 ${missing.size} 项：" + missing.joinToString("；")
        }
        val optional = x11MissingOptionalLibs(context)
        if (optional.isNotEmpty()) {
            onProgress(
                "⚠️ X11 DSO 依赖闭包不完整：缺少 ${optional.joinToString("、")}" +
                    "（libxcb.so 的 DT_NEEDED 声明了 libXdmcp.so，而已发布的 " +
                    "$TARBALL_X11_RUNTIME 未包含它 ⇒ dlopen(libX11.so) 可能失败；需在 wine/CI 侧补进该包）"
            )
        }
        onProgress("D 路线 X11 载荷校验通过 ✅（${x11LibDir(context)}）")
    }

    /**
     * 给 Java 侧（org.winehq.wine.WineActivity）调用的无回调入口：进度写 logcat。
     * WineActivity.loadWine 已经跑在后台线程里，所以这里可以同步下载 + 解包。
     */
    @JvmStatic
    fun ensureInstalled(context: Context) {
        install(context) { msg -> android.util.Log.i("WineAndroidPayload", msg) }
    }

    /**
     * D 路线（native aarch64 wine + 内嵌 X + winex11.drv）载荷入口，供后续 X11 后端调用。
     * 语义 = 主载荷（[install]）→ 覆盖包（[installX11Overlay]）→ X11 运行时（[installX11Runtime]）→ 校验（[verifyX11Installed]）。
     * 幂等：每一步都有自己的 marker，重复调用只做校验。
     */
    @JvmStatic
    fun ensureX11Installed(context: Context) {
        ensureX11Installed(context) { msg -> android.util.Log.i("WineAndroidPayload", msg) }
    }

    fun ensureX11Installed(context: Context, onProgress: (String) -> Unit) {
        install(context, onProgress)
        installX11Overlay(context, onProgress)
        installX11Runtime(context, onProgress)
        verifyX11Installed(context, onProgress)
    }

    /**
     * 安装载荷：本地 tarball（若已由用户/adb 放到 filesDir/payload/ 或公共目录）优先，
     * 否则从 [URL_PAYLOAD] 下载。已安装且 marker 存在时直接返回。
     */
    fun install(context: Context, onProgress: (String) -> Unit = {}) {
        if (isInstalled(context)) {
            onProgress("wine-android 载荷已就绪（${abiDir(context)}）")
            return
        }
        val local = fetchTarball(context, URL_PAYLOAD, TARBALL_NAME, onProgress)

        // 解压后是 ~1.17GB（2539 文件 + 若干符号链接），先看设备空间够不够，别解到一半失败留下半棵树
        val needBytes = 1_300L * 1024 * 1024
        val freeBytes = context.filesDir.usableSpace
        onProgress("解包 ${local.name}（${local.length() / 1048576} MB，展开后约 1.17GB）")
        check(freeBytes <= 0 || freeBytes > needBytes) {
            "应用私有目录空间不足：可用 ${freeBytes / 1048576}MB，需要约 ${needBytes / 1048576}MB"
        }
        extractTarGz(local, context.filesDir) { done ->
            if (done % 256 == 0L) onProgress("已解出 $done 个文件…")
        }
        local.delete()

        // 校验只看关键文件，**不能**用 isInstalled()——它还要求 marker，而 marker 是下一步才写的，
        // 真机上就是这么把自己判失败的（"载荷安装后校验失败"其实是装好了）。
        check(ntdllSo(context).isFile && wineserver(context).isFile) {
            "载荷安装后校验失败：缺少 ntdll.so 或 wineserver"
        }
        File(context.filesDir, MARKER).writeText(TARBALL_NAME)
        onProgress("wine-android 载荷安装完成 ✅")
    }

    /**
     * D 路线①：把覆盖包 [TARBALL_X11_OVERLAY] 解到 `filesDir/arm64-v8a/lib/wine/`。
     * tar 顶层就是 `aarch64-unix/`、`aarch64-windows/`，所以目标目录 = [wineLibDir] 正好落位。
     * 幂等：marker [MARKER_X11_OVERLAY] + winex11.so + winex11.drv 都在就跳过。
     *
     * ⚠️ 覆盖包里还有 `aarch64-unix/{wine,wine-preloader,ntdll.so,…}` 与 `aarch64-windows/wineandroid.drv`，
     * 解包会**覆盖**主载荷的同名文件（见报告「风险」一节）。权限用 `permissionRoot = filesDir` 判定，
     * 否则 `wine`/`wine-preloader` 的相对路径匹配不上 [EXECUTABLE_FILES]，执行位会丢。
     */
    fun installX11Overlay(context: Context, onProgress: (String) -> Unit = {}) {
        if (isX11OverlayInstalled(context)) {
            onProgress("X11 覆盖包已就绪（${wineLibDir(context)}）")
            return
        }
        check(ntdllSo(context).isFile) {
            "X11 覆盖包安装前缺少主载荷：${ntdllSo(context).absolutePath} 不存在，请先调用 ensureInstalled()/install()"
        }
        val dest = wineLibDir(context).apply { mkdirs() }
        // 覆盖写 win32u.so/opengl32.so/winevulkan.so 等 ≈25MB：空间不足时必须**在解包前**拦住，
        // 否则解到一半留下半棵覆盖树（比不装还糟）。
        val needBytes = 128L * 1024 * 1024
        val freeBytes = context.filesDir.usableSpace
        check(freeBytes <= 0 || freeBytes > needBytes) {
            "应用私有目录空间不足：可用 ${freeBytes / 1048576}MB，X11 覆盖包需要约 ${needBytes / 1048576}MB"
        }
        val tar = fetchTarball(context, URL_X11_OVERLAY, TARBALL_X11_OVERLAY, onProgress)
        onProgress("解包 ${tar.name}（${tar.length() / 1048576} MB）→ ${dest.absolutePath}（覆盖已有 wine 安装树）")
        extractTarGz(
            tarGz = tar,
            destRoot = dest,
            permissionRoot = context.filesDir,
        ) { done ->
            if (done % 8 == 0L) onProgress("已解出 $done 个文件…")
        }
        // 有意保留 tar（8MB）：marker 万一被清掉可以离线重解，不必重新下载。
        check(winex11So(context).isFile && winex11Drv(context).isFile) {
            "X11 覆盖包安装后校验失败：缺少 ${winex11So(context).absolutePath} " +
                "或 ${winex11Drv(context).absolutePath}"
        }
        File(context.filesDir, MARKER_X11_OVERLAY).writeText(TARBALL_X11_OVERLAY)
        onProgress("X11 覆盖包安装完成 ✅（winex11.so + winex11.drv 已就位）")
    }

    /**
     * D 路线②：X11 运行时 [TARBALL_X11_RUNTIME] → `filesDir/arm64-v8a/lib/`（DSO 拍平）+ `filesDir/share/X11/locale/`。
     * 靠 [remapX11Entry] 做「目标前缀重映射」复用同一个 tar 读取器；软链 `libX11.so.6 -> libX11.so` 照旧创建。
     * 幂等：marker [MARKER_X11_RUNTIME] + libX11.so + share/X11/locale 都在就跳过。
     */
    fun installX11Runtime(context: Context, onProgress: (String) -> Unit = {}) {
        if (isX11RuntimeInstalled(context)) {
            onProgress("X11 运行时已就绪（${x11LibDir(context)}）")
            return
        }
        val needBytes = 32L * 1024 * 1024
        val freeBytes = context.filesDir.usableSpace
        check(freeBytes <= 0 || freeBytes > needBytes) {
            "应用私有目录空间不足：可用 ${freeBytes / 1048576}MB，X11 运行时需要约 ${needBytes / 1048576}MB"
        }
        val tar = fetchTarball(context, URL_X11_RUNTIME, TARBALL_X11_RUNTIME, onProgress)
        x11LibDir(context).mkdirs()
        onProgress(
            "解包 ${tar.name}（${tar.length() / 1048576} MB）：lib/*.so* → ${x11LibDir(context)}（拍平），" +
                "share/X11/locale → ${x11LocaleDir(context)}"
        )
        extractTarGz(
            tarGz = tar,
            destRoot = context.filesDir,
            nameRemap = ::remapX11Entry,
            permissionRoot = context.filesDir,
        ) { done ->
            if (done % 64 == 0L) onProgress("已解出 $done 个文件…")
        }
        // 有意保留 tar（1MB）：理由同覆盖包。
        val missing = x11MissingFiles(context).filterNot { it.contains("/lib/wine/") }
        check(missing.isEmpty()) { "X11 运行时安装后校验失败，缺少：" + missing.joinToString("；") }
        File(context.filesDir, MARKER_X11_RUNTIME).writeText(TARBALL_X11_RUNTIME)
        onProgress("X11 运行时安装完成 ✅（${X11_LIBS.size} 个 DSO + locale）")
    }

    /**
     * X11 运行时的条目重映射（只改名字，不改 tar 读取逻辑）：
     *   `./lib/libX11.so`        → `arm64-v8a/lib/libX11.so`   （DSO 拍平进 LD_LIBRARY_PATH 目录）
     *   `./lib/libX11.so.6`      → `arm64-v8a/lib/libX11.so.6` （软链目标 `libX11.so` 是同一目录的相对名，不受影响）
     *   `./share/X11/locale/...` → `share/X11/locale/...`      （原样，落到 filesDir 下）
     * 返回的是相对 destRoot 的路径；读取器随后仍会做 `trimStart('.', '/')`。
     */
    private fun remapX11Entry(rawName: String): String {
        val rel = rawName.removePrefix("./")
        return if (rel == "lib" || rel.startsWith("lib/")) "arm64-v8a/$rel" else rel
    }

    /**
     * 取 tarball：`filesDir/payload/<name>` 已有（用户/adb 预放）就直接用；
     * 否则尝试从公共目录 `/sdcard/Download/ProtonDroid/<name>` 复制，最后才走 [download]。
     */
    private fun fetchTarball(
        context: Context,
        url: String,
        tarName: String,
        onProgress: (String) -> Unit,
    ): File {
        val payloadDir = File(context.filesDir, "payload").apply { mkdirs() }
        val local = File(payloadDir, tarName)
        if (local.isFile && local.length() > 0L) {
            onProgress("使用已有载荷 ${local.absolutePath}（${local.length() / 1048576} MB）")
            return local
        }
        // 公共目录可能因缺「所有文件访问」权限而 EACCES（真机实测：ColorOS 上 shell 也改不了 appops）。
        // 拿不到就当没有，直接走下载，绝不因为备选路径失败而崩掉。
        val publicPayload = File("/sdcard/Download/ProtonDroid/$tarName")
        var copiedFromPublic = false
        try {
            if (publicPayload.isFile) {
                onProgress("复制公共目录里的载荷: ${publicPayload.absolutePath}")
                publicPayload.inputStream().use { input ->
                    FileOutputStream(local).use { output -> input.copyTo(output, 1 shl 20) }
                }
                copiedFromPublic = local.length() > 0
            }
        } catch (e: Exception) {
            onProgress("公共目录不可读（${e.javaClass.simpleName}: ${e.message}），改为下载")
        }
        if (!copiedFromPublic) download(url, local, onProgress)
        return local
    }

    private fun download(url: String, dest: File, onProgress: (String) -> Unit) {
        onProgress("下载载荷 $url")
        val tmp = File(dest.parentFile, dest.name + ".part")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        try {
            conn.connect()
            val code = conn.responseCode
            check(code in 200..299) { "下载失败: HTTP $code" }
            val total = conn.contentLengthLong
            var read = 0L
            val buf = ByteArray(1 shl 20)
            BufferedInputStream(conn.inputStream).use { input ->
                BufferedOutputStream(FileOutputStream(tmp)).use { output ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        read += n
                        if (total > 0 && read % (32L shl 20) < n) {
                            onProgress("下载中 ${read * 100 / total}%（${read / 1048576}/${total / 1048576} MB）")
                        }
                    }
                }
            }
            check(tmp.length() > 0) { "下载内容为空" }
            if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
            onProgress("下载完成（${dest.length() / 1048576} MB）")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 极简 tar 读取器（GNU/ustar/PAX 长名 + 普通文件/目录/软链），配 GZIPInputStream。
     * 不引第三方库：Android 上没有 commons-compress，自己解析 512 字节头最省事。
     *
     * @param nameRemap 可选的「目标前缀重映射」：tar 里的条目名 → destRoot 下的相对路径。
     *        X11 运行时靠它把 `./lib/` 下的 DSO 从 `filesDir/lib/` 挪到 `filesDir/arm64-v8a/lib/`（拍平进 LD_LIBRARY_PATH），
     *        而**不重写**读取器本身（软链/长名/权限逻辑全复用）。默认恒等。
     * @param permissionRoot 计算执行位判据（[EXECUTABLE_FILES] 等都是 filesDir 相对路径）的基准目录。
     *        默认 = destRoot；覆盖包解到 `lib/wine/` 时要显式传 filesDir，否则 `wine`/`wine-preloader` 匹配不上、执行位会丢。
     */
    private fun extractTarGz(
        tarGz: File,
        destRoot: File,
        nameRemap: (String) -> String = { it },
        permissionRoot: File = destRoot,
        onProgress: (Long) -> Unit,
    ) {
        val destCanonical = destRoot.canonicalPath + File.separator
        var entries = 0L
        var pendingLongName: String? = null

        fun readFully(input: InputStream, len: Int): ByteArray {
            val out = ByteArray(len)
            var off = 0
            while (off < len) {
                val n = input.read(out, off, len - off)
                if (n < 0) throw IOException("tar 意外结束（期待 $len 字节，实际 $off）")
                off += n
            }
            return out
        }

        GZIPInputStream(BufferedInputStream(FileInputStream(tarGz), 1 shl 20)).use { input ->
            val header = ByteArray(512)
            while (true) {
                val first = input.read()
                if (first < 0) break
                header[0] = first.toByte()
                val rest = readFully(input, 511)
                System.arraycopy(rest, 0, header, 1, 511)
                if (header.all { it.toInt() == 0 }) break   // 归档结束

                var name = String(header, 0, 100, Charsets.UTF_8).substringBefore('\u0000')
                val sizeField = String(header, 124, 12, Charsets.UTF_8).trim('\u0000', ' ')
                val size = if (sizeField.isEmpty()) 0L else sizeField.toLong(8)
                val typeFlag = header[156].toInt().toChar()
                val prefix = String(header, 345, 155, Charsets.UTF_8).substringBefore('\u0000')
                if (prefix.isNotEmpty()) name = "$prefix/$name"

                val longName = pendingLongName
                if (longName != null) { name = longName; pendingLongName = null }

                // 重映射只作用于「会落地」的条目；'L'/'x'/'g' 是元数据条目，名字留给下一条用，不动。
                val relPath = nameRemap(name).trimStart('.', '/')

                when (typeFlag) {
                    'L' -> {   // GNU long name：本条目的数据是下一个条目的名字
                        val data = readFully(input, size.toInt())
                        pendingLongName = String(data, Charsets.UTF_8).substringBefore('\u0000')
                        skipPadding(input, size)
                    }
                    'x', 'g' -> {  // PAX 扩展头：跳过（我们只用基本字段）
                        readFully(input, size.toInt())
                        skipPadding(input, size)
                    }
                    '5' -> {   // 目录
                        val dir = File(destRoot, relPath)
                        if (dir.canonicalPath.startsWith(destCanonical) || dir.canonicalPath + File.separator == destCanonical) {
                            dir.mkdirs()
                        }
                    }
                    '0', '\u0000', '7' -> {   // 普通文件
                        val target = File(destRoot, relPath)
                        if (!target.canonicalPath.startsWith(destCanonical)) {
                            throw IOException("tar 条目越界: $name")
                        }
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { output ->
                            var left = size
                            val buf = ByteArray(1 shl 16)
                            while (left > 0) {
                                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                                if (n < 0) throw IOException("tar 数据意外结束: $name")
                                output.write(buf, 0, n)
                                left -= n
                            }
                        }
                        if (needsExecBit(relTo(permissionRoot, target))) {
                            target.setExecutable(true, false)
                        }
                        // X11 的 DSO 在 tar 里是 0700，而 FileOutputStream 建文件受 umask 影响（app 上常见 0600）
                        // ⇒ 显式补可读位，保证 dlopen 不因权限失败（本文件所有普通文件都这么处理）。
                        target.setReadable(true, false)
                        entries++
                        onProgress(entries)
                        skipPadding(input, size)
                    }
                    '2' -> {   // 符号链接：载荷里是 bin/<工具> -> wine；X11 运行时是 libX11.so.6 -> libX11.so
                        val linkName = String(header, 157, 100, Charsets.UTF_8).substringBefore('\u0000')
                        val target = File(destRoot, relPath)
                        if (target.canonicalPath.startsWith(destCanonical)) {
                            target.parentFile?.mkdirs()
                            val linked = try {
                                java.nio.file.Files.createSymbolicLink(
                                    target.toPath(), java.nio.file.Paths.get(linkName)
                                )
                                true
                            } catch (e: Exception) {
                                false
                            }
                            val execBit = needsExecBit(relTo(permissionRoot, target))
                            if (!linked) {
                                // 文件系统不支持软链时退化为复制被指向的文件（例如 bin/winecfg ← bin/wine、
                                // libX11.so.6 ← libX11.so；两份包的 tar 里被指向者都在软链**之前**，所以能命中）
                                val src = File(target.parentFile, linkName)
                                if (src.isFile) src.copyTo(target, overwrite = true)
                            }
                            if (execBit) target.setExecutable(true, false)
                            // 软链上 chmod 会跟随到目标；复制回退时这条是必需的（否则 libX11.so.6 可能 0600）
                            if (target.exists()) target.setReadable(true, false)
                        }
                        skipPadding(input, size)
                    }
                    else -> {  // 其它类型（硬链接/设备节点等）：跳过数据
                        var left = size
                        val buf = ByteArray(1 shl 16)
                        while (left > 0) {
                            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                            if (n < 0) break
                            left -= n
                        }
                        skipPadding(input, size)
                    }
                }
            }
        }
        onProgress(entries)
    }

    /** `target` 相对 `root` 的路径（`/` 分隔）；不在 root 下时返回 null（不抛，权限判定而已）。 */
    private fun relTo(root: File, target: File): String? = try {
        target.relativeTo(root).path.replace('\\', '/')
    } catch (e: IllegalArgumentException) {
        null
    }

    /** 需要执行位的判据：bin/ 前缀、wine 装载器清单、X11 DSO 清单。 */
    private fun needsExecBit(rel: String?): Boolean =
        rel != null && (
            EXECUTABLE_PREFIXES.any { rel.startsWith(it) } ||
                EXECUTABLE_FILES.contains(rel) ||
                X11_EXECUTABLE_FILES.contains(rel)
            )

    private fun skipPadding(input: InputStream, size: Long) {
        val pad = ((512 - (size % 512)) % 512).toInt()
        var left = pad
        while (left > 0) {
            val n = input.read()
            if (n < 0) return
            left--
        }
    }
}
