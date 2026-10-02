package com.protondroid.runtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.protondroid.NativeBridge
import java.io.File

class ProtonRuntimeInstaller(private val context: Context) {

    private val tag = "ProtonInstaller"
    private val mainHandler = Handler(Looper.getMainLooper())

    val runtimeDir: File by lazy {
        File(context.filesDir, "runtime").apply { mkdirs() }
    }

    val wineExecutable: File by lazy {
        File(runtimeDir, "files/bin-arm64/wine")
    }

    val defaultTarPath: File by lazy {
        File("/sdcard/Download/ProtonDroid/proton-droid-arm64.tar.gz")
    }

    fun isRuntimeInstalled(): Boolean {
        return wineExecutable.exists() && wineExecutable.canExecute()
    }

    fun installFromTar(
        customTarPath: String? = null,
        onProgress: (String) -> Unit,
        onComplete: (Boolean) -> Unit
    ) {
        val tarFile = if (customTarPath != null) File(customTarPath) else defaultTarPath

        if (!tarFile.exists()) {
            postProgress(onProgress, "错误: 未找到核心安装包 (${tarFile.absolutePath})")
            postComplete(onComplete, false)
            return
        }

        Thread {
            try {
                postProgress(onProgress, "正在清空并准备私有运行时沙箱目录...")
                runtimeDir.deleteRecursively()
                runtimeDir.mkdirs()

                postProgress(onProgress, "正在解压 Proton 11 ARM64 核心运行包 (约需 15~30 秒)...")

                // 利用系统 toybox tar 进行高速解压
                val tarProcess = ProcessBuilder(
                    "toybox", "tar", "-xzf",
                    tarFile.absolutePath,
                    "-C", runtimeDir.absolutePath
                ).redirectErrorStream(true).start()

                val exitCode = tarProcess.waitFor()
                if (exitCode != 0) {
                    postProgress(onProgress, "解压失败 (tar exit code: $exitCode)")
                    postComplete(onComplete, false)
                    return@Thread
                }

                postProgress(onProgress, "正在修复二进制文件执行权限 (chmod +x)...")
                val binDir = File(runtimeDir, "files/bin-arm64")
                if (binDir.exists()) {
                    ProcessBuilder("chmod", "-R", "755", binDir.absolutePath).start().waitFor()
                }

                // 复制并就位独立启动器脚本
                postProgress(onProgress, "部署独立启动器 proton_standalone.py ...")
                val standaloneSource = File("/sdcard/Download/ProtonDroid/proton_standalone.py")
                val standaloneDest = File(runtimeDir, "proton_standalone.py")
                if (standaloneSource.exists()) {
                    standaloneSource.copyTo(standaloneDest, overwrite = true)
                    ProcessBuilder("chmod", "+x", standaloneDest.absolutePath).start().waitFor()
                }

                postProgress(onProgress, "Proton 11 ARM64 私有运行时安装成功！")
                postComplete(onComplete, true)
            } catch (e: Exception) {
                Log.e(tag, "Installation error", e)
                postProgress(onProgress, "解压异常: ${e.message}")
                postComplete(onComplete, false)
            }
        }.start()
    }

    private fun postProgress(callback: (String) -> Unit, msg: String) {
        mainHandler.post { callback(msg) }
    }

    private fun postComplete(callback: (Boolean) -> Unit, success: Boolean) {
        mainHandler.post { callback(success) }
    }
}
