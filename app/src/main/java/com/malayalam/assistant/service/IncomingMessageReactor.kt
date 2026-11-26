package com.malayalam.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.malayalam.assistant.AppGraph
import com.malayalam.assistant.ai.EmbeddingMatcherEngine
import com.malayalam.assistant.ai.GeminiBrainClient
import com.malayalam.assistant.audio.TriggerDeduplicator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Unified AI-Powered Incoming Message Reactor for:
 *  - Real-time Bank & Message SMS (`SmsReactionReceiver`)
 *  - Direct SSL IMAP Gmail (`GmailDirectWatcherService`)
 *  - App Notifications (`DialogueNotificationListenerService`)
 *
 * Respects DND, Silent/Vibrate mode, active phone call (`MODE_IN_CALL`), Headphones-Only toggle,
 * smart OTP filtering (exempting bank credit/debit alerts), and 60s deduplication.
 */
class IncomingMessageReactor(private val context: Context) {

    suspend fun reactToIncoming(
        source: String,
        sourceKey: String,
        sender: String,
        messageText: String,
        appPackageHint: String? = null
    ): String? {
        val cleanMsg = messageText.replace(Regex("\\s+"), " ").trim()
        if (cleanMsg.length < 3) return null
        if (AudioOutputGuard.isLikelyOtp(cleanMsg)) return null

        val graph = AppGraph.from(context)
        val settings = graph.prefs.snapshot()
        if (!AudioOutputGuard.canPlayReactionNow(context, settings.headphonesOrBluetoothOnly)) {
            return null
        }

        val apiKey = graph.prefs.getDecryptedGeminiApiKey()
        val vaultEntries = graph.vaultRepository.dialogues.value

        // 1. Online Google AI Studio Brain Path (6-second fast timeout)
        if (apiKey.isNotBlank()) {
            val summaries = GeminiBrainClient.toVaultSummaries(vaultEntries) {
                graph.vaultRepository.hasPlayableAudio(it)
            }
            val aiDecision = graph.geminiBrainClient.analyzeIncomingMessage(
                apiKey = apiKey,
                sender = sender.ifBlank { source },
                source = source,
                messageText = cleanMsg,
                vaultDialogues = summaries
            )

            if (aiDecision != null) {
                if (aiDecision.skipReaction) return null

                val targetDialogue = aiDecision.playDialogueId?.let { id ->
                    vaultEntries.firstOrNull { it.id == id && graph.vaultRepository.hasPlayableAudio(it) }
                }

                val dedupId = targetDialogue?.id ?: "gemini_tts_$source"
                if (!TriggerDeduplicator.shouldAllowAutoTrigger(sourceKey, cleanMsg, dedupId)) {
                    return null
                }

                if (targetDialogue != null) {
                    val played = graph.audioPlayer.playEntry(targetDialogue)
                    if (played) return "Played '${targetDialogue.title}'"
                }

                // No MP3 linked for this situation yet -> speak Gemini's witty Malayalam reaction!
                if (aiDecision.replyMalayalam.isNotBlank()) {
                    graph.audioPlayer.speakNaturalReply(
                        text = aiDecision.replyMalayalam,
                        apiKey = apiKey,
                        ttsClient = graph.geminiTtsClient,
                        voiceName = settings.geminiVoiceName,
                        ttsStyle = aiDecision.ttsStyle,
                        preferMalayalam = settings.isMalayalamMode
                    )
                    return "Spoke: ${aiDecision.replyMalayalam.take(45)}"
                }
                return null
            }
        }

        // 2. Instant Offline Regex / Keyword Fallback (credit / debit / exam / battery)
        val threshold = settings.matchThreshold.coerceAtLeast(EmbeddingMatcherEngine.MIN_SAFE_THRESHOLD)
        val winner = graph.embeddingMatcher.rankDialogues(
            rawQuery = "$sender $cleanMsg",
            appPackageHint = appPackageHint,
            requirePlayableAudio = true,
            topK = 1
        ).firstOrNull() ?: return null

        if (winner.score >= threshold &&
            TriggerDeduplicator.shouldAllowAutoTrigger(sourceKey, cleanMsg, winner.entry.id)
        ) {
            val played = graph.audioPlayer.playEntry(winner.entry)
            if (played) return "Played '${winner.entry.title}'"
        }
        return null
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
