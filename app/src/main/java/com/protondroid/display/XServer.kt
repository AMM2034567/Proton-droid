package com.protondroid.display

import android.os.ParcelFileDescriptor
import android.util.Log
import com.protondroid.NativeBridge
import com.termux.x11.CmdEntryPoint
import com.termux.x11.LorieView

/**
 * 内嵌 X 服务器（Termux-X11 的 `libXlorie.so`）的启动与状态管理。
 *
 * 进程模型：与 App 同进程 —— `CmdEntryPoint.start(args)` 在 native 侧另起线程执行
 * Xorg 主循环（lorie DDX），无需 `app_process` / Binder / 广播。
 *
 * 数据通道：
 * ```
 * X 服务器线程 ──(socketpair, native 端 conn_fd)── LorieView.connect(fd) ── EGL 渲染进 Surface
 *                └─ X 客户端（wine）通过抽象 socket @/tmp/.X11-unix/X0 连接
 * ```
 */
object XServer {

    private const val TAG = "XServer"
    private const val DISPLAY_READY_TIMEOUT_MS = 8000L

    /** 当前使用的 display 编号（与 ProtonProcessManager 的 DISPLAY=:0 对应） */
    const val DISPLAY = 0

    @Volatile
    private var entry: CmdEntryPoint? = null

    @Volatile
    private var fdHandle: Int = -1

    /** App 端控制通道 fd；LorieView 用它在 surfaceCreated 时连上渲染器 */
    @JvmStatic
    fun getConnectionFd(): Int = fdHandle

    @JvmStatic
    fun isRunning(): Boolean = fdHandle >= 0

    /**
     * 启动 X 服务器并等待其抽象 socket 可连接。
     * 幂等：已启动时直接返回当前状态。
     */
    @JvmStatic
    @Synchronized
    fun ensureStarted(): Boolean {
        if (fdHandle >= 0) return true

        try {
            System.loadLibrary("Xlorie")
        } catch (t: Throwable) {
            Log.e(TAG, "loadLibrary(Xlorie) failed", t)
            return false
        }

        return try {
            // Xorg 风格参数：显示号 + 关闭访问控制（guest 内 wine 以同一 uid 连接，-ac 更省事）
            val instance = CmdEntryPoint(arrayOf(":$DISPLAY", "-ac", "-nolisten", "tcp"))
            if (!instance.startServer()) {
                Log.e(TAG, "native start() returned false")
                return false
            }

            val pfd: ParcelFileDescriptor = instance.takeXConnection() ?: run {
                Log.e(TAG, "getXConnection() returned null")
                return false
            }
            fdHandle = pfd.detachFd()
            entry = instance
            Log.i(TAG, "X server started on :$DISPLAY (app-side fd=$fdHandle)")

            if (!awaitDisplayReady()) {
                Log.w(TAG, "X server started but display :$DISPLAY not reachable yet")
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "failed to start embedded X server", t)
            false
        }
    }

    /** 轮询抽象 socket，等待 X 服务器真正开始监听 */
    private fun awaitDisplayReady(): Boolean {
        val deadline = System.currentTimeMillis() + DISPLAY_READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (NativeBridge.checkX11Display(DISPLAY)) return true
            try {
                Thread.sleep(100)
            } catch (ignored: InterruptedException) {
                return false
            }
        }
        return false
    }

    /**
     * 供 LorieView 在 Surface 就绪后调用（当前实现里 LorieView 会自行取 fd，
     * 保留此入口以便后续需要额外的 surface 重建逻辑）。
     */
    @JvmStatic
    fun onViewAttached(view: LorieView) {
        view.onServerReady()
    }
}
