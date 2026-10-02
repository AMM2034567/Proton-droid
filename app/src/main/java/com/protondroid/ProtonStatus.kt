package com.protondroid

import android.os.Handler
import android.os.Looper

/**
 * 极简状态总线：把运行时状态同时派发给主界面与游戏视窗。
 * （避免为一次状态同步引入 LocalBroadcastManager 等额外依赖。）
 */
object ProtonStatus {

    private val handler = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<(String) -> Unit>()

    @Synchronized
    fun register(listener: (String) -> Unit) {
        listeners.add(listener)
    }

    @Synchronized
    fun unregister(listener: (String) -> Unit) {
        listeners.remove(listener)
    }

    fun publish(message: String) {
        val snapshot: List<(String) -> Unit>
        synchronized(this) { snapshot = listeners.toList() }
        handler.post { snapshot.forEach { it(message) } }
    }
}
