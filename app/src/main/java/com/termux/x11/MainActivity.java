package com.termux.x11;

/**
 * 仅为满足 libXlorie.so 的 JNI 契约而存在的桩类。
 *
 * <p>native 侧在 {@code nativeInit()} 时会
 * {@code FindClass("com/termux/x11/MainActivity")}（找不到会直接致命退出），
 * 并查找实例方法 {@code clientConnectedStateChanged()V}。
 * 它只在 {@code LorieView.activity} 字段非空时才被回调 —— 内嵌场景我们保持该字段为 null，
 * 因此这里的方法体可以为空。
 *
 * <p>注意：与本项目自身的 {@code com.protondroid.MainActivity} 不是同一个类，
 * 也不要在 AndroidManifest 里引用它。
 */
public class MainActivity {

    /** 连接断开时由 native 回调（内嵌场景不使用）。 */
    @SuppressWarnings("unused")
    public void clientConnectedStateChanged() {
    }
}
