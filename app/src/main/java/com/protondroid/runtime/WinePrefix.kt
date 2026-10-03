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

    /**
     * Proton 发行包里 DXVK 的 dll 名（位于 `files/lib/wine/dxvk/<arch>-windows/`）。
     * 注意：这些名称必须与 wine 在 prefix 里期望的覆盖点一致。
     */
    private val DXVK_DLLS = listOf("d3d8.dll", "d3d9.dll", "d3d10core.dll", "d3d11.dll", "dxgi.dll")

    /** arch 目录 → prefix 内对应的 Windows 目录 */
    private val DXVK_ARCH_MAP = listOf(
        "aarch64-windows" to "system32",   // 64 位 / WOW64 游戏的系统 dll 解析路径
        "i386-windows" to "syswow64"       // 32 位
    )

    fun ensure(layout: ProtonLayout, guestRoot: File, onLog: (String) -> Unit): Boolean {
        if (layout.isPrefixValid(guestRoot)) {
            // prefix 已存在也要确保 DXVK 覆盖点正确（历史 prefix 可能仍指向 wine 内建 dll）
            installDxvk(layout, guestRoot, onLog)
            return true
        }

        val template = layout.prefixTemplate(guestRoot)
        if (!template.isDirectory) {
            onLog("[PFX] 未找到预置 prefix 模板，将由 wine 首次启动自行初始化")
            return false
        }

        onLog("[PFX] 正在从 default_pfx_arm64 克隆 wine prefix（符号链接改写为 guest 绝对路径）...")
        val ok = cloneTemplate(layout, guestRoot, template, layout.prefixDir(guestRoot), onLog)
        if (ok) installDxvk(layout, guestRoot, onLog)
        onLog(if (ok) "[PFX] wine prefix 就绪：${ProtonLayout.PREFIX_GUEST_PATH}" else "[PFX] wine prefix 克隆失败")
        return ok
    }

    /**
     * 把 DXVK 的 dll 装进 prefix —— 等价于 Proton 发行版的 `proton` 脚本首次运行时做的事。
     *
     * 为什么必须做：我们直接拉起 `wine`，不经过 Proton 的启动脚本，而模板 prefix 里的
     * `system32|syswow64/{d3d11,dxgi,...}.dll` 默认指向 **wine 内建** 实现；
     * 配合 `WINEDLLOVERRIDES=d3d11=n,b` 时 wine 会判定"找到的是内建 dll"从而**永远不加载 DXVK**，
     * 表现为 D3D 游戏静默走 WineD3D（甚至黑屏），且看不到任何 DXVK 日志。
     *
     * 做法：把覆盖点改成指向 `files/lib/wine/dxvk/<arch>-windows/<dll>` 的 **guest 绝对路径** 符号链接；
     * 幂等，每次启动都可安全调用。
     */
    fun installDxvk(layout: ProtonLayout, guestRoot: File, onLog: (String) -> Unit): Boolean {
        val prefix = layout.prefixDir(guestRoot)
        if (!prefix.isDirectory) return false

        var installed = 0
        for (dll in DXVK_DLLS) {
            for ((archDir, winDir) in DXVK_ARCH_MAP) {
                val hostSrc = File(guestRoot, "opt/proton/files/lib/wine/dxvk/$archDir/$dll")
                if (!hostSrc.isFile) continue

                val link = File(prefix, "drive_c/windows/$winDir/$dll")
                link.parentFile?.mkdirs()
                try {
                    if (link.exists() || Files.isSymbolicLink(link.toPath())) link.delete()
                    Files.createSymbolicLink(
                        Paths.get(link.absolutePath),
                        Paths.get("/opt/proton/files/lib/wine/dxvk/$archDir/$dll")
                    )
                    installed++
                } catch (t: Throwable) {
                    onLog("[DXVK] 写符号链接失败 ${link.name}: ${t.message}")
                }
            }
        }
        onLog("[DXVK] 已安装 $installed 个 dll 覆盖点（system32=dxvk aarch64 / syswow64=dxvk i386）")
        return installed > 0
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
                .onEnter { dir -> dir == template || !Files.isSymbolicLink(dir.toPath()) }
                .forEach { src ->
                    val relative = src.relativeTo(template).path
                    if (relative.isEmpty()) return@forEach
                    val dst = File(dest, relative)

                    when {
                        Files.isSymbolicLink(src.toPath()) -> {
                            val link = Files.readSymbolicLink(src.toPath()).toString()
                            if (link.isEmpty()) {
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
                                if (dst.exists() || Files.isSymbolicLink(dst.toPath())) dst.delete()
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
        if (link.exists() || Files.isSymbolicLink(link.toPath())) return
        try {
            Files.createSymbolicLink(Paths.get(link.absolutePath), Paths.get(target))
        } catch (ignored: Exception) {
        }
    }
}
