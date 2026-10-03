package com.protondroid.display

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.Os
import android.util.Log
import com.protondroid.NativeBridge
import com.protondroid.runtime.ProtonLayout
import com.termux.x11.CmdEntryPoint
import com.termux.x11.LorieView
import java.io.File

/**
 * 内嵌 X 服务器（Termux-X11 的 `libXlorie.so`）的启动与状态管理。
 *
 * 进程模型：与 App 同进程 —— `CmdEntryPoint.start(args)` 在 native 侧另起线程执行
 * Xorg 主循环（lorie DDX），无需 `app_process` / Binder / 广播。
 *
 * native `start()` 有两个**硬前提**（缺任一个直接 `return JNI_FALSE`）：
 *  1. `TMPDIR` —— 必须存在且可写；X socket 与 lock 文件都放在 `$TMPDIR` 下
 *     （socket 路径 = `$TMPDIR/.X11-unix/X<n>`）。Android 既无 `/tmp` 也无 Termux 的 tmp，
 *     因此必须显式设置成 App 私有目录，并在启动 guest 时把它 bind 到 `/tmp`，
 *     guest 里的 X 客户端（wine）才能按标准路径 `/tmp/.X11-unix/X0` 连上。
 *  2. `XKB_CONFIG_ROOT` —— 必须指向存在的 xkb 数据目录（`usr/share/xkeyboard-config-2`
 *     或 `usr/share/X11/xkb`）。Android/guest 里通常都没有，因此随 APK 内置
 *     `assets/xkb/xkb.tar.gz`（来自 Termux `xkeyboard-config`），首次启动时解压到
 *     `files/xkeyboard-config-2`。
 */
object XServer {

    private const val TAG = "XServer"
    private const val DISPLAY_READY_TIMEOUT_MS = 10000L

    /** xkb 数据解压后的目录名（与上游查找顺序中的 `usr/share/xkeyboard-config-2` 同名） */
    const val XKB_DIR_NAME = "xkeyboard-config-2"

    /** 内置 xkb 资产路径（裸 tar，见 ensureXkbData 注释） */
    private const val XKB_ASSET = "xkb/xkb.tar"

    /** 当前使用的 display 编号（与 ProtonProcessManager 的 DISPLAY=:0 对应） */
    const val DISPLAY = 0

    @Volatile
    private var entry: CmdEntryPoint? = null

    @Volatile
    private var fdHandle: Int = -1

    @Volatile
    private var socketPath: String? = null

    /** App 端控制通道 fd；LorieView 用它在 surfaceCreated 时连上渲染器 */
    @JvmStatic
    fun getConnectionFd(): Int = fdHandle

    @JvmStatic
    fun isRunning(): Boolean = fdHandle >= 0

    /** X 服务器监听的 unix socket 路径（宿主机视角） */
    @JvmStatic
    fun getSocketPath(): String? = socketPath

    /** 供 guest 侧 bind 的宿主机临时目录（X socket 所在） */
    @JvmStatic
    fun getHostTempDir(context: Context): String = ProtonLayout(context).tmpDir.absolutePath

    /**
     * 显示是否可达：优先探测内嵌 X 服务器的 socket（在 App 私有目录下，
     * 不在 /tmp，所以不能只用 checkX11Display），再回退探测外部 X 服务器。
     */
    @JvmStatic
    fun isDisplayReachable(): Boolean {
        val path = socketPath
        if (path != null && NativeBridge.checkUnixSocket(path)) return true
        return NativeBridge.checkX11Display(DISPLAY)
    }

    /**
     * 启动 X 服务器并等待其 socket 可连接。幂等。
     *
     * 注意：必须在**有 Looper 的线程**（如主线程）调用 —— native 里会调用
     * `AChoreographer_getInstance()`。
     */
    @JvmStatic
    @Synchronized
    fun ensureStarted(context: Context): Boolean {
        if (fdHandle >= 0) return true

        val layout = ProtonLayout(context)
        layout.ensureDirs()
        ensureXkbData(context, layout)

        val tmpDir = layout.tmpDir
        Os.setenv("TMPDIR", tmpDir.absolutePath, true)

        val xkbDir = File(layout.filesDir, XKB_DIR_NAME)
        if (File(xkbDir, "symbols").isDirectory) {
            Os.setenv("XKB_CONFIG_ROOT", xkbDir.absolutePath, true)
        } else {
            Log.w(TAG, "XKB data missing at ${xkbDir.absolutePath}; native start() will refuse")
        }

        // 上一次异常退出可能残留 X lock，导致 "Server is already active for display 0"
        File(tmpDir, ".X$DISPLAY-lock").delete()

        // 支持通过 files/app_env.txt 注入宿主 App 进程环境变量（如 TERMUX_X11_FORCE_FLIP=1）
        val appEnvFile = File(layout.filesDir, "app_env.txt")
        if (appEnvFile.isFile) {
            appEnvFile.readLines().forEach { raw ->
                val line = raw.trim()
                if (line.isNotEmpty() && !line.startsWith("#")) {
                    val idx = line.indexOf('=')
                    if (idx > 0) {
                        val k = line.substring(0, idx).trim()
                        val v = line.substring(idx + 1).trim()
                        try {
                            Os.setenv(k, v, true)
                            Log.i(TAG, "App host env injected: $k=$v")
                        } catch (t: Throwable) {
                            Log.w(TAG, "Failed to setenv $k=$v", t)
                        }
                    }
                }
            }
        }

        try {
            System.loadLibrary("Xlorie")
        } catch (t: Throwable) {
            Log.e(TAG, "loadLibrary(Xlorie) failed", t)
            return false
        }

        return try {
            // X 服务器参数可用文件免重编 A/B（files/xserver_args.txt）：
            //   · 默认（无文件 / 无 `+dri3`）：加 `-disable-dri3`
            //     —— Xlorie 的 DRI3 单向残缺（fds_from_pixmap = FalseNoop、无标准 DRI3Open），
            //        Mesa 的 X11 Vulkan WSI 见到 DRI3 就优先走 DRI3 present → 画面到不了服务器。
            //   · 文件里含 `+dri3`（或 `enable-dri3`）：不加 `-disable-dri3`（对照实验用）
            //   · 其它以 `-` 开头的行原样追加，例如：
            //     `-disable-gpu-present` / `-force-sysvshm` / `-legacy-drawing` / `-check-drawing`
            val baseArgs = mutableListOf(":$DISPLAY", "-ac", "-nolisten", "tcp")
            val argsFile = File(layout.filesDir, "xserver_args.txt")
            val extraArgs = if (argsFile.isFile) {
                argsFile.readLines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .flatMap { it.split("\\s+".toRegex()) }
            } else emptyList()
            val enableDri3 = extraArgs.any { it.equals("+dri3", true) || it.equals("enable-dri3", true) }
            if (!enableDri3) baseArgs.add("-disable-dri3")
            baseArgs.addAll(extraArgs.filter { !it.startsWith("+") && !it.equals("enable-dri3", true) })
            Log.i(TAG, "Starting X server with args: ${baseArgs.joinToString(" ")}")
            val instance = CmdEntryPoint(baseArgs.toTypedArray())
            if (!instance.startServer()) {
                Log.e(TAG, "native start() returned false (TMPDIR/XKB_CONFIG_ROOT 是否就绪？)")
                return false
            }

            val pfd: ParcelFileDescriptor = instance.takeXConnection() ?: run {
                Log.e(TAG, "getXConnection() returned null")
                return false
            }
            fdHandle = pfd.detachFd()
            entry = instance
            socketPath = File(File(tmpDir, ".X11-unix"), "X$DISPLAY").absolutePath
            Log.i(TAG, "X server started on :$DISPLAY (app fd=$fdHandle, socket=$socketPath)")

            if (!awaitDisplayReady()) {
                Log.w(TAG, "X server started but socket $socketPath not connectable yet")
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "failed to start embedded X server", t)
            false
        }
    }

    /** 轮询 socket，等待 X 服务器真正开始监听 */
    private fun awaitDisplayReady(): Boolean {
        val deadline = System.currentTimeMillis() + DISPLAY_READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (isDisplayReachable()) return true
            try {
                Thread.sleep(100)
            } catch (ignored: InterruptedException) {
                return false
            }
        }
        return false
    }

    /**
     * 首次运行解压内置 xkb 数据（318 项，约 3.6MB 的裸 tar）。
     *
     * 注意：**不能用 `.tar.gz` 作为 asset 名** —— AAPT 会把 `.gz` 资源自动解压并去掉扩展名，
     * APK 里会出现 `assets/xkb/xkb.tar`，按原名 open() 会 FileNotFoundException。
     */
    private fun ensureXkbData(context: Context, layout: ProtonLayout) {
        val xkbDir = File(layout.filesDir, XKB_DIR_NAME)
        if (File(xkbDir, "symbols").isDirectory) return

        val tmpTar = File(layout.filesDir, "xkb-asset.tar")
        try {
            context.assets.open(XKB_ASSET).use { input ->
                tmpTar.outputStream().use { output -> input.copyTo(output) }
            }
            val proc = ProcessBuilder(
                "toybox", "tar", "-xf", tmpTar.absolutePath, "-C", layout.filesDir.absolutePath
            ).redirectErrorStream(true).start()
            val code = proc.waitFor()
            val ok = File(xkbDir, "symbols").isDirectory
            Log.i(TAG, "xkb data extracted (rc=$code, ok=$ok, dir=${xkbDir.absolutePath})")
            if (!ok) Log.e(TAG, "xkb extraction produced no symbols dir (rc=$code)")
        } catch (t: Throwable) {
            Log.e(TAG, "failed to extract embedded xkb data", t)
        } finally {
            tmpTar.delete()
        }
    }

    /** Surface 就绪后由 GameViewActivity 调用，补做渲染器连接 */
    @JvmStatic
    fun onViewAttached(view: LorieView) {
        view.onServerReady()
    }
}
