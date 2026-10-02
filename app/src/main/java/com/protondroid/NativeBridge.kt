package com.protondroid

object NativeBridge {
    init {
        System.loadLibrary("proton_bridge")
    }

    external fun getSystemPageSize(): Int

    external fun checkGpuNodeAccess(): Boolean

    external fun forkAndExec(
        command: String,
        args: Array<String>,
        envs: Array<String>,
        logPath: String?
    ): Int

    external fun killProcess(pid: Int, sig: Int): Boolean

    external fun waitPid(pid: Int): Int
}
