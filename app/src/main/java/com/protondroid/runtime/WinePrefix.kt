package com.protondroid.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * wine prefix 初始化。
 *
 * Proton 的发行包自带 `files/share/default_pfx_arm64` 模板，直接复制比 `wineboot` 更快、
 * 更省事；但模板里的符号链接是相对于模板目录的，克隆到别处后会断链，
 * 因此克隆时统一改写为 **guest 绝对路径**（例如 `/opt/proton/files/lib/wine/...`），
 * 这样在 PRoot guest 内部才能解析。
 */
object WinePrefix {

    fun ensure(layout: ProtonLayout, guestRoot: File, onLog: (String) -> Unit): Boolean {
        if (layout.isPrefixValid(guestRoot)) return true

        val template = layout.prefixTemplate(guestRoot)
        if (!template.isDirectory) {
            onLog("[PFX] 未找到预置 prefix 模板，将由 wine 首次启动自行初始化")
            return false
        }

        onLog("[PFX] 正在从 default_pfx_arm64 克隆 wine prefix（符号链接改写为 guest 绝对路径）...")
        val ok = cloneTemplate(layout, guestRoot, template, layout.prefixDir(guestRoot), onLog)
        onLog(if (ok) "[PFX] wine prefix 就绪：${ProtonLayout.PREFIX_GUEST_PATH}" else "[PFX] wine prefix 克隆失败")
        return ok
    }

    fun cloneTemplate(
        layout: ProtonLayout,
        guestRoot: File,
        template: File,
        dest: File,
        onLog: (String) -> Unit
    ): Boolean {
        return try {
            if (dest.exists()) dest.deleteRecursively()
            dest.mkdirs()

            val guestRootPath = guestRoot.absolutePath.trimEnd('/')

            template.walkTopDown()
                .onEnter { dir -> dir == template || !dir.isSymbolicLink }
                .forEach { src ->
                    val relative = src.relativeTo(template).path
                    if (relative.isEmpty()) return@forEach
                    val dst = File(dest, relative)

                    when {
                        src.isSymbolicLink -> {
                            val link = src.readSymlink()
                            if (link == null) {
                                onLog("[PFX] 跳过无法读取的符号链接: $relative")
                            } else {
                                val hostTarget = (
                                    if (link.startsWith("/")) File(link)
                                    else File(src.parentFile, link)
                                    ).normalize().absolutePath

                                val guestTarget = when {
                                    hostTarget == guestRootPath -> "/"
                                    hostTarget.startsWith("$guestRootPath/") -> hostTarget.removePrefix(guestRootPath)
                                    else -> hostTarget
                                }

                                dst.parentFile?.mkdirs()
                                if (dst.exists() || dst.isSymbolicLink) dst.delete()
                                Files.createSymbolicLink(
                                    Paths.get(dst.absolutePath),
                                    Paths.get(guestTarget)
                                )
                            }
                        }

                        src.isDirectory -> dst.mkdirs()

                        src.isFile -> {
                            dst.parentFile?.mkdirs()
                            src.copyTo(dst, overwrite = true)
                        }
                    }
                }

            ensureDosDevice(dest, "c:", "../drive_c")
            ensureDosDevice(dest, "z:", "/")
            layout.isPrefixValid(guestRoot)
        } catch (t: Throwable) {
            onLog("[PFX] 克隆异常: ${t.message}")
            false
        }
    }

    private fun ensureDosDevice(prefix: File, name: String, target: String) {
        val link = File(prefix, "dosdevices/$name")
        link.parentFile?.mkdirs()
        if (link.exists() || link.isSymbolicLink) return
        try {
            Files.createSymbolicLink(Paths.get(link.absolutePath), Paths.get(target))
        } catch (ignored: Exception) {
        }
    }
}
