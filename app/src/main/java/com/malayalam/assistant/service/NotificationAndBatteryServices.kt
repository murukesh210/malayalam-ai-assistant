package com.malayalam.assistant.service

import android.app.Notification
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.malayalam.assistant.AppGraph
import com.malayalam.assistant.ai.EmbeddingMatcherEngine
import com.malayalam.assistant.audio.TriggerDeduplicator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

object AudioOutputGuard {

    private val OTP_REGEX = Regex("\\b(otp|verification code|cvv|one[- ]?time password|security code|passcode|auth code)\\b", RegexOption.IGNORE_CASE)
    private val BANK_TXN_REGEX = Regex("\\b(credited|debited|received rs|paid rs|withdrawn|deposited|upi credit|upi debit|spent|salary credited|paisa ayach|sent you)\\b", RegexOption.IGNORE_CASE)

    fun isLikelyOtp(text: String): Boolean {
        if (BANK_TXN_REGEX.containsMatchIn(text)) return false
        return OTP_REGEX.containsMatchIn(text)
    }

    fun canPlayReactionNow(context: Context, headphonesOnly: Boolean): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (nm != null &&
            nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL &&
            nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        ) {
            return false
        }

        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false

        if (am.mode == AudioManager.MODE_IN_CALL ||
            am.mode == AudioManager.MODE_IN_COMMUNICATION ||
            am.mode == AudioManager.MODE_RINGTONE
        ) {
            return false
        }

        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) {
            return false
        }

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
 * Background `NotificationListenerService` for WhatsApp, Messages, Gmail, GPay, PhonePe, Paytm,
 * Telegram, and Instagram:
 *  - Properly extracts latest message + sender from `NotificationCompat.MessagingStyle` (`EXTRA_MESSAGES`),
 *    falling back cleanly to `EXTRA_BIG_TEXT` and `EXTRA_TEXT`.
 *  - Ignores `FLAG_GROUP_SUMMARY` duplicates and ongoing notifications.
 */
class DialogueNotificationListenerService : NotificationListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return

        val flags = notification.flags
        if ((flags and Notification.FLAG_ONGOING_EVENT) != 0) return
        if ((flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val (sender, body) = extractMessagingOrStandardText(notification)
        val cleanBody = body.trim()
        if (cleanBody.length < 2) return

        val notifKey = sbn.key ?: pkg
        val appLabel = friendlyAppLabel(pkg)

        serviceScope.launch {
            val graph = AppGraph.from(applicationContext)
            val settings = graph.prefs.snapshot()
            if (pkg == "com.google.android.gm" && settings.gmailWatcherEnabled) return@launch
            if (pkg in SMS_APP_PACKAGES && settings.smsAutoReactEnabled) return@launch
            if (pkg !in settings.watchedPackages) return@launch

            graph.incomingMessageReactor.reactToIncoming(
                source = appLabel,
                sourceKey = notifKey,
                sender = sender.ifBlank { appLabel },
                messageText = cleanBody,
                appPackageHint = pkg
            )
        }
    }

    private fun extractMessagingOrStandardText(notification: Notification): Pair<String, String> {
        // 1. Primary: NotificationCompat.MessagingStyle (WhatsApp, Messages, Telegram, etc.)
        runCatching {
            val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
            val latest = style?.messages?.lastOrNull()
            if (latest != null && !latest.text.isNullOrBlank()) {
                val senderName = latest.person?.name?.toString()?.trim().orEmpty()
                return senderName to latest.text.toString().trim()
            }
        }

        // 2. Fallback: raw EXTRA_MESSAGES Parcelable[] bundle (some OEM WhatsApp builds)
        val extras = notification.extras ?: return "" to ""
        runCatching {
            @Suppress("DEPRECATION")
            val rawMsgs = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            val lastBundle = rawMsgs?.lastOrNull() as? Bundle
            if (lastBundle != null) {
                val txt = lastBundle.getCharSequence("text")?.toString()?.trim().orEmpty()
                val snd = lastBundle.getCharSequence("sender")?.toString()?.trim().orEmpty()
                if (txt.isNotBlank()) return snd to txt
            }
        }

        // 3. Standard EXTRA_TITLE + EXTRA_BIG_TEXT / EXTRA_TEXT
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty().trim()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty().trim()
        val resolvedBody = bigText.ifBlank { text }.ifBlank { title }
        return title to resolvedBody
    }

    private fun friendlyAppLabel(pkg: String): String = when (pkg) {
        "com.whatsapp", "com.whatsapp.w4b" -> "WhatsApp"
        "com.google.android.gm" -> "Gmail"
        "com.google.android.apps.messaging", "com.android.mms", "com.samsung.android.messaging" -> "Messages"
        "org.telegram.messenger" -> "Telegram"
        "com.instagram.android" -> "Instagram"
        "com.google.android.apps.nbu.paisa.user" -> "GPay"
        "com.phonepe.app" -> "PhonePe"
        "net.one97.paytm" -> "Paytm"
        else -> pkg.substringAfterLast('.')
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private val SMS_APP_PACKAGES = setOf(
            "com.google.android.apps.messaging",
            "com.android.mms",
            "com.samsung.android.messaging"
        )
    }
}

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
                    requirePlayableAudio = true,
                    topK = 1
                ).firstOrNull() ?: return@launch

                val threshold = settings.matchThreshold.coerceAtLeast(EmbeddingMatcherEngine.MIN_SAFE_THRESHOLD)
                if (match.score >= threshold &&
                    TriggerDeduplicator.shouldAllowAutoTrigger(
                        sourceKey = "system_battery_low",
                        messageBody = "battery_low_broadcast",
                        dialogueId = match.entry.id
                    )
                ) {
                    graph.audioPlayer.playEntry(match.entry)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
