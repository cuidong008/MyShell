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
 * 在存在**已连接的 SSH 终端会话**和/或**活跃本地端口转发**时以前台服务保活，
 * 减轻切后台后系统回收进程、掐断长连接或本地监听的概率。
 */
class PortForwardHoldService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val forwards = intent?.getIntExtra(EXTRA_FORWARD_COUNT, 0) ?: 0
        val sshSessions = intent?.getIntExtra(EXTRA_SSH_SESSION_COUNT, 0) ?: 0
        if (forwards <= 0 && sshSessions <= 0) {
            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification(this, forwards, sshSessions))
        return START_STICKY
    }

    companion object {
        const val EXTRA_FORWARD_COUNT = "active_forward_count"
        const val EXTRA_SSH_SESSION_COUNT = "connected_ssh_session_count"
        private const val NOTIFICATION_ID = 7102

        fun update(
            context: Context,
            activeForwardCount: Int,
            connectedSshSessionCount: Int,
        ) {
            val app = context.applicationContext
            val i = Intent(app, PortForwardHoldService::class.java)
                .putExtra(EXTRA_FORWARD_COUNT, activeForwardCount)
                .putExtra(EXTRA_SSH_SESSION_COUNT, connectedSshSessionCount)
            if (activeForwardCount > 0 || connectedSshSessionCount > 0) {
                ContextCompat.startForegroundService(app, i)
            } else {
                try {
                    app.stopService(Intent(app, PortForwardHoldService::class.java))
                } catch (_: Throwable) {
                }
            }
        }

        private fun buildNotification(
            ctx: Context,
            forwardCount: Int,
            sshSessionCount: Int,
        ): Notification {
            val open = PendingIntent.getActivity(
                ctx,
                0,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val parts = mutableListOf<String>()
            if (sshSessionCount > 0) {
                parts += "${sshSessionCount} 个 SSH 会话"
            }
            if (forwardCount > 0) {
                parts += "${forwardCount} 条本地端口转发"
            }
            val text = if (parts.isEmpty()) "点按返回应用" else parts.joinToString("；") + "；点按返回应用"
            return NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setContentTitle("MyShell 后台保持连接")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        }

        internal const val CHANNEL_ID = "port_forward_hold"
    }
}
