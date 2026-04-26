package com.dxkj.myshell

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * 有活跃 SSH 本地端口转发时以前台服务形式保活，减轻切后台后系统回收进程/掐断监听的概率。
 */
class PortForwardHoldService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = intent?.getIntExtra(EXTRA_COUNT, 0) ?: 0
        if (n <= 0) {
            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification(this, n))
        return START_STICKY
    }

    companion object {
        const val EXTRA_COUNT = "active_forward_count"
        private const val NOTIFICATION_ID = 7102

        fun update(context: Context, activeForwardCount: Int) {
            val app = context.applicationContext
            val i = Intent(app, PortForwardHoldService::class.java).putExtra(EXTRA_COUNT, activeForwardCount)
            if (activeForwardCount > 0) {
                ContextCompat.startForegroundService(app, i)
            } else {
                try {
                    app.stopService(Intent(app, PortForwardHoldService::class.java))
                } catch (_: Throwable) {
                }
            }
        }

        private fun buildNotification(ctx: Context, count: Int): Notification {
            val open = PendingIntent.getActivity(
                ctx,
                0,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setContentTitle("端口转发运行中")
                .setContentText("当前 $count 条本地转发；点按返回应用")
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        }

        internal const val CHANNEL_ID = "port_forward_hold"
    }
}
