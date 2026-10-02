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

    val runtimeDir: File by lazy {
        File(context.filesDir, "runtime").apply { mkdirs() }
    }

    val prefixDir: File by lazy {
        File(context.filesDir, "pfx-arm64").apply { mkdirs() }
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

        val launcherScript = File(runtimeDir, "proton_standalone.py")
        if (!launcherScript.exists()) {
            statusCallback?.invoke("错误: 未找到 proton_standalone.py，请先导入运行核心！")
            return false
        }

        // 构造环境变量
        val envList = mutableListOf(
            "WINEPREFIX=${prefixDir.absolutePath}",
            "HOME=${context.filesDir.absolutePath}",
            "DISPLAY=:0",
            "WINEFSYNC=1",
            "WINEDEBUG=-all",
            "DXVK_ENABLE_NVAPI=0"
        )

        // 启动参数
        val args = mutableListOf("python3", launcherScript.absolutePath, "run", gameExecutablePath)
        args.addAll(extraArgs)

        statusCallback?.invoke("正在通过 ARM64 原生调度拉起游戏...")
        activePid = NativeBridge.forkAndExec(
            command = "/system/bin/sh",
            args = args.toTypedArray(),
            envs = envList.toTypedArray(),
            logPath = logFile.absolutePath
        )

        if (activePid > 0) {
            statusCallback?.invoke("Proton 已启动 (PID: $activePid)")
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
