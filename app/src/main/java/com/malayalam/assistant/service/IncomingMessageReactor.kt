package com.malayalam.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Telephony
import com.malayalam.assistant.AppGraph
import com.malayalam.assistant.ai.EmbeddingMatcherEngine
import com.malayalam.assistant.ai.GeminiBrainClient
import com.malayalam.assistant.audio.TriggerDeduplicator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LiveNotificationLogEntry(
    val id: Long = System.nanoTime(),
    val timeLabel: String,
    val sourceApp: String,
    val sender: String,
    val messagePreview: String,
    val actionTaken: String
)

/**
 * Unified AI-Powered Incoming Message Reactor for:
 *  - WhatsApp & App Notifications (`DialogueNotificationListenerService`)
 *  - Real-time Bank & Message SMS (`SmsReactionReceiver`)
 *  - Direct SSL IMAP Gmail (`GmailDirectWatcherService`)
 *
 * Acquires a brief `PARTIAL_WAKE_LOCK` (max 15s) so reactions fire even when the screen is locked,
 * records the last 5 intercepted messages into `liveMonitorLogs` for Settings diagnostics,
 * and speaks a short witty Malayalam voice reaction via streaming Gemini TTS when no MP3 matches!
 */
class IncomingMessageReactor(private val context: Context) {

    private val _liveMonitorLogs = MutableStateFlow<List<LiveNotificationLogEntry>>(emptyList())
    val liveMonitorLogs: StateFlow<List<LiveNotificationLogEntry>> = _liveMonitorLogs.asStateFlow()

    private fun recordMonitorLog(
        sourceApp: String,
        sender: String,
        message: String,
        actionTaken: String
    ) {
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = LiveNotificationLogEntry(
            timeLabel = timeStr,
            sourceApp = sourceApp,
            sender = sender.ifBlank { sourceApp },
            messagePreview = message.take(80),
            actionTaken = actionTaken
        )
        _liveMonitorLogs.value = (listOf(entry) + _liveMonitorLogs.value).take(5)
    }

    suspend fun reactToIncoming(
        source: String,
        sourceKey: String,
        sender: String,
        messageText: String,
        appPackageHint: String? = null,
        bypassCooldownAndGuards: Boolean = false
    ): String? {
        val cleanMsg = messageText.replace(Regex("\\s+"), " ").trim()
        if (cleanMsg.length < 2) return null

        if (!bypassCooldownAndGuards && AudioOutputGuard.isLikelyOtp(cleanMsg)) {
            recordMonitorLog(source, sender, cleanMsg, "Skipped (OTP / Code)")
            return null
        }

        val graph = AppGraph.from(context)
        val settings = graph.prefs.snapshot()

        if (!bypassCooldownAndGuards && !AudioOutputGuard.canPlayReactionNow(context, settings.headphonesOrBluetoothOnly)) {
            recordMonitorLog(source, sender, cleanMsg, "Skipped (DND / Silent / Call / Headphones)")
            return null
        }

        // Brief PARTIAL_WAKE_LOCK (max 15s) so reactions fire cleanly when screen is locked!
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = runCatching {
            pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MalayalamAssistant::IncomingReaction")
                ?.apply { acquire(15_000L) }
        }.getOrNull()

        try {
            val apiKey = graph.prefs.getDecryptedGeminiApiKey()
            val activePack = graph.voicePackRepository.getActivePackMeta()
            val vaultEntries = graph.voicePackRepository.activeDialogues.value

            // 1. Primary: Google AI Studio Gemini Brain (Specificity-First + Active Pack Persona!)
            if (apiKey.isNotBlank()) {
                val summaries = GeminiBrainClient.toVaultSummaries(vaultEntries) {
                    graph.voicePackRepository.hasPlayableAudio(it)
                }
                val aiDecision = graph.geminiBrainClient.analyzeIncomingMessage(
                    apiKey = apiKey,
                    sender = sender.ifBlank { source },
                    source = source,
                    messageText = cleanMsg,
                    vaultDialogues = summaries,
                    packPersonaInstruction = activePack.personaInstruction
                )

                if (aiDecision != null) {
                    if (!aiDecision.shouldReact) {
                        recordMonitorLog(source, sender, cleanMsg, "Skipped (Spam / Promo)")
                        return null
                    }

                    val targetDialogue = aiDecision.playDialogueId?.let { id ->
                        vaultEntries.firstOrNull { it.id == id && graph.voicePackRepository.hasPlayableAudio(it) }
                    }

                    val dedupId = targetDialogue?.id ?: "gemini_tts_$source"
                    if (!bypassCooldownAndGuards &&
                        !TriggerDeduplicator.shouldAllowAutoTrigger(sourceKey, cleanMsg, dedupId)
                    ) {
                        recordMonitorLog(source, sender, cleanMsg, "Skipped (60s Cooldown)")
                        return null
                    }

                    if (targetDialogue != null) {
                        val played = graph.audioPlayer.playEntry(targetDialogue)
                        if (played) {
                            val note = "🔊 Played '${targetDialogue.title}' (${activePack.packName})"
                            recordMonitorLog(source, sender, cleanMsg, note)
                            return note
                        }
                    }

                    if (aiDecision.replyMalayalam.isNotBlank()) {
                        val note = "🗣️ ${aiDecision.replyMalayalam.take(55)}"
                        recordMonitorLog(source, sender, cleanMsg, note)
                        graph.audioPlayer.speakNaturalReply(
                            text = aiDecision.replyMalayalam,
                            apiKey = apiKey,
                            ttsClient = graph.geminiTtsClient,
                            voiceName = activePack.ttsVoice.ifBlank { settings.geminiVoiceName },
                            ttsStyle = aiDecision.ttsStyle,
                            preferMalayalam = settings.isMalayalamMode
                        )
                        return note
                    }
                    recordMonitorLog(source, sender, cleanMsg, "No reaction needed")
                    return null
                }
            }

            // 2. Instant Offline Polarity-Safe & Specificity-First Fallback
            val threshold = settings.matchThreshold.coerceAtLeast(EmbeddingMatcherEngine.MIN_SAFE_THRESHOLD)
            val winner = graph.embeddingMatcher.rankDialogues(
                rawQuery = "$sender $cleanMsg",
                appPackageHint = appPackageHint,
                requirePlayableAudio = true,
                overrideDialogues = vaultEntries,
                topK = 1
            ).firstOrNull()

            if (winner != null && winner.score >= threshold) {
                if (bypassCooldownAndGuards ||
                    TriggerDeduplicator.shouldAllowAutoTrigger(sourceKey, cleanMsg, winner.entry.id)
                ) {
                    val played = graph.audioPlayer.playEntry(winner.entry)
                    if (played) {
                        val note = "🔊 Played '${winner.entry.title}' (${String.format("%.2f", winner.score)})"
                        recordMonitorLog(source, sender, cleanMsg, note)
                        return note
                    }
                }
            }

            recordMonitorLog(source, sender, cleanMsg, "No matching clip (offline)")
            return null
        } finally {
            runCatching {
                if (wakeLock?.isHeld == true) wakeLock.release()
            }
        }
    }
}

/**
 * Real-Time SMS Receiver (`android.provider.Telephony.SMS_RECEIVED`) for automatic bank
 * credit/debit and personal SMS Malayalam dialogue reactions.
 */
class SmsReactionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val sender = messages.firstOrNull()?.displayOriginatingAddress.orEmpty().trim()
        val combinedBody = messages.joinToString("") { it.messageBody.orEmpty() }.trim()
        if (combinedBody.isBlank()) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val graph = AppGraph.from(context)
                val settings = graph.prefs.snapshot()
                if (!settings.smsAutoReactEnabled) return@launch

                graph.incomingMessageReactor.reactToIncoming(
                    source = "SMS",
                    sourceKey = "sms_$sender",
                    sender = sender,
                    messageText = combinedBody,
                    appPackageHint = "com.google.android.apps.messaging"
                )
            } finally {
                pendingResult.finish()
            }
        }
    }
}
