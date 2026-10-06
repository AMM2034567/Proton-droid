package com.protondroid.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.protondroid.NativeBridge
import com.protondroid.ProtonStatus
import com.protondroid.R
import com.protondroid.display.DisplayBackend
import com.protondroid.display.XServer
import com.protondroid.runtime.WineAndroidPayload
import com.protondroid.service.ProtonForegroundService
import com.termux.x11.LorieView
import java.io.File
import kotlin.concurrent.thread

/**
 * 游戏视窗。
 *
 * 三条后端（见 [DisplayBackend]）：
 *  - B 方案（默认 `x11`）：内嵌 X 服务器（Termux-X11 的 `libXlorie.so`）把 X 画面通过 EGL 合成到
 *    本页的 [LorieView]；proot guest wine 以 `DISPLAY=:0` 连到同一个 X 服务器；
 *  - C 方案（`android`）：**不启 X**，直接把 `org.winehq.wine.WineActivity` 拉到前台，
 *    由 `wineandroid.drv` 直接往 `ANativeWindow` 呈现；
 *  - D 路线（`native-x11`）：内嵌 X 服务器（[XServer] + [LorieView]），
 *    再由 [NativeBridge.forkAndExec] 启动 native aarch64(bionic) wine（`winex11.drv`），
 *    Vulkan 走 `VK_KHR_xlib_surface`，支持 DXVK。
 */
class GameViewActivity : AppCompatActivity() {

    private lateinit var lorieView: LorieView
    private lateinit var tvGameTitle: TextView
    private lateinit var tvGameStatus: TextView
    private lateinit var btnExit: Button

    private var gamePath: String = ""
    private var gameLaunched = false

    @Volatile
    private var nativeWinePid: Int = -1

    private val statusListener: (String) -> Unit = { message ->
        runOnUiThread { appendStatus(message) }
    }

    companion object {
        const val EXTRA_GAME_PATH = "extra_game_path"
        private const val TAG = "GameViewActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        gamePath = intent.getStringExtra(EXTRA_GAME_PATH) ?: ""

        // ── C 方案分支：不启 X 服务器，直接把 wine 的 Activity 拉到前台 ──
        // 后端选择见 DisplayBackend（files/display_backend.txt，默认 x11 ⇒ 老路径不回归）。
        if (DisplayBackend.isAndroid(this)) {
            val wineCmdline = windowsCmdlineFor(gamePath)
            Log.i(TAG, "display_backend=android ⇒ 启动 WineActivity, cmdline=$wineCmdline")
            val intent = Intent().setClassName(this, "org.winehq.wine.WineActivity")
            if (wineCmdline != null) intent.putExtra("cmdline", wineCmdline)
            startActivity(intent)
            finish()
            return
        }

        setContentView(R.layout.activity_game_view)

        lorieView = findViewById(R.id.surface_game_view)
        tvGameTitle = findViewById(R.id.tv_game_title)
        tvGameStatus = findViewById(R.id.tv_game_status)
        btnExit = findViewById(R.id.btn_exit_game)

        val isNativeX11 = DisplayBackend.isNativeX11(this)
        tvGameTitle.text = when {
            gamePath.isNotEmpty() -> gamePath.substringAfterLast('/')
            isNativeX11 -> "winecfg (native-x11)"
            else -> ""
        }

        enableImmersiveMode()

        btnExit.setOnClickListener {
            if (isNativeX11) {
                stopNativeWine()
            }
            ProtonForegroundService.stopService(this)
            finish()
        }

        // 1) 先拉起内嵌 X 服务器，保证 LorieView 能拿到控制通道 fd
        if (XServer.ensureStarted(this)) {
            appendStatus("内嵌 X 服务器 (DISPLAY=:${XServer.DISPLAY}) 已就绪")
        } else {
            appendStatus("错误: 内嵌 X 服务器启动失败 (libXlorie.so)")
        }

        // 2) Surface 就绪后再拉起游戏或 Wine
        lorieView.setSurfaceReadyListener {
            XServer.onViewAttached(lorieView)
            if (isNativeX11) {
                appendStatus("渲染视图已就绪，正在准备 Native Wine (native-x11)...")
                launchNativeWineOnce()
            } else {
                appendStatus("渲染视图已就绪，正在启动 Proton...")
                launchGameOnce()
            }
        }
    }

    /** B 方案（默认 x11）：proot guest 启动链路，行为保持完全不变。 */
    private fun launchGameOnce() {
        if (gameLaunched || gamePath.isEmpty()) return
        gameLaunched = true
        ProtonForegroundService.startService(this, gamePath)
    }

    /**
     * D 路线（`native-x11`）：native aarch64(bionic) wine + 内嵌 X（XServer + LorieView）+ winex11.drv。
     *
     * 启动参数与环境变量：
     *   command = <filesDir>/arm64-v8a/lib/wine/aarch64-unix/wine
     *   args    = ["c:\windows\system32\winecfg.exe"]（冒烟）或游戏 exe
     *   env     = DISPLAY=:0,
     *             WINEPREFIX=<filesDir>/prefix-x11,
     *             WINEDLLPATH=<filesDir>/arm64-v8a/lib/wine,
     *             LD_LIBRARY_PATH=<filesDir>/arm64-v8a/lib:<filesDir>/arm64-v8a/lib/wine/aarch64-unix:<nativeLibDir>,
     *             XLOCALEDIR=<filesDir>/share/X11/locale,
     *             WINEDEBUGLOG=<filesDir>/log-x11,
     *             WINEDEBUG(读 files/winedebug)
     */
    private fun launchNativeWineOnce() {
        if (gameLaunched) return
        gameLaunched = true

        thread(name = "native-wine-launcher") {
            try {
                // 1) 校验并安装 D 路线 X11 载荷（幂等）
                runOnUiThread { appendStatus("正在检查 D 路线载荷 (winex11 + X11 DSO + locale)...") }
                WineAndroidPayload.ensureX11Installed(this) { msg ->
                    runOnUiThread { appendStatus(msg) }
                }

                // 2) 准备可执行文件与目录
                val files = filesDir
                val wineBin = File(files, "arm64-v8a/lib/wine/aarch64-unix/wine")
                check(wineBin.isFile) { "未找到 Wine 核心装载器: ${wineBin.absolutePath}" }
                wineBin.setExecutable(true, false)
                File(files, "arm64-v8a/lib/wine/aarch64-unix/wine-preloader").setExecutable(true, false)
                File(files, "arm64-v8a/bin/wineserver").setExecutable(true, false)

                val prefixDir = File(files, "prefix-x11").apply { mkdirs() }
                val wineLibDir = File(files, "arm64-v8a/lib/wine")
                val x11LibDir = File(files, "arm64-v8a/lib")
                val unixWineLibDir = File(wineLibDir, "aarch64-unix")
                val nativeLibDir = applicationInfo.nativeLibraryDir
                val x11LocaleDir = File(files, "share/X11/locale")
                val logFile = File(files, "log-x11")
                val tmpDir = File(files, "tmp").apply { mkdirs() }

                // 3) 清理上次遗留的 wine 进程
                val swept = NativeBridge.cleanupStaleProcesses(files.absolutePath, 0)
                if (swept > 0) {
                    runOnUiThread { appendStatus("已清理遗留进程 $swept 个") }
                }

                // 4) 组织环境变量
                val envList = mutableListOf(
                    "DISPLAY=:0",
                    "WINEPREFIX=${prefixDir.absolutePath}",
                    "WINEDLLPATH=${wineLibDir.absolutePath}",
                    "LD_LIBRARY_PATH=${x11LibDir.absolutePath}:${unixWineLibDir.absolutePath}:$nativeLibDir",
                    "XLOCALEDIR=${x11LocaleDir.absolutePath}",
                    "WINEDEBUGLOG=${logFile.absolutePath}",
                    "TMPDIR=${tmpDir.absolutePath}"
                )

                val winedebugFile = File(files, "winedebug")
                if (winedebugFile.isFile) {
                    val dbg = runCatching { winedebugFile.readText().trim() }.getOrDefault("")
                    if (dbg.isNotEmpty()) envList.add("WINEDEBUG=$dbg")
                }

                val targetArg = if (gamePath.isNotEmpty()) {
                    windowsCmdlineFor(gamePath) ?: "c:\\windows\\system32\\winecfg.exe"
                } else {
                    "c:\\windows\\system32\\winecfg.exe"
                }
                val args = arrayOf(targetArg)

                Log.i(TAG, "启动 Native Wine: ${wineBin.absolutePath} ${args.joinToString(" ")}")
                runOnUiThread { appendStatus("正在执行: wine ${args.joinToString(" ")}") }

                // 5) forkAndExec 拉起进程
                val pid = NativeBridge.forkAndExec(
                    command = wineBin.absolutePath,
                    args = args,
                    envs = envList.toTypedArray(),
                    logPath = logFile.absolutePath
                )

                if (pid <= 0) {
                    val err = "Native Wine 启动失败: rc=$pid (errno=${-pid})"
                    Log.e(TAG, err)
                    runOnUiThread { appendStatus(err) }
                    return@thread
                }

                nativeWinePid = pid
                Log.i(TAG, "Native Wine 启动成功 (PID: $pid)，日志输出至 files/log-x11")
                runOnUiThread {
                    appendStatus("Native Wine 已启动 (PID: $pid)")
                    appendStatus("日志文件: files/log-x11")
                }

                // 6) 轮询等待进程状态
                monitorNativeWine(pid)
            } catch (t: Throwable) {
                Log.e(TAG, "Native Wine 异常", t)
                runOnUiThread { appendStatus("错误: ${t.message ?: t.toString()}") }
            }
        }
    }

    private fun monitorNativeWine(pid: Int) {
        while (nativeWinePid == pid) {
            val st = NativeBridge.waitPid(pid)
            if (st != 0) {
                nativeWinePid = -1
                val msg = when {
                    st == -1 -> "Native Wine (PID: $pid) 已结束"
                    st in 100..255 -> "Native Wine (PID: $pid) 正常退出 (code: ${st - 100})"
                    st < 0 -> "Native Wine (PID: $pid) 被信号终止 (sig: ${-(st + 100)})"
                    else -> "Native Wine (PID: $pid) 结束 (状态: $st)"
                }
                Log.i(TAG, msg)
                runOnUiThread { appendStatus(msg) }
                break
            }
            try {
                Thread.sleep(1000)
            } catch (e: InterruptedException) {
                break
            }
        }
    }

    private fun stopNativeWine() {
        val pid = nativeWinePid
        if (pid > 0) {
            nativeWinePid = -1
            Log.i(TAG, "终止 Native Wine 进程树 (PID: $pid)")
            NativeBridge.killProcess(pid, 15)
            NativeBridge.killProcess(pid, 9)
        }
    }

    /**
     * 把 Android 侧路径转成 wine 能认的 Windows 路径。
     * wine 默认把 `/` 映射成 `Z:`，所以 `/sdcard/Download/a.exe` → `Z:\sdcard\Download\a.exe`。
     * 传空表示不指定（WineActivity 走它自己的默认程序 winecfg.exe）。
     */
    private fun windowsCmdlineFor(hostPath: String): String? =
        if (hostPath.isEmpty()) null else "Z:" + hostPath.replace('/', '\\')

    override fun onStart() {
        super.onStart()
        ProtonStatus.register(statusListener)
    }

    override fun onStop() {
        ProtonStatus.unregister(statusListener)
        super.onStop()
    }

    private fun appendStatus(message: String) {
        val current = tvGameStatus.text.toString()
        val lines = (current + "\n" + message).trim().lines()
        tvGameStatus.text = if (lines.size > 8) lines.takeLast(8).joinToString("\n") else lines.joinToString("\n")
    }

    private fun enableImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        }
    }

    override fun onDestroy() {
        if (DisplayBackend.isNativeX11(this)) {
            stopNativeWine()
        }
        ProtonForegroundService.stopService(this)
        super.onDestroy()
    }
}
