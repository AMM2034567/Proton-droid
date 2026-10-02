package com.protondroid.runtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

class ProtonRuntimeInstaller(private val context: Context) {

    private val tag = "ProtonInstaller"
    private val mainHandler = Handler(Looper.getMainLooper())

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

    // 公共存储中的核心资源
    val publicProotSource: File by lazy {
        File("/sdcard/Download/ProtonDroid/proot")
    }

    val publicRootfsSource: File by lazy {
        File("/sdcard/Download/ProtonDroid/rootfs.tar.gz")
    }

    val publicProtonSource: File by lazy {
        File("/sdcard/Download/ProtonDroid/proton-droid-arm64.tar.gz")
    }

    fun isRuntimeInstalled(): Boolean {
        return prootExecutable.exists() && prootExecutable.canExecute() && wineExecutable.exists()
    }

    fun installStandaloneRuntime(
        onProgress: (String) -> Unit,
        onComplete: (Boolean) -> Unit
    ) {
        Thread {
            try {
                // 1. 部署 PRoot 执行器
                postProgress(onProgress, "[1/3] 正在部署原生 PRoot 执行器...")
                if (!publicProotSource.exists()) {
                    postProgress(onProgress, "错误: 未找到 ${publicProotSource.absolutePath}")
                    postComplete(onComplete, false)
                    return@Thread
                }
                binDir.mkdirs()
                publicProotSource.copyTo(prootExecutable, overwrite = true)
                ProcessBuilder("chmod", "755", prootExecutable.absolutePath).start().waitFor()

                // 2. 部署 glibc Rootfs 基础运行环境
                if (!File(rootfsDir, "lib/ld-linux-aarch64.so.1").exists()) {
                    postProgress(onProgress, "[2/3] 正在解压 glibc Rootfs 运行环境 (约需 10 秒)...")
                    if (!publicRootfsSource.exists()) {
                        postProgress(onProgress, "提示: 请先在 Termux 中执行 export_rootfs.sh 导出基础根文件系统！")
                        postComplete(onComplete, false)
                        return@Thread
                    }
                    rootfsDir.mkdirs()
                    val proc = ProcessBuilder(
                        "toybox", "tar", "-xzf",
                        publicRootfsSource.absolutePath,
                        "-C", rootfsDir.absolutePath
                    ).redirectErrorStream(true).start()
                    if (proc.waitFor() != 0) {
                        postProgress(onProgress, "Rootfs 解压失败！")
                        postComplete(onComplete, false)
                        return@Thread
                    }
                } else {
                    postProgress(onProgress, "[2/3] glibc Rootfs 基础环境已就绪！")
                }

                // 3. 部署 Proton 11 ARM64 游戏兼容核心
                postProgress(onProgress, "[3/3] 正在解压 Proton 11 ARM64 核心到 /opt/proton (约需 20 秒)...")
                protonDir.mkdirs()
                if (!wineExecutable.exists()) {
                    if (!publicProtonSource.exists()) {
                        postProgress(onProgress, "错误: 未找到 ${publicProtonSource.absolutePath}")
                        postComplete(onComplete, false)
                        return@Thread
                    }
                    val proc = ProcessBuilder(
                        "toybox", "tar", "-xzf",
                        publicProtonSource.absolutePath,
                        "-C", protonDir.absolutePath
                    ).redirectErrorStream(true).start()
                    if (proc.waitFor() != 0) {
                        postProgress(onProgress, "Proton 核心解压失败！")
                        postComplete(onComplete, false)
                        return@Thread
                    }
                }

                // 权限修复
                ProcessBuilder("chmod", "-R", "755", File(protonDir, "files/bin-arm64").absolutePath).start().waitFor()

                postProgress(onProgress, "🎉 Proton-droid 独立沙箱运行时全量装配就绪！")
                postComplete(onComplete, true)
            } catch (e: Exception) {
                Log.e(tag, "Install standalone runtime failed", e)
                postProgress(onProgress, "安装异常: ${e.message}")
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
