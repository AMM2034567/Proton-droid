package com.protondroid.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.protondroid.ProtonStatus
import com.protondroid.R
import com.protondroid.display.DisplayBackend
import com.protondroid.display.XServer
import com.protondroid.service.ProtonForegroundService
import com.termux.x11.LorieView

/**
 * 游戏视窗。
 *
 * 两条后端（见 [DisplayBackend]）：
 *  - B 方案（默认 `x11`）：内嵌 X 服务器（Termux-X11 的 `libXlorie.so`）把 X 画面通过 EGL 合成到
 *    本页的 [LorieView]；wine 以 `DISPLAY=:0` 连到同一个 X 服务器；
 *  - C 方案（`android`）：**不启 X**，直接把 `org.winehq.wine.WineActivity` 拉到前台，
 *    由 `wineandroid.drv` 直接往 `ANativeWindow` 呈现。
 */
class GameViewActivity : AppCompatActivity() {

    private lateinit var lorieView: LorieView
    private lateinit var tvGameTitle: TextView
    private lateinit var tvGameStatus: TextView
    private lateinit var btnExit: Button

    private var gamePath: String = ""
    private var gameLaunched = false

    private val statusListener: (String) -> Unit = { message ->
        runOnUiThread { appendStatus(message) }
    }

    companion object {
        const val EXTRA_GAME_PATH = "extra_game_path"
        private const val TAG = "GameViewActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        gamePath = intent.getStringExtra(EXTRA_GAME_PATH) ?: ""

        // ── C 方案分支：不启 X 服务器，直接把 wine 的 Activity 拉到前台 ──
        // 后端选择见 DisplayBackend（files/display_backend.txt，默认 x11 ⇒ 老路径不回归）。
        if (DisplayBackend.isAndroid(this)) {
            val wineCmdline = windowsCmdlineFor(gamePath)
            Log.i(TAG, "display_backend=android ⇒ 启动 WineActivity, cmdline=$wineCmdline")
            val intent = Intent().setClassName(this, "org.winehq.wine.WineActivity")
            if (wineCmdline != null) intent.putExtra("cmdline", wineCmdline)
            startActivity(intent)
            finish()
            return
        }

        setContentView(R.layout.activity_game_view)

        lorieView = findViewById(R.id.surface_game_view)
        tvGameTitle = findViewById(R.id.tv_game_title)
        tvGameStatus = findViewById(R.id.tv_game_status)
        btnExit = findViewById(R.id.btn_exit_game)

        tvGameTitle.text = gamePath.substringAfterLast('/')

        enableImmersiveMode()

        btnExit.setOnClickListener {
            ProtonForegroundService.stopService(this)
            finish()
        }

        // 1) 先拉起内嵌 X 服务器，保证 LorieView 能拿到控制通道 fd
        if (XServer.ensureStarted(this)) {
            appendStatus("内嵌 X 服务器 (DISPLAY=:${XServer.DISPLAY}) 已就绪")
        } else {
            appendStatus("错误: 内嵌 X 服务器启动失败 (libXlorie.so)")
        }

        // 2) Surface 就绪后再拉起 Proton（wine 需要 X 服务器）
        lorieView.setSurfaceReadyListener {
            XServer.onViewAttached(lorieView)
            appendStatus("渲染视图已就绪，正在启动 Proton...")
            launchGameOnce()
        }
    }

    private fun launchGameOnce() {
        if (gameLaunched || gamePath.isEmpty()) return
        gameLaunched = true
        ProtonForegroundService.startService(this, gamePath)
    }

    /**
     * 把 Android 侧路径转成 wine 能认的 Windows 路径。
     * wine 默认把 `/` 映射成 `Z:`，所以 `/sdcard/Download/a.exe` → `Z:\sdcard\Download\a.exe`。
     * 传空表示不指定（WineActivity 走它自己的默认程序 winecfg.exe）。
     */
    private fun windowsCmdlineFor(hostPath: String): String? =
        if (hostPath.isEmpty()) null else "Z:" + hostPath.replace('/', '\\')

    override fun onStart() {
        super.onStart()
        ProtonStatus.register(statusListener)
    }

    override fun onStop() {
        ProtonStatus.unregister(statusListener)
        super.onStop()
    }

    private fun appendStatus(message: String) {
        val current = tvGameStatus.text.toString()
        val lines = (current + "\n" + message).trim().lines()
        tvGameStatus.text = if (lines.size > 8) lines.takeLast(8).joinToString("\n") else lines.joinToString("\n")
    }

    private fun enableImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        }
    }

    override fun onDestroy() {
        ProtonForegroundService.stopService(this)
        super.onDestroy()
    }
}
