package com.protondroid.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.protondroid.MainActivity
import com.protondroid.ProtonProcessManager

class ProtonForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var processManager: ProtonProcessManager

    companion object {
        const val CHANNEL_ID = "proton_droid_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.protondroid.ACTION_START"
        const val ACTION_STOP = "com.protondroid.ACTION_STOP"
        const val EXTRA_GAME_PATH = "extra_game_path"

        fun startService(context: Context, gamePath: String) {
            val intent = Intent(context, ProtonForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_GAME_PATH, gamePath)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, ProtonForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        processManager = ProtonProcessManager(this)
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopGameAndSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val gamePath = intent.getStringExtra(EXTRA_GAME_PATH) ?: "Windows Game"
                startForegroundWithNotification(gamePath)
                if (gamePath.isNotEmpty() && !processManager.isRunning()) {
                    processManager.launchGame(gamePath)
                }
            }
            else -> {
                startForegroundWithNotification("Windows Game")
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ProtonDroid::GameExecutionWakeLock"
        ).apply {
            acquire(6 * 60 * 60 * 1000L) // 最大持有 6 小时
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Proton 游戏运行服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保证 Windows 游戏进程在后台不被系统休眠或冻结"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundWithNotification(gamePath: String) {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, ProtonForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Proton-droid 正在运行")
            .setContentText("目标: ${gamePath.substringAfterLast('/')}")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止游戏", stopPendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopGameAndSelf() {
        processManager.stopSession()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopGameAndSelf()
        super.onDestroy()
    }
}
