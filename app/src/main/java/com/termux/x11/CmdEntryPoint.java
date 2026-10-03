package com.termux.x11;

import android.os.ParcelFileDescriptor;
import android.util.Log;

import dalvik.annotation.optimization.CriticalNative;

/**
 * libXlorie.so 的 X 服务器入口（内嵌版）。
 *
 * <p>上游 Termux:X11 把它作为 {@code app_process} 的命令行入口运行，并通过广播 + Binder
 * 与 App 通信。Proton-droid 直接把 X 服务器跑在自己的进程里：
 * <ol>
 *   <li>{@link #startServer()} → native {@code start(args)} 起线程执行 {@code dix_main}（Xorg + lorie DDX）</li>
 *   <li>{@link #getXConnection()} → native 建 socketpair，返回 App 端 fd</li>
 *   <li>把 fd 交给 {@code LorieView.connect(fd)}，渲染器即可用该控制通道把画面送进 Surface</li>
 * </ol>
 *
 * <p>方法名 / 签名必须与 libXlorie.so 中的 {@code RegisterNatives} 表完全一致，
 * 否则 {@code JNI_OnLoad} 注册失败。
 */
public class CmdEntryPoint {

    private static final String TAG = "CmdEntryPoint";

    private final String[] args;

    public CmdEntryPoint(String[] args) {
        this.args = args;
    }

    /** 启动 X 服务器；native 会另起线程执行 Xorg 主循环，本调用很快返回。 */
    public boolean startServer() {
        Log.i(TAG, "starting X server, args=" + String.join(" ", args));
        return start(args);
    }

    /** 取 App 端的控制通道 fd（送达 LorieView.connect 后由渲染器接管并负责关闭）。 */
    public ParcelFileDescriptor takeXConnection() {
        return getXConnection();
    }

    // ------------------------------------------------------------------
    // native（注册名不可改）
    // ------------------------------------------------------------------

    public native boolean start(String[] args);

    public native ParcelFileDescriptor getXConnection();

    public native ParcelFileDescriptor getLogcatOutput();

    public native void reportFatalError(String message);

    @CriticalNative
    public static native boolean connected();

    // ------------------------------------------------------------------
    // 由 native 回调：上游用来广播 Binder 给 App（内嵌场景无需动作）
    // ------------------------------------------------------------------

    @SuppressWarnings("unused")
    void sendBroadcast() {
    }

    @SuppressWarnings("unused")
    void sendBroadcastDelayed() {
    }
}
