package com.protondroid

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

class ProtonProcessManager(private val context: Context) {

    private val tag = "ProtonProcessMgr"
    private var activePid: Int = -1
    private val handler = Handler(Looper.getMainLooper())
    private var statusCallback: ((String) -> Unit)? = null

    val binDir: File by lazy {
        File(context.filesDir, "bin").apply { mkdirs() }
    }

    val rootfsDir: File by lazy {
        File(context.filesDir, "rootfs").apply { mkdirs() }
    }

    val prootExecutable: File by lazy {
        File(binDir, "proot")
    }

    val protonDir: File by lazy {
        File(rootfsDir, "opt/proton")
    }

    val wineExecutable: File by lazy {
        File(protonDir, "files/bin-arm64/wine")
    }

    val logFile: File by lazy {
        File(context.filesDir, "proton-stdout.log")
    }

    fun setStatusListener(listener: (String) -> Unit) {
        this.statusCallback = listener
    }

    fun isRunning(): Boolean {
        if (activePid <= 0) return false
        val ret = NativeBridge.waitPid(activePid)
        return ret == 0
    }

    fun launchGame(gameExecutablePath: String, extraArgs: List<String> = emptyList()): Boolean {
        if (isRunning()) {
            Log.w(tag, "Proton session is already running (PID: $activePid)!")
            return false
        }

        if (!prootExecutable.exists()) {
            statusCallback?.invoke("错误: 未找到 PRoot 执行器，请先执行环境装配！")
            return false
        }

        if (!wineExecutable.exists()) {
            statusCallback?.invoke("错误: 未找到 Wine 核心，请先导入 Proton 运行时！")
            return false
        }

        val gameFile = File(gameExecutablePath)
        val workingDir = gameFile.parent ?: "/sdcard"

        // 装配 PRoot 原生执行参数 (完全告别 python3 和 /system/bin/sh)
        val args = mutableListOf(
            prootExecutable.absolutePath,
            "-r", rootfsDir.absolutePath,
            "-b", "/sdcard:/sdcard",
            "-b", "/dev:/dev",
            "-b", "/proc:/proc",
            "-b", "/sys:/sys",
            "-0",
            "-w", workingDir,
            "/opt/proton/files/bin-arm64/wine",
            "explorer",
            "/desktop=ProtonDroid,1280x720",
            gameExecutablePath
        )
        args.addAll(extraArgs)

        // 注入 Proton 11 ARM64 专有环境变量
        val envList = arrayOf(
            "WINEPREFIX=/root/.proton_droid_pfx",
            "WINESERVER=/opt/proton/files/bin-arm64/wineserver",
            "WINELOADER=/opt/proton/files/bin-arm64/wine",
            "PATH=/opt/proton/files/bin-arm64:/usr/bin:/bin:/usr/sbin:/sbin",
            "LD_LIBRARY_PATH=/opt/proton/files/lib/wine/aarch64-unix:/opt/proton/files/lib:/usr/lib/aarch64-linux-gnu:/lib/aarch64-linux-gnu",
            "FEX_APP_CONFIG_LOCATION=/opt/proton/files/share/fex-emu",
            "FEX_TSOENABLED=1",
            "FEX_MULTIBLOCK=1",
            "FEX_MAXINST=500",
            "WINEDEBUG=-all",
            "WINE_LARGE_ADDRESS_AWARE=1",
            "WINEDLLOVERRIDES=d3d11=n,b;dxgi=n,b;d3d9=n,b;d3d10core=n,b;d3d12=n,b",
            "DXVK_ENABLE_NVAPI=0",
            "DXVK_LOG_LEVEL=none",
            "VKD3D_DEBUG=none",
            "DISPLAY=:0",
            "PULSE_SERVER=127.0.0.1",
            "PROOT_NO_SECCOMP=1"
        )

        statusCallback?.invoke("正在通过独立 PRoot 原生拉起 Proton 11 (零 Python 依赖)...")
        activePid = NativeBridge.forkAndExec(
            command = prootExecutable.absolutePath,
            args = args.toTypedArray(),
            envs = envList,
            logPath = logFile.absolutePath
        )

        if (activePid > 0) {
            statusCallback?.invoke("Proton 游戏引擎已启动 (PID: $activePid)")
            monitorProcess()
            return true
        } else {
            statusCallback?.invoke("启动失败 (forkAndExec 返回错误)")
            return false
        }
    }

    fun stopSession() {
        if (activePid > 0) {
            NativeBridge.killProcess(activePid, 15) // SIGTERM
            handler.postDelayed({
                if (isRunning()) {
                    NativeBridge.killProcess(activePid, 9) // SIGKILL
                }
                activePid = -1
                statusCallback?.invoke("Proton 运行会话已停止")
            }, 1500)
        }
    }

    private fun monitorProcess() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (activePid > 0) {
                    val code = NativeBridge.waitPid(activePid)
                    if (code != 0) {
                        statusCallback?.invoke("游戏已退出 (退出码: $code)")
                        activePid = -1
                        return
                    }
                    handler.postDelayed(this, 1000)
                }
            }
        }, 1000)
    }
}
