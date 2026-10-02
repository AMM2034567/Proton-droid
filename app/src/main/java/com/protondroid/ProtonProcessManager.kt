package com.protondroid

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.protondroid.runtime.ProtonLayout
import com.protondroid.runtime.WinePrefix
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PRoot + Proton/Wine 会话管理。
 *
 * 启动链路（修正后）：
 *   app 进程 --fork/exec--> files/bin/proot (aarch64)
 *        --ptrace 进入 guest--> <guestRoot>
 *        --exec--> /opt/proton/files/bin-arm64/wine explorer /desktop=... <game.exe>
 */
class ProtonProcessManager(private val context: Context) {

    private val tag = "ProtonProcessMgr"
    private val layout = ProtonLayout(context)
    private val handler = Handler(Looper.getMainLooper())

    private var activePid = -1

    @Volatile
    private var running = false

    private var statusCallback: ((String) -> Unit)? = null

    val logFile: File get() = layout.logFile

    fun setStatusListener(listener: (String) -> Unit) {
        this.statusCallback = listener
    }

    fun isRunning(): Boolean = running

    private fun report(message: String) {
        Log.i(tag, message)
        statusCallback?.invoke(message)
        ProtonStatus.publish(message)
    }

    // ------------------------------------------------------------------
    // 启动
    // ------------------------------------------------------------------

    fun launchGame(gameExecutablePath: String, extraArgs: List<String> = emptyList()): Boolean {
        if (running) {
            report("错误: 已有 Proton 会话在运行 (PID: $activePid)")
            return false
        }

        if (!layout.isToolchainReady()) {
            report("错误: PRoot 工具链未就绪，请先点击“一键导入 / 重置 Proton 核心”")
            return false
        }

        val guestRoot = layout.resolveGuestRoot()
        if (guestRoot == null) {
            report("错误: 未找到 glibc guest rootfs，请先装配运行时")
            return false
        }

        val wine = layout.wineExecutable(guestRoot)
        if (!wine.isFile) {
            report("错误: 未找到 Wine 核心 ($wine)，请先装配 Proton 运行时")
            return false
        }

        WinePrefix.ensure(layout, guestRoot, ::report)

        val guestGamePath = layout.hostPathToGuestPath(guestRoot, gameExecutablePath)
        if (guestGamePath == null) {
            report("错误: 游戏路径必须位于 /sdcard 或 guest 根目录内: $gameExecutablePath")
            return false
        }
        val guestWorkDir = guestGamePath.substringBeforeLast('/', "/root").ifEmpty { "/root" }

        if (!NativeBridge.checkX11Display(0)) {
            report("警告: 未检测到 X11 显示 :0 —— 请先在 Termux 中执行 `termux-x11 :0`（Wine 需要它来创建窗口）")
        }
        layout.tmpDir.mkdirs()
        rotateLogIfNeeded()
        appendLog("==== ${timestamp()} 启动会话 ====")
        appendLog("game:  $guestGamePath")
        appendLog("root:  ${guestRoot.absolutePath}")

        val args = buildProotArgs(guestRoot, guestWorkDir, guestGamePath, extraArgs)
        val env = buildGuestEnv(guestRoot)

        appendLog("exec:  ${layout.prootExecutable.absolutePath} ${args.joinToString(" ")}")

        val rc = NativeBridge.forkAndExec(
            command = layout.prootExecutable.absolutePath,
            args = args.toTypedArray(),
            envs = env,
            logPath = logFile.absolutePath
        )

        if (rc <= 0) {
            val detail = describeLaunchError(rc)
            appendLog("fork/exec 失败: $detail")
            report("启动失败: $detail")
            return false
        }

        activePid = rc
        running = true
        appendLog("pid:   $rc")
        report("Proton 游戏引擎已启动 (PID: $rc)")
        monitorProcess()
        return true
    }

    private fun buildProotArgs(
        guestRoot: File,
        guestWorkDir: String,
        guestGamePath: String,
        extraArgs: List<String>
    ): List<String> {
        val args = mutableListOf<String>()

        // 注意：PRoot 自身路径由 forkAndExec 作为 argv[0] 注入，这里绝不能重复添加。
        args += listOf("-r", guestRoot.absolutePath)
        args += "-0"
        args += listOf("-w", guestWorkDir)
        args += "--kill-on-exit"

        // 设备与外部存储
        args += listOf("-b", "/sdcard:/sdcard")
        args += listOf("-b", "/dev:/dev")
        args += listOf("-b", "/proc:/proc")
        args += listOf("-b", "/sys:/sys")

        // Proton 载荷编译期数据目录是 /usr/share/wine，运行期必须 bind 过去，
        // 否则 wineserver 会报 “failed to load l_intl.nls” 并直接崩溃。
        args += listOf(
            "-b",
            "${layout.wineDataDir(guestRoot).absolutePath}:/usr/share/wine"
        )

        // 目标程序
        args += ProtonLayout.WINE_GUEST_PATH
        args += listOf("explorer", "/desktop=ProtonDroid,1280x720", guestGamePath)
        args += extraArgs
        return args
    }

    private fun buildGuestEnv(guestRoot: File): Array<String> {
        val proton = ProtonLayout.PROTON_GUEST_DIR
        val ldLibraryPath = listOf(
            layout.binDir.absolutePath,                       // proot 自身依赖 libtalloc / libandroid-shmem
            "$proton/files/lib/aarch64-linux-gnu",
            "$proton/files/lib/wine/aarch64-unix",
            "$proton/files/lib"
        ).joinToString(":")

        return arrayOf(
            // --- PRoot 运行期 ---
            "PROOT_LOADER=${layout.prootLoader.absolutePath}",
            "PROOT_LOADER_32=${layout.prootLoader32.absolutePath}",
            "PROOT_TMP_DIR=${layout.tmpDir.absolutePath}",
            "PROOT_NO_SECCOMP=1",
            "LD_LIBRARY_PATH=$ldLibraryPath",
            // --- guest 基础环境 ---
            "HOME=/root",
            "TMPDIR=/tmp",
            "XDG_RUNTIME_DIR=/tmp",
            "PATH=$proton/files/bin-arm64:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            // --- Wine / Proton ---
            "WINEPREFIX=${ProtonLayout.PREFIX_GUEST_PATH}",
            "WINESERVER=${ProtonLayout.WINESERVER_GUEST_PATH}",
            "WINELOADER=${ProtonLayout.WINE_GUEST_PATH}",
            "WINEDLLPATH=$proton/files/lib/vkd3d:$proton/files/lib/wine",
            "ESPEAK_DATA_PATH=$proton/files/share",
            "FEX_APP_CONFIG_LOCATION=$proton/files/share/fex-emu",
            "FEX_TSOENABLED=1",
            "FEX_MULTIBLOCK=1",
            "FEX_MAXINST=500",
            "WINEDEBUG=-all",
            "WINE_LARGE_ADDRESS_AWARE=1",
            "WINEDLLOVERRIDES=d3d11=n,b;dxgi=n,b;d3d9=n,b;d3d10core=n,b;d3d12=n,b",
            "DXVK_ENABLE_NVAPI=0",
            "DXVK_LOG_LEVEL=none",
            "VKD3D_DEBUG=none",
            // --- 显示与音频（Termux-X11 / PulseAudio）---
            "DISPLAY=:0",
            "PULSE_SERVER=127.0.0.1"
        )
    }

    /** 供自检使用的最小环境（只跑 proot --version） */
    fun buildToolchainEnv(): Array<String> = arrayOf(
        "PROOT_LOADER=${layout.prootLoader.absolutePath}",
        "PROOT_LOADER_32=${layout.prootLoader32.absolutePath}",
        "PROOT_TMP_DIR=${layout.tmpDir.absolutePath}",
        "PROOT_NO_SECCOMP=1",
        "LD_LIBRARY_PATH=${layout.binDir.absolutePath}",
        "HOME=/root",
        "PATH=/usr/bin:/bin"
    )

    // ------------------------------------------------------------------
    // 停止 / 监控
    // ------------------------------------------------------------------

    fun stopSession() {
        val pid = activePid
        if (pid <= 0) {
            running = false
            return
        }
        report("正在停止 Proton 会话 (PID: $pid)...")
        NativeBridge.killProcess(pid, 15) // SIGTERM
        handler.postDelayed({
            if (running && activePid == pid) {
                NativeBridge.killProcess(pid, 9) // SIGKILL
                activePid = -1
                running = false
                report("Proton 运行会话已强制结束")
            }
        }, 1500)
    }

    private fun monitorProcess() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                val pid = activePid
                if (pid <= 0 || !running) return

                val code = NativeBridge.waitPid(pid)
                if (code == 0) {
                    handler.postDelayed(this, 1000)
                    return
                }

                running = false
                activePid = -1
                val message = when {
                    code < 0 -> "游戏进程已被信号终止 (signal ${-code})"
                    code == 127 -> "启动失败 (退出码 127: execve 被拒；请检查 logcat/ProtonBridge)"
                    else -> "游戏已退出 (退出码: $code)"
                }
                appendLog(message)
                report(message)
            }
        }, 1000)
    }

    // ------------------------------------------------------------------
    // 自检 & 日志
    // ------------------------------------------------------------------

    /**
     * 端到端自检：真正 fork/exec 一次 `proot --version`。
     * 这是验证 Android 私有目录执行权限（W^X / SELinux）是否放行的最快方式。
     */
    fun prootSelfTest(): String {
        if (!layout.prootExecutable.isFile) return "自检失败: 未找到 ${layout.prootExecutable.absolutePath}"
        layout.tmpDir.mkdirs()

        val pid = NativeBridge.forkAndExec(
            command = layout.prootExecutable.absolutePath,
            args = arrayOf("--version"),
            envs = buildToolchainEnv(),
            logPath = File(context.filesDir, "proot-selftest.log").absolutePath
        )
        if (pid <= 0) return "自检失败: ${describeLaunchError(pid)}"

        repeat(50) {
            val code = NativeBridge.waitPid(pid)
            if (code != 0) {
                return "自检通过: proot 可执行且正常退出 (退出码 $code)"
            }
            Thread.sleep(100)
        }
        NativeBridge.killProcess(pid, 9)
        return "自检超时: proot 未在 5 秒内退出"
    }

    fun describeLaunchError(code: Int): String = when (code) {
        -13 -> "EACCES 权限被拒绝 (errno 13)：Android 禁止执行应用私有目录中的二进制。" +
            "请确认 targetSdk=28 (untrusted_app_27 域) 且文件权限为 755。"
        -8 -> "ENOEXEC (errno 8)：不是 arm64 架构的可执行文件。"
        -2 -> "ENOENT (errno 2)：目标文件不存在。"
        -1 -> "fork() 失败。"
        else -> "fork/exec 失败 (返回 $code)"
    }

    fun readLogTail(maxLines: Int = 40): String {
        val file = logFile
        if (!file.isFile || file.length() == 0L) return "(日志为空)"
        return try {
            val lines = file.readLines()
            lines.takeLast(maxLines).joinToString("\n")
        } catch (e: Exception) {
            "(读取日志失败: ${e.message})"
        }
    }

    private fun appendLog(line: String) {
        try {
            logFile.appendText(line + "\n")
        } catch (ignored: Exception) {
        }
    }

    private fun rotateLogIfNeeded() {
        try {
            if (logFile.isFile && logFile.length() > 2L * 1024 * 1024) {
                logFile.writeText("")
            }
        } catch (ignored: Exception) {
        }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}
