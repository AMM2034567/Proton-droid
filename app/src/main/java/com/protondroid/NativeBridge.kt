package com.protondroid

object NativeBridge {
    init {
        System.loadLibrary("proton_bridge")
    }

    external fun getSystemPageSize(): Int

    external fun checkGpuNodeAccess(): Boolean

    /** 检测 X11 显示是否可达（抽象 socket + /tmp 路径，用于外部 X 服务器如 Termux-X11） */
    external fun checkX11Display(display: Int): Boolean

    /** 检测指定路径的 unix socket 是否可连接（内嵌 libXlorie 的 socket 位于 App 私有目录） */
    external fun checkUnixSocket(path: String): Boolean

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

    /**
     * 清理本应用遗留的 proot/wine 进程（应用被回收后成为孤儿的那些）。
     * @param activePid 当前活跃会话的 pid；若该进程仍存活则整个清理会被跳过
     */
    external fun cleanupStaleProcesses(filesDir: String, activePid: Int): Int

    /**
     * 轮询子进程状态。
     * @return 0=仍在运行；100+code=正常退出；-(100+sig)=被信号终止；-1=已回收
     */
    external fun waitPid(pid: Int): Int

    // 视窗直通
    external fun nativeSetSurface(surface: android.view.Surface): Boolean

    external fun nativeReleaseSurface()

    external fun nativeDrawTestPattern(color: Int): Boolean

    /**
     * 用 RTLD_GLOBAL 加载 .so（C 方案用）。
     * wine 的 wineandroid.drv 初始化时会按**名字** dlopen("ntdll.so")/("wineandroid.so")，
     * 需要这些库对全局命名空间可见；System.load 是 RTLD_LOCAL，且运行时改 LD_LIBRARY_PATH
     * 依赖 android_update_LD_LIBRARY_PATH（现代 Android 上拿不到）⇒ 这里显式提升为 GLOBAL。
     */
    external fun nativeLoadGlobal(path: String): Boolean
}
