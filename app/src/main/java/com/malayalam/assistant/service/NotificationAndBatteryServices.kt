package com.malayalam.assistant.service

import android.app.Notification
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.malayalam.assistant.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

object AudioOutputGuard {

    private val OTP_KEYWORDS = listOf(
        "otp", "one-time password", "one time password", "verification code", "security code", "passcode", "auth code"
    )
    private val OTP_DIGITS_REGEX = Regex("\\b\\d{4,8}\\b")

    fun isLikelyOtp(text: String): Boolean {
        val lower = text.lowercase()
        return OTP_DIGITS_REGEX.containsMatchIn(lower) && OTP_KEYWORDS.any { lower.contains(it) }
    }

    fun canPlayReactionNow(context: Context, headphonesOnly: Boolean): Boolean {
        // 1. Respect Do Not Disturb (DND)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (nm != null &&
            nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL &&
            nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        ) {
            return false
        }

        // 2. Respect Silent / Vibrate ringer mode
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) {
            return false
        }

        // 3. Enforce "Headphones / Bluetooth Only" when toggled ON
        if (headphonesOnly && !isHeadsetOrBluetoothConnected(am)) {
            return false
        }
        return true
    }

    fun isHeadsetOrBluetoothConnected(am: AudioManager): Boolean {
        val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return devices.any { dev ->
            when (dev.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLE_SPEAKER -> true
                else -> false
            }
        }
    }

    fun isNotificationAccessGranted(context: Context): Boolean {
        val cn = ComponentName(context, DialogueNotificationListenerService::class.java)
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
        return flat.contains(cn.flattenToString()) || flat.contains(context.packageName)
    }
}

/**
 * Background `NotificationListenerService`:
 *  - Watches selected apps (Gmail, WhatsApp, Messages, etc.)
 *  - Skips ongoing notifications, group summaries, and OTPs
 *  - Enforces a strict 15-second cooldown
 *  - Respects DND, Silent/Vibrate mode, and the "Headphones / Bluetooth Only" toggle
 *  - Matches incoming notification text via `EmbeddingGemma 2 Text (270M)` (default threshold `0.65`)
 *    and plays the winning Malayalam dialogue clip via `MediaPlayer`.
 */
class DialogueNotificationListenerService : NotificationListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastReactionEpochMs = AtomicLong(0L)

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return

        // Skip ongoing or group summary notifications
        val flags = notification.flags
        if ((flags and Notification.FLAG_ONGOING_EVENT) != 0) return
        if ((flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val body = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty().trim()
        val combined = "$title $body".trim()
        if (combined.length < 3) return
        if (AudioOutputGuard.isLikelyOtp(combined)) return

        // Enforce 15-second cooldown before waking up any matcher
        val now = System.currentTimeMillis()
        val prev = lastReactionEpochMs.get()
        if (now - prev < COOLDOWN_MS) return

        serviceScope.launch {
            val graph = AppGraph.from(applicationContext)
            val settings = graph.prefs.snapshot()
            if (pkg !in settings.watchedPackages) return@launch
            if (!AudioOutputGuard.canPlayReactionNow(applicationContext, settings.headphonesOrBluetoothOnly)) {
                return@launch
            }

            val topMatch = graph.embeddingMatcher.rankDialogues(
                rawQuery = combined,
                appPackageHint = pkg,
                topK = 1
            ).firstOrNull() ?: return@launch

            if (topMatch.score >= settings.matchThreshold) {
                lastReactionEpochMs.set(System.currentTimeMillis())
                graph.audioPlayer.playEntry(topMatch.entry)
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val COOLDOWN_MS = 15_000L
    }
}

/**
 * Optional Low-Battery Broadcast Receiver (`Intent.ACTION_BATTERY_LOW`).
 */
class BatteryLowReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BATTERY_LOW) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val graph = AppGraph.from(context)
                val settings = graph.prefs.snapshot()
                if (!settings.lowBatteryReactionEnabled) return@launch
                if (!AudioOutputGuard.canPlayReactionNow(context, settings.headphonesOrBluetoothOnly)) {
                    return@launch
                }
                val match = graph.embeddingMatcher.rankDialogues(
                    rawQuery = "battery low 15% connect charger phone dying low_battery",
                    topK = 1
                ).firstOrNull()
                if (match != null && match.score >= (settings.matchThreshold - 0.08f).coerceAtLeast(0.45f)) {
                    graph.audioPlayer.playEntry(match.entry)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
