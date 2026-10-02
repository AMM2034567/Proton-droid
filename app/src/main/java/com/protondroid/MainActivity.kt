package com.protondroid

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.protondroid.runtime.ProtonRuntimeInstaller
import com.protondroid.service.ProtonForegroundService
import com.protondroid.ui.GameViewActivity
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var installer: ProtonRuntimeInstaller
    private lateinit var tvStatusLog: TextView
    private lateinit var tvPageSize: TextView
    private lateinit var tvGpuStatus: TextView
    private lateinit var tvRuntimeStatus: TextView
    private lateinit var tvScannedCount: TextView
    private lateinit var etGamePath: EditText
    private lateinit var btnInstallRuntime: Button
    private lateinit var btnScanGames: Button
    private lateinit var btnLaunchGame: Button
    private lateinit var btnStopGame: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        installer = ProtonRuntimeInstaller(this)

        tvStatusLog = findViewById(R.id.tv_status_log)
        tvPageSize = findViewById(R.id.tv_page_size)
        tvGpuStatus = findViewById(R.id.tv_gpu_status)
        tvRuntimeStatus = findViewById(R.id.tv_runtime_status)
        tvScannedCount = findViewById(R.id.tv_scanned_count)
        etGamePath = findViewById(R.id.et_game_path)
        btnInstallRuntime = findViewById(R.id.btn_install_runtime)
        btnScanGames = findViewById(R.id.btn_scan_games)
        btnLaunchGame = findViewById(R.id.btn_launch_game)
        btnStopGame = findViewById(R.id.btn_stop_game)

        tvStatusLog.movementMethod = ScrollingMovementMethod()

        // 1. 检查存储权限
        checkStoragePermissions()

        // 2. 诊断底层系统与硬件
        refreshDashboard()

        // 3. 扫描本地游戏
        scanGames()

        // 4. 事件监听
        btnInstallRuntime.setOnClickListener {
            appendLog("[INFO] 开始安装 Proton 11 ARM64 私有沙箱运行时...")
            btnInstallRuntime.isEnabled = false
            installer.installFromTar(
                onProgress = { msg -> appendLog(msg) },
                onComplete = { success ->
                    btnInstallRuntime.isEnabled = true
                    refreshDashboard()
                    if (success) {
                        Toast.makeText(this, "Proton 核心安装成功！", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Proton 核心安装失败，请查看日志！", Toast.LENGTH_LONG).show()
                    }
                }
            )
        }

        btnScanGames.setOnClickListener {
            scanGames()
        }

        btnLaunchGame.setOnClickListener {
            val gamePath = etGamePath.text.toString().trim()
            if (gamePath.isEmpty()) {
                appendLog("[WARN] 请输入或选择有效的 Windows 游戏路径！")
                return@setOnClickListener
            }

            // 启动全屏直通渲染视窗
            val intent = Intent(this, GameViewActivity::class.java).apply {
                putExtra(GameViewActivity.EXTRA_GAME_PATH, gamePath)
            }
            startActivity(intent)
        }

        btnStopGame.setOnClickListener {
            ProtonForegroundService.stopService(this)
            appendLog("[INFO] 已发送停止信号至前台服务")
        }
    }

    override fun onResume() {
        super.onResume()
        refreshDashboard()
    }

    private fun checkStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                appendLog("[WARN] 需要所有文件访问权限以读取 /sdcard 游戏安装包")
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            }
        }
    }

    private fun refreshDashboard() {
        // 分页大小
        val pageSize = NativeBridge.getSystemPageSize()
        tvPageSize.text = if (pageSize == 4096) {
            "内存分页: 4096 字节 (4KB) [原生适配]"
        } else {
            "内存分页: $pageSize 字节 (非 4KB)"
        }

        // Mali GPU
        val gpuOk = NativeBridge.checkGpuNodeAccess()
        tvGpuStatus.text = if (gpuOk) {
            "GPU 节点 (/dev/mali0): 正常 (R/W 直接访问)"
        } else {
            "GPU 节点 (/dev/mali0): 标准 Vulkan 驱动访问"
        }

        // 运行时状态
        val installed = installer.isRuntimeInstalled()
        if (installed) {
            tvRuntimeStatus.text = "Proton 核心: 已就绪 (/data/data/$packageName/files/runtime)"
            tvRuntimeStatus.setTextColor(0xFF4CAF50.toInt())
        } else {
            tvRuntimeStatus.text = "Proton 核心: 未安装 (点击下方按钮一键导入)"
            tvRuntimeStatus.setTextColor(0xFFFF9800.toInt())
        }
    }

    private fun scanGames() {
        val gamesDir = File("/sdcard/Download/ProtonDroid/games")
        if (!gamesDir.exists()) {
            gamesDir.mkdirs()
        }

        val exeList = mutableListOf<File>()
        gamesDir.walkTopDown().maxDepth(3).forEach { file ->
            if (file.isFile && file.extension.equals("exe", ignoreCase = true)) {
                exeList.add(file)
            }
        }

        tvScannedCount.text = "已发现 ${exeList.size} 款游戏"
        if (exeList.isNotEmpty()) {
            // 默认选中第一款（优先排除清理和安装包）
            val primaryGame = exeList.firstOrNull {
                !it.name.contains("install", ignoreCase = true) &&
                !it.name.contains("cleanup", ignoreCase = true)
            } ?: exeList.first()

            etGamePath.setText(primaryGame.absolutePath)
            appendLog("[SCAN] 自动选中目标: ${primaryGame.name} (${primaryGame.absolutePath})")
        } else {
            etGamePath.setText("/sdcard/Download/ProtonDroid/games/goose/GooseDesktop.exe")
        }
    }

    private fun appendLog(msg: String) {
        val current = tvStatusLog.text.toString()
        tvStatusLog.text = "$current\n$msg"
    }
}
