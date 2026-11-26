package com.malayalam.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.malayalam.assistant.AppGraph
import com.malayalam.assistant.MainActivity
import com.malayalam.assistant.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Lightweight `START_STICKY` Foreground Service keeping WhatsApp, SMS, GPay/PhonePe, and
 * Notification Malayalam dialogue reactions alive even when the app is swiped closed!
 */
class AssistantKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        return START_STICKY
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Background Message Reactions",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Keeps Malayalam Assistant listening for WhatsApp, SMS, and notification reactions"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_spark)
            .setContentTitle("Malayalam Assistant")
            .setContentText("Malayalam Assistant listening for messages")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(pendingIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "assistant_keep_alive_channel"
        private const val NOTIF_ID = 4101
        private const val ACTION_STOP = "com.malayalam.assistant.STOP_KEEP_ALIVE"

        fun syncServiceState(context: Context, enabled: Boolean) {
            val appCtx = context.applicationContext
            if (enabled) {
                runCatching {
                    ContextCompat.startForegroundService(
                        appCtx,
                        Intent(appCtx, AssistantKeepAliveService::class.java)
                    )
                }
            } else {
                val stopIntent = Intent(appCtx, AssistantKeepAliveService::class.java).apply {
                    action = ACTION_STOP
                }
                runCatching { appCtx.stopService(Intent(appCtx, AssistantKeepAliveService::class.java)) }
                runCatching { appCtx.startService(stopIntent) }
            }
        }
    }
}

/**
 * Automatically restarts Always-On Background Message Reactions & Gmail Direct Watcher after reboot.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val act = intent?.action ?: return
        if (act != Intent.ACTION_BOOT_COMPLETED && act != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val graph = AppGraph.from(context)
                val settings = graph.prefs.snapshot()
                if (settings.alwaysOnKeepAliveEnabled) {
                    AssistantKeepAliveService.syncServiceState(context, true)
                }
                if (settings.gmailWatcherEnabled && settings.gmailAddress.isNotBlank()) {
                    GmailDirectWatcherService.syncServiceState(context, true)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
