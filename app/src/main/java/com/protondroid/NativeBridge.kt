package com.protondroid

object NativeBridge {
    init {
        System.loadLibrary("proton_bridge")
    }

    external fun getSystemPageSize(): Int

    external fun checkGpuNodeAccess(): Boolean

    /** 检测 X11 显示是否可达（Termux-X11 的抽象 socket） */
    external fun checkX11Display(display: Int): Boolean

    /**
     * fork + execve。
     * @return > 0 子进程 PID；< 0 表示 -errno（如 -13 = EACCES，-8 = ENOEXEC）
     */
    external fun forkAndExec(
        command: String,
        args: Array<String>,
        envs: Array<String>,
        logPath: String?
    ): Int

    external fun killProcess(pid: Int, sig: Int): Boolean

    external fun waitPid(pid: Int): Int

    // 视窗直通
    external fun nativeSetSurface(surface: android.view.Surface): Boolean

    external fun nativeReleaseSurface()

    external fun nativeDrawTestPattern(color: Int): Boolean
}
