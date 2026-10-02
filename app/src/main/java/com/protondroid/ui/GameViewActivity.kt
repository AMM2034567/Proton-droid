package com.protondroid.ui

import android.os.Build
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.protondroid.NativeBridge
import com.protondroid.R
import com.protondroid.service.ProtonForegroundService

class GameViewActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private lateinit var surfaceView: SurfaceView
    private lateinit var tvGameTitle: TextView
    private lateinit var btnExit: Button
    private var gamePath: String = ""

    companion object {
        const val EXTRA_GAME_PATH = "extra_game_path"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game_view)

        gamePath = intent.getStringExtra(EXTRA_GAME_PATH) ?: ""

        surfaceView = findViewById(R.id.surface_game_view)
        tvGameTitle = findViewById(R.id.tv_game_title)
        btnExit = findViewById(R.id.btn_exit_game)

        tvGameTitle.text = gamePath.substringAfterLast('/')

        // 沉浸式全屏
        enableImmersiveMode()

        surfaceView.holder.addCallback(this)

        btnExit.setOnClickListener {
            // 停止后台前台服务
            ProtonForegroundService.stopService(this)
            finish()
        }
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

    override fun surfaceCreated(holder: SurfaceHolder) {
        // 将底层 Surface 句柄传给 C++ NDK
        val attached = NativeBridge.nativeSetSurface(holder.surface)
        if (attached) {
            // 启动前台服务保活，并拉起 Proton 引擎
            if (gamePath.isNotEmpty()) {
                ProtonForegroundService.startService(this, gamePath)
            }
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // 视窗尺寸变更处理
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // 安全解绑 NDK 视窗指针，防止悬空崩溃
        NativeBridge.nativeReleaseSurface()
    }

    override fun onDestroy() {
        ProtonForegroundService.stopService(this)
        super.onDestroy()
    }
}
