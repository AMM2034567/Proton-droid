package com.protondroid.runtime

import android.content.Context
import java.io.File
import java.io.RandomAccessFile

/**
 * Proton-droid 运行时目录布局（安装器与启动器共用）。
 *
 * 关键点：APK 打的 rootfs.tar.gz 可能带有多层前缀（例如 `debian/rootfs/...`），
 * 因此“真正的 Linux 根”不能假定就是 files/rootfs，必须探测。
 */
class ProtonLayout(private val context: Context) {

    val filesDir: File get() = context.filesDir

    /** PRoot 工具链目录（proot / loader / loader32 / libtalloc / libandroid-shmem） */
    val binDir = File(filesDir, "bin")

    /** PRoot 需要可写的临时目录（PROOT_TMP_DIR），Android 上没有 /tmp */
    val tmpDir = File(filesDir, "tmp")

    /** rootfs.tar.gz 的解压目标 */
    val rootfsDir = File(filesDir, "rootfs")

    /** 游戏进程 stdout/stderr 日志 */
    val logFile = File(filesDir, "proton-stdout.log")

    val prootExecutable = File(binDir, "proot")
    val prootLoader = File(binDir, "loader")
    val prootLoader32 = File(binDir, "loader32")
    val libTalloc = File(binDir, "libtalloc.so.2")
    val libAndroidShmem = File(binDir, "libandroid-shmem.so")

    val toolchainFiles: List<File> =
        listOf(prootExecutable, prootLoader, prootLoader32, libTalloc, libAndroidShmem)

    companion object {
        /** APK 内置工具链的 assets 子目录 */
        const val TOOLCHAIN_ASSET_DIR = "proot"

        /** guest 内的 Proton 安装前缀（相对 guest 根） */
        const val PROTON_OPT_RELATIVE = "opt/proton"

        /** Proton 的 wine 装载器（相对 opt/proton） */
        const val WINE_RELATIVE = "files/bin-arm64/wine"
        const val WINESERVER_RELATIVE = "files/bin-arm64/wineserver"

        /** 预置 wine prefix 模板（相对 opt/proton） */
        const val PREFIX_TEMPLATE_RELATIVE = "files/share/default_pfx_arm64"

        /** 运行期 wine prefix（相对 guest 根） */
        const val PREFIX_RELATIVE = "root/.proton_droid_pfx"

        /** guest 内固定的 Proton 根路径 */
        const val PROTON_GUEST_DIR = "/opt/proton"
        const val WINE_GUEST_PATH = "$PROTON_GUEST_DIR/$WINE_RELATIVE"
        const val WINESERVER_GUEST_PATH = "$PROTON_GUEST_DIR/$WINESERVER_RELATIVE"
        const val PREFIX_GUEST_PATH = "/root/.proton_droid_pfx"

        /** 设备原生 ELF 机器类型：AArch64 */
        const val EM_AARCH64 = 0xB7

        /** 公共覆盖目录：用户可把更新过的 proot 工具链放进这里 */
        const val PUBLIC_TOOLCHAIN_DIR = "/sdcard/Download/ProtonDroid/proot_arm64"
        const val PUBLIC_ROOTFS_TAR = "/sdcard/Download/ProtonDroid/rootfs.tar.gz"
        const val PUBLIC_PROTON_TAR = "/sdcard/Download/ProtonDroid/proton-droid-arm64.tar.gz"
        const val PUBLIC_GAMES_DIR = "/sdcard/Download/ProtonDroid/games"
    }

    fun ensureDirs() {
        binDir.mkdirs()
        tmpDir.mkdirs()
    }

    /** rootfs 目录看起来像不像一个 Linux 根 */
    private fun looksLikeLinuxRoot(dir: File): Boolean {
        if (!dir.isDirectory) return false
        return File(dir, "lib/ld-linux-aarch64.so.1").exists() ||
            File(dir, "usr/bin/env").exists() ||
            File(dir, "bin/sh").exists()
    }

    /**
     * 解析真正的 guest 根文件系统。
     * 依次尝试：files/rootfs → 其子目录（最多 3 层，例如 debian/rootfs）。
     */
    fun resolveGuestRoot(): File? {
        if (!rootfsDir.isDirectory) return null
        if (looksLikeLinuxRoot(rootfsDir)) return rootfsDir

        val queue = ArrayDeque<Pair<File, Int>>()
        rootfsDir.listFiles()?.filter { it.isDirectory }?.forEach { queue.addLast(it to 1) }
        while (queue.isNotEmpty()) {
            val (dir, depth) = queue.removeFirst()
            if (looksLikeLinuxRoot(dir)) return dir
            if (depth >= 3) continue
            dir.listFiles()?.filter { it.isDirectory && it.name !in setOf("proc", "sys", "dev") }
                ?.forEach { queue.addLast(it to depth + 1) }
        }
        return null
    }

    fun protonDir(guestRoot: File) = File(guestRoot, PROTON_OPT_RELATIVE)
    fun wineExecutable(guestRoot: File) = File(guestRoot, "$PROTON_OPT_RELATIVE/$WINE_RELATIVE")
    fun wineserverExecutable(guestRoot: File) = File(guestRoot, "$PROTON_OPT_RELATIVE/$WINESERVER_RELATIVE")
    fun prefixDir(guestRoot: File) = File(guestRoot, PREFIX_RELATIVE)
    fun prefixTemplate(guestRoot: File) =
        File(guestRoot, "$PROTON_OPT_RELATIVE/$PREFIX_TEMPLATE_RELATIVE")

    /** wine 的数据目录（编译期前缀是 /usr/share/wine，运行期需要 bind 过去） */
    fun wineDataDir(guestRoot: File) = File(guestRoot, "$PROTON_OPT_RELATIVE/files/share/wine")

    /** PRoot 工具链是否齐全且架构正确 */
    fun isToolchainReady(): Boolean =
        toolchainFiles.all { it.isFile && it.length() > 0 } && isAarch64Executable(prootExecutable)

    /** 运行时是否可用：guest 根可解析 + wine 存在 */
    fun isRuntimeInstalled(): Boolean {
        val guestRoot = resolveGuestRoot() ?: return false
        return File(guestRoot, "bin/sh").exists() && wineExecutable(guestRoot).exists()
    }

    /** wine prefix 是否已初始化（kernel32.dll 与 dosdevices/c: 都可解析） */
    fun isPrefixValid(guestRoot: File): Boolean {
        val pfx = prefixDir(guestRoot)
        val kernel32 = File(pfx, "drive_c/windows/system32/kernel32.dll")
        val cDrive = File(pfx, "dosdevices/c:")
        return kernel32.exists() && cDrive.exists()
    }

    /**
     * 把宿主机绝对路径转换为 guest 内路径。
     * - 位于 guest 根之下：去掉 guest 根前缀
     * - /sdcard 已 1:1 bind，保持原样
     */
    fun hostPathToGuestPath(guestRoot: File, hostPath: String): String? {
        val root = guestRoot.absolutePath.trimEnd('/')
        val path = File(hostPath).absolutePath
        return when {
            path == root -> "/"
            path.startsWith("$root/") -> path.removePrefix(root)
            path.startsWith("/sdcard/") -> path
            path == "/sdcard" -> path
            else -> null
        }
    }

    /** 读取 ELF e_machine，失败返回 null */
    fun elfMachine(file: File): Int? {
        if (!file.isFile || file.length() < 20) return null
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(20)
                if (raf.read(header) < 20) return null
                if (header[0] != 0x7F.toByte() || header[1] != 'E'.code.toByte() ||
                    header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte()
                ) {
                    return null
                }
                (header[18].toInt() and 0xFF) or ((header[19].toInt() and 0xFF) shl 8)
            }
        } catch (e: Exception) {
            null
        }
    }

    fun isAarch64Executable(file: File): Boolean = elfMachine(file) == EM_AARCH64
}
