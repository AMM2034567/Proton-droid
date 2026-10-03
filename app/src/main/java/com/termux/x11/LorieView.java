package com.termux.x11;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Point;
import android.util.AttributeSet;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import com.protondroid.display.XServer;

import dalvik.annotation.optimization.CriticalNative;

/**
 * 承载 X 服务器输出的 SurfaceView（最小内嵌版）。
 *
 * <p>与上游 Termux:X11 的 LorieView 保持 **完全一致的 JNI 方法签名**（libXlorie.so 按名字注册），
 * 但去掉偏好设置、额外按键条、剪贴板同步、手写笔等部分：
 * <ul>
 *   <li>Surface 建立时：{@code nativeInit()} → {@code connect(fd)} → {@code surfaceChanged(Surface)}</li>
 *   <li>尺寸变化时：按 libxcvt 的 8 像素粒度对齐后 {@code sendWindowChange()}，并 {@code setViewport()}</li>
 *   <li>输入：单指 = 左键拖动/点击，双指轻点 = 右键，键盘事件按 Android keycode 直传（native 负责映射）</li>
 * </ul>
 * 上游注释提到 X 屏幕会比请求值略小（libxcvt 会向下取整），这里同样做 8 对齐。
 */
@SuppressLint({"ViewConstructor", "ClickableViewAccessibility"})
public class LorieView extends SurfaceView implements SurfaceHolder.Callback {

    private static final String TAG = "LorieView";

    /**
     * native 通过 GetFieldID(..., "activity", "Lcom/termux/x11/MainActivity;") 读取该字段，
     * 因此字段名 / 类型不能改（保持 null 即不会触发 native 回调）。
     */
    @SuppressWarnings("unused")
    public MainActivity activity;

    private long mNativeContext = 0L;
    private boolean mConnected = false;
    private Surface mSurface = null;
    private Runnable surfaceReadyListener = null;

    /** X 屏幕尺寸（= 本视图尺寸按 8 对齐后的值） */
    private final Point p = new Point(0, 0);

    public LorieView(Context context) {
        this(context, null);
    }

    /** XML 布局膨胀时需要双参构造。 */
    public LorieView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public LorieView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        getHolder().addCallback(this);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setKeepScreenOn(true);
    }

    /** Surface 首次就绪时回调（避免在 Activity 里再加一个 SurfaceHolder.Callback）。 */
    public void setSurfaceReadyListener(Runnable listener) {
        this.surfaceReadyListener = listener;
    }

    public boolean isConnected() {
        return mConnected && mNativeContext != 0L;
    }

    // ------------------------------------------------------------------
    // SurfaceHolder.Callback
    // ------------------------------------------------------------------

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (mNativeContext == 0L) {
            mNativeContext = nativeInit();
            Log.i(TAG, "nativeInit -> " + mNativeContext);
        }
        mSurface = holder.getSurface();
        connectToServer();
        if (mNativeContext != 0L && mSurface != null) {
            surfaceChanged(mNativeContext, mSurface);
            updateScreenSize(getWidth(), getHeight(), true);
        }
        requestFocus();
        if (surfaceReadyListener != null) surfaceReadyListener.run();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        mSurface = holder.getSurface();
        if (mNativeContext == 0L || mSurface == null) return;
        surfaceChanged(mNativeContext, mSurface);
        updateScreenSize(width, height, true);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        mSurface = null;
        if (mNativeContext != 0L) surfaceChanged(mNativeContext, null);
    }

    @Override
    protected void onDetachedFromWindow() {
        mConnected = false;
        if (mNativeContext != 0L) {
            nativeDestroy(mNativeContext);
            mNativeContext = 0L;
        }
        super.onDetachedFromWindow();
    }

    /** 把 X 服务器的控制通道 fd 交给渲染器（fd 由 XServer 持有，-1 表示尚未就绪）。 */
    private void connectToServer() {
        if (mConnected || mNativeContext == 0L) return;
        int fd = XServer.getConnectionFd();
        if (fd < 0) {
            Log.w(TAG, "X server connection fd not ready yet");
            return;
        }
        connect(mNativeContext, fd);
        mConnected = true;
        Log.i(TAG, "connected to X server (fd=" + fd + ")");
    }

    /** 由 XServer 在服务器就绪后调用，用于补做连接。 */
    public void onServerReady() {
        if (mSurface == null || mNativeContext == 0L) return;
        connectToServer();
        surfaceChanged(mNativeContext, mSurface);
        updateScreenSize(getWidth(), getHeight(), true);
    }

    // ------------------------------------------------------------------
    // 尺寸 / viewport
    // ------------------------------------------------------------------

    private void updateScreenSize(int width, int height) {
        updateScreenSize(width, height, false);
    }

    /**
     * 重新下发 X 屏幕尺寸与 viewport。
     *
     * 关键：native 的 `Renderer::setWindow()` 会执行 `expectedW = expectedH = 0`，
     * 而每次 `surfaceChanged(ptr, surface)` 都会走这条路 —— 所以 **每次换 Surface 之后
     * 必须重新调用 setViewport**，否则渲染器会一直报
     * "Buffer N is not of expected size, expecting 0x0" 并丢弃所有帧（画面全黑）。
     */
    private void updateScreenSize(int width, int height, boolean force) {
        if (mNativeContext == 0L || width <= 0 || height <= 0) return;

        int granularity = 8; // libxcvt 水平方向粒度
        int alignedWidth = Math.max(granularity, Math.max(64, width - width % granularity));
        int alignedHeight = Math.max(64, height);

        if (!force && p.x == alignedWidth && p.y == alignedHeight) return;
        p.set(alignedWidth, alignedHeight);

        int framerate = (getDisplay() != null) ? (int) getDisplay().getRefreshRate() : 60;
        sendWindowChange(mNativeContext, p.x, p.y, framerate, "builtin");
        setViewport(mNativeContext, 0, 0, width, height, p.x, p.y, 0);
        Log.i(TAG, "X screen size -> " + p.x + "x" + p.y + " (view " + width + "x" + height + ")");
    }

    // ------------------------------------------------------------------
    // 输入
    // ------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mNativeContext == 0L || !mConnected) return true;

        // 双指轻点 = 右键（桌面右键菜单）
        if (event.getPointerCount() >= 2 && event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) {
            sendMouseEvent(mNativeContext, event.getX(), event.getY(), 3, true, false);
            sendMouseEvent(mNativeContext, event.getX(), event.getY(), 3, false, false);
            return true;
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                sendMouseEvent(mNativeContext, event.getX(), event.getY(), 1, true, false);
                break;
            case MotionEvent.ACTION_MOVE:
                sendMouseEvent(mNativeContext, event.getX(), event.getY(), 0, false, false);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                sendMouseEvent(mNativeContext, event.getX(), event.getY(), 1, false, false);
                break;
            default:
                break;
        }
        return true;
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (mNativeContext == 0L || !mConnected) return super.onGenericMotionEvent(event);

        // 外接鼠标滚轮：X11 button 4/5
        if (event.isFromSource(InputDevice.SOURCE_MOUSE) &&
                event.getActionMasked() == MotionEvent.ACTION_SCROLL) {
            float delta = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
            int button = delta > 0 ? 4 : 5;
            sendMouseEvent(mNativeContext, event.getX(), event.getY(), button, true, false);
            sendMouseEvent(mNativeContext, event.getX(), event.getY(), button, false, false);
            return true;
        }
        return super.onGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (mNativeContext == 0L || !mConnected) return super.dispatchKeyEvent(event);
        int keyCode = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        // scanCode 传 0，由 native 的 android_to_linux_keycode 表映射
        if (sendKeyEvent(mNativeContext, 0, keyCode, down)) return true;
        return super.dispatchKeyEvent(event);
    }

    // ------------------------------------------------------------------
    // 由 native 调用（保持与上游一致的签名）
    // ------------------------------------------------------------------

    @SuppressWarnings("unused")
    private void setRendererViewport(int viewportLeft, int viewportTop, int viewportWidth,
                                     int viewportHeight, float sourceLeft, float sourceTop,
                                     float sourceWidth, float sourceHeight) {
        // 内嵌版：画面始终铺满 SurfaceView，不需要额外变换
    }

    @SuppressWarnings("unused")
    void setClipboardText(String text) {
    }

    @SuppressWarnings("unused")
    void requestClipboard() {
    }

    @SuppressWarnings("unused")
    private void onSyncReply(int serial) {
    }

    @SuppressWarnings("unused")
    void resetIme() {
    }

    // ------------------------------------------------------------------
    // native 声明（名字 / 签名与 libXlorie.so 注册表逐一对应）
    // ------------------------------------------------------------------

    private native long nativeInit();

    private native void nativeDestroy(long ptr);

    private native void surfaceChanged(long ptr, Surface surface);

    private native void setViewport(long ptr, int x, int y, int width, int height,
                                    int expectedWidth, int expectedHeight, int hiddenBottom);

    private native void setFiltering(long ptr, int filtering);

    private native void setRendererZoom(long ptr, int percent);

    private native void setZoomAnchor(long ptr, float sourceX, float sourceY, float fracX, float fracY);

    private native void clearZoomAnchor(long ptr);

    private native void setFollowCursorPan(long ptr, boolean enabled);

    private native long getCursorPosition(long ptr);

    private native void sendSync(long ptr, int serial);

    private static native void connect(long ptr, int fd);

    @CriticalNative
    private static native boolean connected(long ptr);

    private static native void startLogcat(long ptr, int fd);

    private native void setClipboardSyncEnabled(long ptr, boolean enabled, boolean ignored);

    private native void sendClipboardAnnounce(long ptr);

    private native void sendClipboardEvent(long ptr, byte[] text);

    private native void sendWindowChange(long ptr, int width, int height, int framerate, String name);

    private native void sendMouseEvent(long ptr, float x, float y, int whichButton,
                                       boolean buttonDown, boolean relative);

    private native void sendTouchEvent(long ptr, int action, int id, int x, int y);

    private native void sendStylusEvent(long ptr, float x, float y, int pressure, int tiltX,
                                        int tiltY, int orientation, int buttons, boolean eraser,
                                        boolean mouseMode);

    private native void requestStylusEnabled(long ptr, boolean enabled);

    private native void sendLockKeysState(long ptr, int state);

    private native boolean sendKeyEvent(long ptr, int scanCode, int keyCode, boolean keyDown);

    private native void sendTextEvent(long ptr, byte[] text);

    private native boolean requestConnection(long ptr);

    @CriticalNative
    public static native long getLastInputTimestamp();

    @CriticalNative
    public static native void markUserActivity();
}
