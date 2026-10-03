package com.protondroid.runtime

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * C 方案（wineandroid.drv）载荷安装器。
 *
 * 把 CI 产出的「wine aarch64-android 安装树」（GitHub Release 资产，见 .github/workflows/wine-android.yml）
 * 解到 filesDir 下，布局与上游 WineActivity(11.0) 的约定完全一致：
 *
 *   <filesDir>/arm64-v8a/bin/{wine,wineserver}            ← get_wine_abi() 判据：<abi>/bin/wineserver 可执行
 *   <filesDir>/arm64-v8a/lib/wine/aarch64-unix/ntdll.so   ← System.load(...) 的目标
 *   <filesDir>/arm64-v8a/lib/wine/aarch64-windows 目录    ← PE 侧 DLL（WINEDLLPATH=<libdir>/wine）
 *   <filesDir>/share/wine 目录                            ← wine 数据文件
 *
 * 注意：WineActivity 只在自己那套 assets/`files.sum`+`sums.sum` 存在时才做 assets 解包；
 * 找不到 `sums.sum` 会直接 return（readMapFromAssetFile 捕获 IOException 返回空 Map），
 * 所以这里自管载荷、不往 APK assets 里塞 400MB 是安全的。
 */
object WineAndroidPayload {

    /** C1 产物（GitHub Release）。换版本时同时改这里和 TARBALL_NAME。 */
    const val URL_PAYLOAD =
        "https://github.com/AMM2034567/Proton-droid/releases/download/wine-android-11.0-r1/wine-android-arm64-11.0-r1-install.tar.gz"

    const val TARBALL_NAME = "wine-android-arm64-11.0-r1-install.tar.gz"

    private const val MARKER = ".wine-android-payload.ok"

    /** 需要可执行位的路径前缀（相对 filesDir）。 */
    private val EXECUTABLE_PREFIXES = listOf("arm64-v8a/bin/")

    fun abiDir(context: Context): File = File(context.filesDir, "arm64-v8a")

    fun ntdllSo(context: Context): File =
        File(abiDir(context), "lib/wine/aarch64-unix/ntdll.so")

    fun wineserver(context: Context): File = File(abiDir(context), "bin/wineserver")

    fun isInstalled(context: Context): Boolean {
        val marker = File(context.filesDir, MARKER)
        return marker.isFile && ntdllSo(context).isFile && wineserver(context).isFile
    }

    /**
     * 给 Java 侧（org.winehq.wine.WineActivity）调用的无回调入口：进度写 logcat。
     * WineActivity.loadWine 已经跑在后台线程里，所以这里可以同步下载 + 解包。
     */
    @JvmStatic
    fun ensureInstalled(context: Context) {
        install(context) { msg -> android.util.Log.i("WineAndroidPayload", msg) }
    }

    /**
     * 安装载荷：本地 tarball（若已由用户/adb 放到 filesDir/payload/ 或公共目录）优先，
     * 否则从 [URL_PAYLOAD] 下载。已安装且 marker 存在时直接返回。
     */
    fun install(context: Context, onProgress: (String) -> Unit = {}) {
        if (isInstalled(context)) {
            onProgress("wine-android 载荷已就绪（${abiDir(context)}）")
            return
        }
        val payloadDir = File(context.filesDir, "payload").apply { mkdirs() }
        val local = File(payloadDir, TARBALL_NAME)
        val publicPayload = File("/sdcard/Download/ProtonDroid/$TARBALL_NAME")

        if (!local.isFile || local.length() == 0L) {
            when {
                publicPayload.isFile -> {
                    onProgress("复制公共目录里的载荷: ${publicPayload.absolutePath}")
                    publicPayload.inputStream().use { input ->
                        FileOutputStream(local).use { output -> input.copyTo(output, 1 shl 20) }
                    }
                }
                else -> download(URL_PAYLOAD, local, onProgress)
            }
        }

        // 解压后是 ~1.17GB（2539 文件 + 若干符号链接），先看设备空间够不够，别解到一半失败留下半棵树
        val needBytes = 1_300L * 1024 * 1024
        val freeBytes = context.filesDir.usableSpace
        onProgress("解包 ${local.name}（${local.length() / 1048576} MB，展开后约 1.17GB）")
        check(freeBytes <= 0 || freeBytes > needBytes) {
            "应用私有目录空间不足：可用 ${freeBytes / 1048576}MB，需要约 ${needBytes / 1048576}MB"
        }
        extractTarGz(local, context.filesDir) { done ->
            if (done % 256 == 0L) onProgress("已解出 $done 个文件…")
        }
        local.delete()

        check(isInstalled(context)) { "载荷安装后校验失败：缺少 ntdll.so 或 wineserver" }
        File(context.filesDir, MARKER).writeText(TARBALL_NAME)
        onProgress("wine-android 载荷安装完成 ✅")
    }

    private fun download(url: String, dest: File, onProgress: (String) -> Unit) {
        onProgress("下载载荷 $url")
        val tmp = File(dest.parentFile, dest.name + ".part")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        try {
            conn.connect()
            val code = conn.responseCode
            check(code in 200..299) { "下载失败: HTTP $code" }
            val total = conn.contentLengthLong
            var read = 0L
            val buf = ByteArray(1 shl 20)
            BufferedInputStream(conn.inputStream).use { input ->
                BufferedOutputStream(FileOutputStream(tmp)).use { output ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        read += n
                        if (total > 0 && read % (32L shl 20) < n) {
                            onProgress("下载中 ${read * 100 / total}%（${read / 1048576}/${total / 1048576} MB）")
                        }
                    }
                }
            }
            check(tmp.length() > 0) { "下载内容为空" }
            if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
            onProgress("下载完成（${dest.length() / 1048576} MB）")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 极简 tar 读取器（GNU/ustar/PAX 长名 + 普通文件/目录），配 GZIPInputStream。
     * 不引第三方库：Android 上没有 commons-compress，自己解析 512 字节头最省事。
     */
    private fun extractTarGz(tarGz: File, destRoot: File, onProgress: (Long) -> Unit) {
        val destCanonical = destRoot.canonicalPath + File.separator
        var entries = 0L
        var pendingLongName: String? = null

        fun readFully(input: InputStream, len: Int): ByteArray {
            val out = ByteArray(len)
            var off = 0
            while (off < len) {
                val n = input.read(out, off, len - off)
                if (n < 0) throw IOException("tar 意外结束（期待 $len 字节，实际 $off）")
                off += n
            }
            return out
        }

        GZIPInputStream(BufferedInputStream(FileInputStream(tarGz), 1 shl 20)).use { input ->
            val header = ByteArray(512)
            while (true) {
                val first = input.read()
                if (first < 0) break
                header[0] = first.toByte()
                val rest = readFully(input, 511)
                System.arraycopy(rest, 0, header, 1, 511)
                if (header.all { it.toInt() == 0 }) break   // 归档结束

                var name = String(header, 0, 100, Charsets.UTF_8).substringBefore('\u0000')
                val sizeField = String(header, 124, 12, Charsets.UTF_8).trim('\u0000', ' ')
                val size = if (sizeField.isEmpty()) 0L else sizeField.toLong(8)
                val typeFlag = header[156].toInt().toChar()
                val prefix = String(header, 345, 155, Charsets.UTF_8).substringBefore('\u0000')
                if (prefix.isNotEmpty()) name = "$prefix/$name"

                val longName = pendingLongName
                if (longName != null) { name = longName; pendingLongName = null }

                when (typeFlag) {
                    'L' -> {   // GNU long name：本条目的数据是下一个条目的名字
                        val data = readFully(input, size.toInt())
                        pendingLongName = String(data, Charsets.UTF_8).substringBefore('\u0000')
                        skipPadding(input, size)
                    }
                    'x', 'g' -> {  // PAX 扩展头：跳过（我们只用基本字段）
                        readFully(input, size.toInt())
                        skipPadding(input, size)
                    }
                    '5' -> {   // 目录
                        val dir = File(destRoot, name.trimStart('.', '/'))
                        if (dir.canonicalPath.startsWith(destCanonical) || dir.canonicalPath + File.separator == destCanonical) {
                            dir.mkdirs()
                        }
                    }
                    '0', '\u0000', '7' -> {   // 普通文件
                        val target = File(destRoot, name.trimStart('.', '/'))
                        if (!target.canonicalPath.startsWith(destCanonical)) {
                            throw IOException("tar 条目越界: $name")
                        }
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { output ->
                            var left = size
                            val buf = ByteArray(1 shl 16)
                            while (left > 0) {
                                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                                if (n < 0) throw IOException("tar 数据意外结束: $name")
                                output.write(buf, 0, n)
                                left -= n
                            }
                        }
                        val rel = target.relativeTo(destRoot).path.replace('\\', '/')
                        if (EXECUTABLE_PREFIXES.any { rel.startsWith(it) }) {
                            target.setExecutable(true, false)
                        }
                        target.setReadable(true, false)
                        entries++
                        onProgress(entries)
                        skipPadding(input, size)
                    }
                    '2' -> {   // 符号链接：载荷里是 bin/<工具> -> wine（winecfg/wineboot/notepad/…共 12 个）
                        val linkName = String(header, 157, 100, Charsets.UTF_8).substringBefore('\u0000')
                        val target = File(destRoot, name.trimStart('.', '/'))
                        if (target.canonicalPath.startsWith(destCanonical)) {
                            target.parentFile?.mkdirs()
                            val linked = try {
                                java.nio.file.Files.createSymbolicLink(
                                    target.toPath(), java.nio.file.Paths.get(linkName)
                                )
                                true
                            } catch (e: Exception) {
                                false
                            }
                            if (!linked) {
                                // 文件系统不支持软链时退化为复制被指向的文件（例如 bin/winecfg ← bin/wine）
                                val src = File(target.parentFile, linkName)
                                if (src.isFile) src.copyTo(target, overwrite = true)
                            } else if (EXECUTABLE_PREFIXES.any {
                                    target.relativeTo(destRoot).path.replace('\\', '/').startsWith(it)
                                }) {
                                target.setExecutable(true, false)
                            }
                        }
                        skipPadding(input, size)
                    }
                    else -> {  // 其它类型（硬链接/设备节点等）：跳过数据
                        var left = size
                        val buf = ByteArray(1 shl 16)
                        while (left > 0) {
                            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                            if (n < 0) break
                            left -= n
                        }
                        skipPadding(input, size)
                    }
                }
            }
        }
        onProgress(entries)
    }

    private fun skipPadding(input: InputStream, size: Long) {
        val pad = ((512 - (size % 512)) % 512).toInt()
        var left = pad
        while (left > 0) {
            val n = input.read()
            if (n < 0) return
            left--
        }
    }
}
