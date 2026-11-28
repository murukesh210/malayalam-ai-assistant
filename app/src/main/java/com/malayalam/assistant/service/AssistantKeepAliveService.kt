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
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.malayalam.assistant.AppGraph
import com.malayalam.assistant.MainActivity
import com.malayalam.assistant.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Android 14–17 Compliant `mediaPlayback` Foreground Service for One-Tap Background Mode:
 *  - Started explicitly from the Home Screen "Background Mode" toggle using
 *    `ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK` (no 6-hour `dataSync` timeout!).
 *  - Keeps background Malayalam dialogue & TTS reactions working when switching apps or locking the screen.
 *  - Includes a direct **"Stop Background Mode"** action button inside the persistent notification.
 */
class AssistantKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!AppGraph.from(applicationContext).prefs.isAssistantEnabledNow ||
            intent?.action == ACTION_STOP ||
            intent?.action == ACTION_STOP_FROM_NOTIFICATION
        ) {
            if (intent?.action == ACTION_STOP_FROM_NOTIFICATION) {
                CoroutineScope(Dispatchers.IO).launch {
                    AppGraph.from(applicationContext).prefs.setBackgroundModeActive(false)
                }
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIF_ID, notification, fgsType)
        return START_STICKY
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Background Mode (Dialogue Playback)",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Malayalam Assistant ready for background WhatsApp, SMS & Gmail audio reactions"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, AssistantKeepAliveService::class.java).apply {
                action = ACTION_STOP_FROM_NOTIFICATION
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_spark)
            .setContentTitle("Malayalam Assistant • Background Mode")
            .setContentText("Listening for WhatsApp, SMS & Gmail dialogue reactions")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent)
            .addAction(0, "Stop Background Mode", stopIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "assistant_background_media_channel"
        private const val NOTIF_ID = 4101
        private const val ACTION_STOP = "com.malayalam.assistant.STOP_KEEP_ALIVE"
        private const val ACTION_STOP_FROM_NOTIFICATION = "com.malayalam.assistant.STOP_BG_MODE_NOTIF"

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

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val act = intent?.action ?: return
        if (act != Intent.ACTION_BOOT_COMPLETED && act != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val graph = AppGraph.from(context)
                val settings = graph.prefs.snapshot()
                // Do NOT auto-start background services after reboot when Master Power is OFF!
                if (!settings.isAssistantEnabled) return@launch

                if (settings.backgroundModeActive || settings.alwaysOnKeepAliveEnabled) {
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
