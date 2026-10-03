package com.protondroid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import com.protondroid.runtime.ProtonLayout
import com.protondroid.runtime.ProtonRuntimeInstaller
import com.protondroid.service.ProtonForegroundService
import com.protondroid.ui.GameViewActivity
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var installer: ProtonRuntimeInstaller
    private lateinit var processManager: ProtonProcessManager
    private lateinit var tvStatusLog: TextView
    private lateinit var tvPageSize: TextView
    private lateinit var tvGpuStatus: TextView
    private lateinit var tvRuntimeStatus: TextView
    private lateinit var tvScannedCount: TextView
    private lateinit var etGamePath: EditText
    private lateinit var btnInstallRuntime: Button
    private lateinit var btnScanGames: Button
    private lateinit var btnSelfTest: Button
    private lateinit var btnLaunchGame: Button
    private lateinit var btnStopGame: Button

    private val statusListener: (String) -> Unit = { message ->
        runOnUiThread { appendLog(message) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        installer = ProtonRuntimeInstaller(this)
        processManager = ProtonProcessManager(this)
        processManager.setStatusListener { ProtonStatus.publish(it) }

        tvStatusLog = findViewById(R.id.tv_status_log)
        tvPageSize = findViewById(R.id.tv_page_size)
        tvGpuStatus = findViewById(R.id.tv_gpu_status)
        tvRuntimeStatus = findViewById(R.id.tv_runtime_status)
        tvScannedCount = findViewById(R.id.tv_scanned_count)
        etGamePath = findViewById(R.id.et_game_path)
        btnInstallRuntime = findViewById(R.id.btn_install_runtime)
        btnScanGames = findViewById(R.id.btn_scan_games)
        btnSelfTest = findViewById(R.id.btn_self_test)
        btnLaunchGame = findViewById(R.id.btn_launch_game)
        btnStopGame = findViewById(R.id.btn_stop_game)

        tvStatusLog.movementMethod = ScrollingMovementMethod()

        // 1. 检查存储权限
        checkStoragePermissions()

        // 1.1 清掉上次被系统回收时遗留的运行时进程
        val swept = processManager.sweepStaleProcesses()
        if (swept > 0) appendLog("[INFO] 已清理上次遗留的运行时进程 $swept 个")

        // 2. 诊断底层系统与硬件
        refreshDashboard()

        // 3. 扫描本地游戏
        scanGames()

        // 4. 事件监听
        btnInstallRuntime.setOnClickListener {
            appendLog("[INFO] 开始装配 Proton 11 ARM64 独立沙箱运行时...")
            btnInstallRuntime.isEnabled = false
            installer.installStandaloneRuntime(
                onProgress = { msg -> appendLog(msg) },
                onComplete = { success ->
                    btnInstallRuntime.isEnabled = true
                    refreshDashboard()
                    if (success) {
                        Toast.makeText(this, "Proton 核心安装成功！", Toast.LENGTH_SHORT).show()
                        runSelfTestAsync()
                    } else {
                        Toast.makeText(this, "Proton 核心安装失败，请查看日志！", Toast.LENGTH_LONG).show()
                    }
                }
            )
        }

        btnScanGames.setOnClickListener {
            scanGames()
        }

        btnSelfTest.setOnClickListener {
            runSelfTestAsync()
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

    override fun onStart() {
        super.onStart()
        ProtonStatus.register(statusListener)
    }

    override fun onStop() {
        ProtonStatus.unregister(statusListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshDashboard()
    }

    /**
     * 运行时自检：真正 fork/exec 一次 `proot --version`。
     * Android 10+ 的 W^X/SELinux 限制会让私有目录内的二进制无法执行，
     * 自检能立刻把这类根因暴露出来。
     */
    private fun runSelfTestAsync() {
        appendLog("[自检] 正在验证 PRoot 工具链可执行性...")
        Thread {
            val result = processManager.prootSelfTest()
            runOnUiThread {
                appendLog("[自检] $result")
                appendLog("[自检] 运行日志尾部:\n" + processManager.readLogTail(15))
            }
        }.start()
    }

    /**
     * targetSdk=28 走 legacy storage：运行时授予 READ/WRITE_EXTERNAL_STORAGE
     * 即可直接以路径方式访问 /sdcard（游戏目录、rootfs.tar.gz、Proton 载荷）。
     * Android 11+ 的“所有文件访问权限”作为兜底，不强制打断用户。
     */
    private fun checkStoragePermissions() {
        val needed = listOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

        if (needed.isNotEmpty()) {
            appendLog("[WARN] 正在申请存储权限（用于读取 /sdcard 上的游戏与运行时包）...")
            requestPermissions(needed.toTypedArray(), REQUEST_STORAGE)
        } else {
            noteAllFilesAccess()
        }
    }

    private fun noteAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            appendLog("[INFO] 可选：如需访问 /sdcard 全部目录，可在系统设置中开启“所有文件访问权限”")
        }
    }

    private fun openAllFilesAccessSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (ignored: Exception) {
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_STORAGE) return

        val granted = grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (granted) {
            appendLog("[INFO] 存储权限已授予")
            scanGames()
            refreshDashboard()
        } else {
            appendLog("[WARN] 存储权限被拒绝，无法读取 /sdcard；尝试引导到“所有文件访问权限”设置页")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) openAllFilesAccessSettings()
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
            val layout = ProtonLayout(this)
            val guestRoot = layout.resolveGuestRoot()
            tvRuntimeStatus.text = "Proton 核心: 已就绪 (guest root: ${guestRoot?.absolutePath ?: "?"})"
            tvRuntimeStatus.setTextColor(0xFF4CAF50.toInt())
        } else {
            tvRuntimeStatus.text = "Proton 核心: 未安装 (点击下方按钮一键装配)"
            tvRuntimeStatus.setTextColor(0xFFFF9800.toInt())
        }

        val x11 = com.protondroid.display.XServer.isDisplayReachable()
        tvGpuStatus.append(if (x11) "\nX11 显示 :0: 已连接" else "\nX11 显示 :0: 未启动 (进入游戏页会自动拉起内嵌 X 服务器)")
    }

    private fun scanGames() {
        val gamesDir = File(ProtonLayout.PUBLIC_GAMES_DIR)
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
            appendLog("[SCAN] 未在 ${ProtonLayout.PUBLIC_GAMES_DIR} 下发现 .exe")
        }
    }

    private fun appendLog(msg: String) {
        val current = tvStatusLog.text.toString()
        tvStatusLog.text = "$current\n$msg"
    }

    companion object {
        private const val REQUEST_STORAGE = 1001
    }
}
