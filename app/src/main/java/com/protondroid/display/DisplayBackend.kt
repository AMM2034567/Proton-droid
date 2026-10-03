package com.protondroid.display

import android.content.Context
import java.io.File

/**
 * 显示后端选择。
 *
 * 两条并行路线：
 *  - [X11]（默认，B 方案）：内嵌 X 服务器（`libXlorie.so`）+ X11 后端 wine，现有可用路径，**不动**；
 *  - [ANDROID]（C 方案）：不启 X，直接用上游 `wineandroid.drv`（已在 CI 里编出并打补丁）
 *    在 `TextureView`/`ANativeWindow` 上呈现，入口是 `org.winehq.wine.WineActivity`。
 *
 * 开关放在 `<filesDir>/display_backend.txt`（一行纯文本），方便 adb 切换与灰度：
 *   adb shell "run-as com.protondroid sh -c 'echo android > files/display_backend.txt'"
 * 文件缺失/内容非法 ⇒ 回落到 [X11]，保证默认不回归。
 */
object DisplayBackend {

    const val X11 = "x11"
    const val ANDROID = "android"

    private const val FILE_NAME = "display_backend.txt"

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    /** 当前后端；非法或缺失一律当 [X11]。 */
    fun current(context: Context): String {
        val f = file(context)
        if (!f.isFile) return X11
        val v = runCatching { f.readText().trim().lowercase() }.getOrDefault("")
        return when {
            v.startsWith(ANDROID) -> ANDROID
            v.startsWith(X11) -> X11
            else -> X11
        }
    }

    fun isAndroid(context: Context): Boolean = current(context) == ANDROID

    /** 写开关（UI/调试用）；value 非 [ANDROID] 一律写 [X11]。 */
    fun set(context: Context, value: String) {
        val v = if (value.trim().lowercase().startsWith(ANDROID)) ANDROID else X11
        runCatching { file(context).writeText("$v\n") }
    }
}
