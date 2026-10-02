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

    /** 清理本应用遗留的 proot/wine 进程（应用被回收后成为孤儿的那些） */
    external fun cleanupStaleProcesses(filesDir: String): Int

    /**
     * 轮询子进程状态。
     * @return 0=仍在运行；100+code=正常退出；-(100+sig)=被信号终止；-1=已回收
     */
    external fun waitPid(pid: Int): Int

    // 视窗直通
    external fun nativeSetSurface(surface: android.view.Surface): Boolean

    external fun nativeReleaseSurface()

    external fun nativeDrawTestPattern(color: Int): Boolean
}
