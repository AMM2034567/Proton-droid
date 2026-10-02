package com.protondroid

import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var processManager: ProtonProcessManager
    private lateinit var tvStatusLog: TextView
    private lateinit var tvPageSize: TextView
    private lateinit var tvGpuStatus: TextView
    private lateinit var etGamePath: EditText
    private lateinit var btnLaunch: Button
    private lateinit var btnStop: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        processManager = ProtonProcessManager(this)

        tvStatusLog = findViewById(R.id.tv_status_log)
        tvPageSize = findViewById(R.id.tv_page_size)
        tvGpuStatus = findViewById(R.id.tv_gpu_status)
        etGamePath = findViewById(R.id.et_game_path)
        btnLaunch = findViewById(R.id.btn_launch)
        btnStop = findViewById(R.id.btn_stop)

        tvStatusLog.movementMethod = ScrollingMovementMethod()

        // 1. 刷新硬件诊断信息
        diagnoseHardware()

        // 2. 注册状态回调
        processManager.setStatusListener { message ->
            appendLog(message)
        }

        // 3. 按钮交互
        btnLaunch.setOnClickListener {
            val gamePath = etGamePath.text.toString().trim()
            if (gamePath.isEmpty()) {
                appendLog("[WARN] 请先输入有效的游戏可执行文件路径！")
                return@setOnClickListener
            }
            processManager.launchGame(gamePath)
        }

        btnStop.setOnClickListener {
            processManager.stopSession()
        }
    }

    private fun diagnoseHardware() {
        val pageSize = NativeBridge.getSystemPageSize()
        if (pageSize == 4096) {
            tvPageSize.text = "内存分页: 4096 字节 (4KB) [完美适配 x86 仿真]"
        } else {
            tvPageSize.text = "内存分页: $pageSize 字节 (非 4KB, 可能存在兼容风险)"
        }

        val gpuOk = NativeBridge.checkGpuNodeAccess()
        if (gpuOk) {
            tvGpuStatus.text = "GPU 节点 (/dev/mali0): 已具备直接访问权限"
        } else {
            tvGpuStatus.text = "GPU 节点 (/dev/mali0): 权限受限 (通过 Vulkan 驱动访问)"
        }
    }

    private fun appendLog(msg: String) {
        val current = tvStatusLog.text.toString()
        val newText = "$current\n$msg"
        tvStatusLog.text = newText
    }
}
