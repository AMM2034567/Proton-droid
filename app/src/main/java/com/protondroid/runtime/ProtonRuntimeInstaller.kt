package com.protondroid.runtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files

/**
 * 一键装配独立运行时：
 *  1. 从 APK assets 释放 aarch64 PRoot 工具链（proot/loader/libtalloc/libandroid-shmem）
 *  2. 解压 / 定位 glibc guest rootfs（兼容 `rootfs/` 与 `rootfs/debian/rootfs/` 两种层次）
 *  3. 把 Proton 11 ARM64 载荷放到 **guest 根内部** 的 /opt/proton
 *  4. 初始化 wine prefix
 */
class ProtonRuntimeInstaller(private val context: Context) {

    private val tag = "ProtonInstaller"
    private val layout = ProtonLayout(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        const val URL_PROTON_CORE =
            "https://github.com/AMM2034567/Proton-droid/releases/download/v1.0.0-arm64-3/proton-droid-arm64-20261002.tar.gz"
    }

    fun isRuntimeInstalled(): Boolean = layout.isRuntimeInstalled()

    /**
     * 仅释放 / 修复 PRoot 工具链（约 320KB，来自 APK assets）。
     * 启动前自愈用：即使 rootfs/Proton 载荷早已就位，工具链缺失或架构不对也能就地修好。
     */
    fun ensureToolchain(onProgress: (String) -> Unit = {}): Boolean {
        return try {
            layout.ensureDirs()
            installProotToolchain(onProgress)
            true
        } catch (t: Throwable) {
            Log.e(tag, "ensureToolchain failed", t)
            onProgress("PRoot 工具链装配失败: ${t.message}")
            false
        }
    }

    fun installStandaloneRuntime(
        onProgress: (String) -> Unit,
        onComplete: (Boolean) -> Unit
    ) {
        Thread {
            try {
                layout.ensureDirs()

                installProotToolchain(onProgress)
                val guestRoot = ensureGuestRoot(onProgress)
                    ?: throw IllegalStateException(
                        "未能定位 glibc rootfs。请把 rootfs.tar.gz 放到 ${ProtonLayout.PUBLIC_ROOTFS_TAR} 后重试。"
                    )
                onProgress("[2/3] guest 根目录: ${guestRoot.absolutePath}")

                ensureProtonPayload(guestRoot, onProgress)

                WinePrefix.ensure(layout, guestRoot, onProgress)

                if (layout.isRuntimeInstalled()) {
                    onProgress("🎉 Proton-droid 独立沙箱运行时全量装配成功！")
                    onProgress("proot: aarch64 ✅ (${layout.prootExecutable.absolutePath})")
                    postComplete(onComplete, true)
                } else {
                    throw IllegalStateException("装配后校验失败：未找到 ${layout.wineExecutable(guestRoot).absolutePath}")
                }
            } catch (t: Throwable) {
                Log.e(tag, "Install standalone runtime failed", t)
                postProgress(onProgress, "装配异常: ${t.message}")
                postComplete(onComplete, false)
            }
        }.start()
    }

    // ------------------------------------------------------------------
    // 1. PRoot 工具链
    // ------------------------------------------------------------------

    private fun installProotToolchain(onProgress: (String) -> Unit) {
        onProgress("[1/3] 正在装配 aarch64 PRoot 工具链...")
        val assetNames = context.assets.list(ProtonLayout.TOOLCHAIN_ASSET_DIR)?.toSet() ?: emptySet()
        val publicDir = File(ProtonLayout.PUBLIC_TOOLCHAIN_DIR)

        for (target in layout.toolchainFiles) {
            val needsInstall = !target.isFile || target.length() == 0L ||
                (target == layout.prootExecutable && !layout.isAarch64Executable(target))
            if (!needsInstall) continue

            val fromPublic = File(publicDir, target.name)
            when {
                fromPublic.isFile && fromPublic.length() > 0 -> {
                    onProgress("  释放 ${target.name} ← 公共目录")
                    fromPublic.copyTo(target, overwrite = true)
                }

                assetNames.contains(target.name) -> {
                    onProgress("  释放 ${target.name} ← APK 内置")
                    context.assets.open("${ProtonLayout.TOOLCHAIN_ASSET_DIR}/${target.name}").use { input ->
                        FileOutputStream(target).use { output -> input.copyTo(output) }
                    }
                }

                else -> throw IllegalStateException("缺少 PRoot 组件: ${target.name}")
            }
            target.setReadable(true, false)
            target.setExecutable(true, false)
        }

        if (!layout.isAarch64Executable(layout.prootExecutable)) {
            throw IllegalStateException(
                "PRoot 架构校验失败：期望 aarch64 ELF，实际 e_machine=" +
                    "${layout.elfMachine(layout.prootExecutable)}（x86_64=62，aarch64=183）"
            )
        }
        onProgress("  PRoot 校验通过 (aarch64 ELF)")
    }

    // ------------------------------------------------------------------
    // 2. guest rootfs
    // ------------------------------------------------------------------

    private fun ensureGuestRoot(onProgress: (String) -> Unit): File? {
        layout.resolveGuestRoot()?.let { return it }

        val tar = File(ProtonLayout.PUBLIC_ROOTFS_TAR)
        if (!tar.isFile) {
            onProgress("[2/3] 未找到 rootfs.tar.gz，无法装配 glibc 环境")
            return null
        }

        onProgress("[2/3] 正在解压 rootfs.tar.gz（约需数十秒）...")
        extractTarGz(tar, layout.rootfsDir)
        return layout.resolveGuestRoot()
    }

    // ------------------------------------------------------------------
    // 3. Proton 载荷
    // ------------------------------------------------------------------

    private fun ensureProtonPayload(guestRoot: File, onProgress: (String) -> Unit) {
        val wine = layout.wineExecutable(guestRoot)
        if (wine.isFile) {
            onProgress("[3/3] Proton 11 ARM64 核心已就位: $wine")
            chmodExecutable(File(guestRoot, "${ProtonLayout.PROTON_OPT_RELATIVE}/files/bin-arm64"))
            return
        }

        val protonDir = layout.protonDir(guestRoot)
        protonDir.mkdirs()
        val publicTar = File(ProtonLayout.PUBLIC_PROTON_TAR)

        if (publicTar.isFile) {
            onProgress("[3/3] 正在解压本地 Proton 核心到 /opt/proton ...")
        } else {
            onProgress("[3/3] 正在从云端下载 Proton 核心 (562MB)...")
            downloadWithProgress(URL_PROTON_CORE, publicTar) { pct ->
                postProgress(onProgress, "  下载进度: $pct%")
            }
            onProgress("[3/3] 正在解压 Proton 核心到 /opt/proton ...")
        }

        extractTarGz(publicTar, protonDir)
        chmodExecutable(File(protonDir, "files/bin-arm64"))
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    private fun extractTarGz(tarFile: File, targetDir: File) {
        targetDir.mkdirs()
        val proc = ProcessBuilder(
            "toybox", "tar", "-xzf",
            tarFile.absolutePath,
            "-C", targetDir.absolutePath
        ).redirectErrorStream(true).start()
        val code = proc.waitFor()
        if (code != 0) {
            throw RuntimeException("tar -xzf ${tarFile.name} 失败，退出码 $code")
        }
    }

    private fun chmodExecutable(dir: File) {
        if (!dir.isDirectory) return
        dir.walkTopDown().forEach { file ->
            if (file.isFile && !Files.isSymbolicLink(file.toPath())) {
                file.setReadable(true, false)
                file.setExecutable(true, false)
            }
        }
    }

    private fun downloadWithProgress(urlStr: String, dest: File, onPercent: (Int) -> Unit) {
        var connection = URL(urlStr).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 15000
        connection.readTimeout = 30000

        val responseCode = connection.responseCode
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
