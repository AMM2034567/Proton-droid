package com.protondroid.runtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

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

    // 本地缓存 / 公共存储检查路径
    val publicProotSource: File by lazy { File("/sdcard/Download/ProtonDroid/proot") }
    val publicRootfsSource: File by lazy { File("/sdcard/Download/ProtonDroid/rootfs.tar.gz") }
    val publicProtonSource: File by lazy { File("/sdcard/Download/ProtonDroid/proton-droid-arm64.tar.gz") }

    // 官方云端下载端点
    companion object {
        const val URL_PROOT = "https://github.com/proot-me/proot/releases/download/v5.4.1/proot"
        const val URL_PROTON_CORE = "https://github.com/AMM2034567/Proton-droid/releases/download/v1.0.0-arm64-3/proton-droid-arm64-20261002.tar.gz"
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
                // 1. 确保 PRoot 执行器就位
                postProgress(onProgress, "[1/3] 正在装配 PRoot 原生执行器...")
                if (!prootExecutable.exists() || !prootExecutable.canExecute()) {
                    if (publicProotSource.exists()) {
                        publicProotSource.copyTo(prootExecutable, overwrite = true)
                    } else {
                        postProgress(onProgress, "从云端下载 PRoot 静态二进制 (1.8MB)...")
                        downloadWithProgress(URL_PROOT, prootExecutable) { pct ->
                            postProgress(onProgress, "下载 PRoot: $pct%")
                        }
                    }
                    ProcessBuilder("chmod", "755", prootExecutable.absolutePath).start().waitFor()
                }

                // 2. 确保 glibc Rootfs 基础运行环境就位
                val ldLinux = File(rootfsDir, "lib/ld-linux-aarch64.so.1")
                if (!ldLinux.exists()) {
                    postProgress(onProgress, "[2/3] 正在装配 glibc Rootfs 基础运行环境...")
                    if (publicRootfsSource.exists()) {
                        postProgress(onProgress, "正在解压本地 rootfs.tar.gz (约需 10 秒)...")
                        extractTarGz(publicRootfsSource, rootfsDir)
                    } else {
                        postProgress(onProgress, "未找到本地 rootfs.tar.gz，请先通过 Termux 快速导出或放置于 /sdcard/Download/ProtonDroid/")
                        postComplete(onComplete, false)
                        return@Thread
                    }
                } else {
                    postProgress(onProgress, "[2/3] glibc Rootfs 基础运行库已就位！")
                }

                // 3. 确保 Proton 11 ARM64 游戏兼容核心就位
                if (!wineExecutable.exists()) {
                    postProgress(onProgress, "[3/3] 正在解压 Proton 11 ARM64 核心到 /opt/proton (约需 20 秒)...")
                    protonDir.mkdirs()
                    if (publicProtonSource.exists()) {
                        extractTarGz(publicProtonSource, protonDir)
                    } else {
                        postProgress(onProgress, "从云端下载 Proton 11 ARM64 核心 (562MB)...")
                        downloadWithProgress(URL_PROTON_CORE, publicProtonSource) { pct ->
                            postProgress(onProgress, "下载 Proton 核心: $pct%")
                        }
                        extractTarGz(publicProtonSource, protonDir)
                    }
                }

                // 权限修复
                ProcessBuilder("chmod", "-R", "755", File(protonDir, "files/bin-arm64").absolutePath).start().waitFor()

                postProgress(onProgress, "🎉 Proton-droid 独立沙箱运行时全量装配成功！")
                postComplete(onComplete, true)
            } catch (e: Exception) {
                Log.e(tag, "Install standalone runtime failed", e)
                postProgress(onProgress, "装配异常: ${e.message}")
                postComplete(onComplete, false)
            }
        }.start()
    }

    private fun extractTarGz(tarFile: File, targetDir: File) {
        targetDir.mkdirs()
        val proc = ProcessBuilder(
            "toybox", "tar", "-xzf",
            tarFile.absolutePath,
            "-C", targetDir.absolutePath
        ).redirectErrorStream(true).start()
        val code = proc.waitFor()
        if (code != 0) {
            throw RuntimeException("tar -xzf failed with code $code")
        }
    }

    private fun downloadWithProgress(urlStr: String, dest: File, onPercent: (Int) -> Unit) {
        var connection = URL(urlStr).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 15000
        connection.readTimeout = 30000

        var responseCode = connection.responseCode
        if (responseCode == HttpURLConnection.HTTP_MOVED_TEMP || responseCode == HttpURLConnection.HTTP_MOVED_PERM) {
            val newUrl = connection.getHeaderField("Location")
            connection = URL(newUrl).openConnection() as HttpURLConnection
        }

        val totalBytes = connection.contentLengthLong
        var downloadedBytes = 0L

        dest.parentFile?.mkdirs()
        connection.inputStream.use { input ->
            FileOutputStream(dest).use { output ->
                val buffer = ByteArray(64 * 1024)
                var bytesRead: Int
                var lastPct = -1
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead
                    if (totalBytes > 0) {
                        val pct = ((downloadedBytes * 100) / totalBytes).toInt()
                        if (pct != lastPct && pct % 5 == 0) {
                            lastPct = pct
                            mainHandler.post { onPercent(pct) }
                        }
                    }
                }
            }
        }
    }

    private fun postProgress(callback: (String) -> Unit, msg: String) {
        mainHandler.post { callback(msg) }
    }

    private fun postComplete(callback: (Boolean) -> Unit, success: Boolean) {
        mainHandler.post { callback(success) }
    }
}
