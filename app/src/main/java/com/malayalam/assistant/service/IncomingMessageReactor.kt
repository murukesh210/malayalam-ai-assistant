package com.malayalam.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.Telephony
import com.malayalam.assistant.AppGraph
import com.malayalam.assistant.ai.EmbeddingMatcherEngine
import com.malayalam.assistant.ai.GeminiBrainClient
import com.malayalam.assistant.audio.TriggerDeduplicator
import com.malayalam.assistant.data.DialogueEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

data class ReactionOutcome(
    val actionTaken: String,
    val understoodMeaning: String = "",
    val matchedDialogue: DialogueEntry? = null,
    val replyMalayalam: String = "",
    val latencyMs: Long = 0L,
    val apiStatus: String = "Local Fast-Path"
)

/**
 * Cross-Lingual Two-Tier Zero-Lag Incoming Message Reactor (WhatsApp, SMS, GPay/PhonePe, Gmail):
 *  - Executes directly on `Dispatchers.IO` with a short `PARTIAL_WAKE_LOCK` (max 10s) AND `WifiLock`.
 *  - **Tier 1 (< 200 ms, Zero Network):** ONLY short-circuits on a **100% exact literal phrase match**
 *    from a dialogue's `whenToPlay` (with Unicode-safe strict opposite-polarity guards).
 *  - **Tier 2 (Universal Cross-Lingual AI):** Whenever a Gemini API key is configured and there is no
 *    100% literal match, ALWAYS sends the message to `GeminiBrainClient.analyzeNotificationFast()` so
 *    pure Malayalam (`എക്സാം പൊട്ടി`, `മാച്ച് തോറ്റു`), Manglish (`pareeksha potty`), English, Hindi,
 *    and emojis are understood by real-world meaning — NEVER dropped by a local keyword check!
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
        _liveMonitorLogs.value = (listOf(entry) + _liveMonitorLogs.value).take(8)
    }

    suspend fun reactToIncoming(
        source: String,
        sourceKey: String,
        sender: String,
        messageText: String,
        appPackageHint: String? = null,
        bypassCooldownAndGuards: Boolean = false
    ): String? = reactToIncomingDetailed(
        source = source,
        sourceKey = sourceKey,
        sender = sender,
        messageText = messageText,
        appPackageHint = appPackageHint,
        bypassCooldownAndGuards = bypassCooldownAndGuards
    )?.actionTaken

    suspend fun reactToIncomingDetailed(
        source: String,
        sourceKey: String,
        sender: String,
        messageText: String,
        appPackageHint: String? = null,
        bypassCooldownAndGuards: Boolean = false
    ): ReactionOutcome? = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        // Preserve full UTF-8 Malayalam Unicode (\u0D00-\u0D7F); only collapse whitespace
        val cleanMsg = messageText.replace(Regex("\\s+"), " ").trim()
        if (cleanMsg.length < 2) return@withContext null

        if (!bypassCooldownAndGuards && AudioOutputGuard.isLikelyOtp(cleanMsg)) {
            val msg = "Skipped (OTP / Code)"
            recordMonitorLog(source, sender, cleanMsg, msg)
            return@withContext ReactionOutcome(actionTaken = msg, latencyMs = System.currentTimeMillis() - t0, apiStatus = "Guard")
        }

        val graph = AppGraph.from(context)
        val settings = graph.prefs.snapshot()

        if (!bypassCooldownAndGuards && !AudioOutputGuard.canPlayReactionNow(context, settings.headphonesOrBluetoothOnly)) {
            val msg = "Skipped (DND / Silent / Call / Headphones)"
            recordMonitorLog(source, sender, cleanMsg, msg)
            return@withContext ReactionOutcome(actionTaken = msg, latencyMs = System.currentTimeMillis() - t0, apiStatus = "Guard")
        }

        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = runCatching {
            pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MalayalamAssistant::InstantReaction")
                ?.apply { acquire(10_000L) }
        }.getOrNull()

        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val wifiLock = runCatching {
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wm?.createWifiLock(mode, "MalayalamAssistant::FastWifi")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()

        try {
            val activePack = graph.voicePackRepository.getActivePackMeta()
            val vaultEntries = graph.voicePackRepository.activeDialogues.value
            val playableEntries = vaultEntries.filter { graph.voicePackRepository.hasPlayableAudio(it) }

            // =========================================================================
            // TIER 1: EXACT LITERAL PHRASE FAST-PATH ONLY (< 200 ms, Zero Network)
            // =========================================================================
            val exactHit = findExactLiteralPhraseMatch(
                message = cleanMsg,
                playableEntries = playableEntries
            )
            if (exactHit != null) {
                if (!bypassCooldownAndGuards &&
                    !TriggerDeduplicator.shouldAllowAutoTrigger(sourceKey, cleanMsg, exactHit.id)
                ) {
                    recordMonitorLog(source, sender, cleanMsg, "Skipped (60s Cooldown)")
                    return@withContext null
                }
                val played = graph.audioPlayer.playEntry(exactHit)
                if (played) {
                    val elapsed = System.currentTimeMillis() - t0
                    val note = "⚡ Instant (${elapsed}ms) '${exactHit.title}' (${activePack.packName})"
                    recordMonitorLog(source, sender, cleanMsg, note)
                    return@withContext ReactionOutcome(
                        actionTaken = note,
                        understoodMeaning = "Exact literal rule match: ${exactHit.title}",
                        matchedDialogue = exactHit,
                        latencyMs = elapsed,
                        apiStatus = "Tier-1 Exact Match (0ms network)"
                    )
                }
            }

            // =========================================================================
            // TIER 2: ALWAYS ROUTE TO CROSS-LINGUAL GEMINI BRAIN WHEN KEY IS SET
            // =========================================================================
            val apiKey = graph.prefs.getDecryptedGeminiApiKey()
            if (apiKey.isNotBlank()) {
                val summaries = GeminiBrainClient.toVaultSummaries(playableEntries) { true }
                val aiDecision = graph.geminiBrainClient.analyzeNotificationFast(
                    apiKey = apiKey,
                    sender = sender.ifBlank { source },
                    messageText = cleanMsg,
                    vaultDialogues = summaries,
                    packPersonaInstruction = activePack.personaInstruction,
                    sourceLabel = source
                )
                val apiStatus = graph.geminiBrainClient.lastDebugStatus.value

                if (aiDecision != null) {
                    val elapsed = System.currentTimeMillis() - t0
                    if (!aiDecision.shouldReact) {
                        val note = "Skipped (Spam / Promo)"
                        recordMonitorLog(source, sender, cleanMsg, note)
                        return@withContext ReactionOutcome(
                            actionTaken = note,
                            understoodMeaning = aiDecision.understoodMeaning,
                            latencyMs = elapsed,
                            apiStatus = apiStatus
                        )
                    }

                    val targetId = aiDecision.resolvedDialogueId
                    val targetDialogue = targetId?.let { id ->
                        playableEntries.firstOrNull { it.id == id }
                            ?: vaultEntries.firstOrNull { it.id == id }
                    }

                    val dedupId = targetDialogue?.id ?: "gemini_tts_$source"
                    if (!bypassCooldownAndGuards &&
                        !TriggerDeduplicator.shouldAllowAutoTrigger(sourceKey, cleanMsg, dedupId)
                    ) {
                        recordMonitorLog(source, sender, cleanMsg, "Skipped (60s Cooldown)")
                        return@withContext null
                    }

                    if (targetDialogue != null && graph.voicePackRepository.hasPlayableAudio(targetDialogue)) {
                        val played = graph.audioPlayer.playEntry(targetDialogue)
                        if (played) {
                            val note = "🔊 AI (${elapsed}ms) '${targetDialogue.title}' (${activePack.packName})"
                            recordMonitorLog(source, sender, cleanMsg, note)
                            return@withContext ReactionOutcome(
                                actionTaken = note,
                                understoodMeaning = aiDecision.understoodMeaning,
                                matchedDialogue = targetDialogue,
                                replyMalayalam = aiDecision.replyMalayalam,
                                latencyMs = elapsed,
                                apiStatus = apiStatus
                            )
                        }
                    }

                    if (aiDecision.replyMalayalam.isNotBlank()) {
                        val note = "🗣️ (${elapsed}ms) ${aiDecision.replyMalayalam.take(55)}"
                        recordMonitorLog(source, sender, cleanMsg, note)
                        graph.audioPlayer.speakNaturalReply(
                            text = aiDecision.replyMalayalam,
                            apiKey = apiKey,
                            ttsClient = graph.geminiTtsClient,
                            voiceName = activePack.ttsVoice.ifBlank { settings.geminiVoiceName },
                            ttsStyle = aiDecision.ttsStyle,
                            preferMalayalam = settings.isMalayalamMode
                        )
                        return@withContext ReactionOutcome(
                            actionTaken = note,
                            understoodMeaning = aiDecision.understoodMeaning,
                            matchedDialogue = targetDialogue,
                            replyMalayalam = aiDecision.replyMalayalam,
                            latencyMs = elapsed,
                            apiStatus = apiStatus
                        )
                    }

                    val note = "No reaction needed"
                    recordMonitorLog(source, sender, cleanMsg, note)
                    return@withContext ReactionOutcome(
                        actionTaken = note,
                        understoodMeaning = aiDecision.understoodMeaning,
                        latencyMs = elapsed,
                        apiStatus = apiStatus
                    )
                } else {
                    val elapsed = System.currentTimeMillis() - t0
                    recordMonitorLog(source, sender, cleanMsg, "⚠️ Gemini unreachable ($apiStatus) — trying offline")
                    // Fall through to offline fallback below, preserving apiStatus if offline also misses
                }
            }

            // =========================================================================
            // OFFLINE FALLBACK (Only when offline or no Gemini API key)
            // =========================================================================
            val threshold = settings.matchThreshold.coerceAtLeast(EmbeddingMatcherEngine.MIN_SAFE_THRESHOLD)
            val winner = graph.embeddingMatcher.rankDialogues(
                rawQuery = "$sender $cleanMsg",
                appPackageHint = appPackageHint,
                requirePlayableAudio = true,
                overrideDialogues = playableEntries,
                topK = 1
            ).firstOrNull()

            if (winner != null && winner.score >= threshold) {
                if (bypassCooldownAndGuards ||
                    TriggerDeduplicator.shouldAllowAutoTrigger(sourceKey, cleanMsg, winner.entry.id)
                ) {
                    val played = graph.audioPlayer.playEntry(winner.entry)
                    if (played) {
                        val elapsed = System.currentTimeMillis() - t0
                        val note = "🔊 Offline (${elapsed}ms) '${winner.entry.title}'"
                        recordMonitorLog(source, sender, cleanMsg, note)
                        return@withContext ReactionOutcome(
                            actionTaken = note,
                            understoodMeaning = "Offline local match (${String.format("%.2f", winner.score)})",
                            matchedDialogue = winner.entry,
                            latencyMs = elapsed,
                            apiStatus = if (apiKey.isBlank()) "Offline (No API Key)" else graph.geminiBrainClient.lastDebugStatus.value
                        )
                    }
                }
            }

            val elapsed = System.currentTimeMillis() - t0
            val statusStr = if (apiKey.isBlank()) "Offline (No API Key)" else graph.geminiBrainClient.lastDebugStatus.value
            val missMsg = "No matching clip ($statusStr)"
            recordMonitorLog(source, sender, cleanMsg, missMsg)
            ReactionOutcome(
                actionTaken = missMsg,
                understoodMeaning = "No match found",
                latencyMs = elapsed,
                apiStatus = statusStr
            )
        } finally {
            runCatching {
                if (wifiLock?.isHeld == true) wifiLock.release()
            }
            runCatching {
                if (wakeLock?.isHeld == true) wakeLock.release()
            }
        }
    }

    /**
     * Tier-1 Exact Literal Phrase Fast-Path (< 200 ms):
     * ONLY short-circuits when a `specific` dialogue's user-written `whenToPlay` contains an
     * **exact literal phrase match** (with Unicode-safe strict opposite-polarity protection).
     * Never guesses on partial keywords — leaves all semantic/cross-lingual reasoning to Gemini!
     */
    private fun findExactLiteralPhraseMatch(
        message: String,
        playableEntries: List<DialogueEntry>
    ): DialogueEntry? {
        if (playableEntries.isEmpty()) return null
        val normMsg = normalizeUnicode(message)
        if (normMsg.length < 3) return null

        var bestEntry: DialogueEntry? = null
        var bestPhraseLen = 0

        for (entry in playableEntries) {
            if (entry.scope.equals("broad", ignoreCase = true)) continue

            val candidateBlob = normalizeUnicode(
                "${entry.title} ${entry.situations.joinToString(" ")} ${entry.category}"
            )
            if (hasOppositePolarityUnicode(normMsg, candidateBlob)) continue

            for (rawSit in entry.situations) {
                val sit = normalizeUnicode(rawSit)
                // Must be a meaningful literal phrase (>= 4 chars) and present verbatim in the incoming message
                if (sit.length >= 4 && normMsg.contains(sit)) {
                    if (sit.length > bestPhraseLen) {
                        bestPhraseLen = sit.length
                        bestEntry = entry
                    }
                }
            }
        }
        return bestEntry
    }

    /**
     * Unicode-safe opposite-polarity check (works cleanly with pure Malayalam `\u0D00-\u0D7F` where
     * ASCII `\b` word boundaries fail around Malayalam viramas/vowel signs).
     */
    private fun hasOppositePolarityUnicode(msg: String, cand: String): Boolean {
        for ((posTerms, negTerms) in POLARITY_TERM_PAIRS) {
            val msgPos = posTerms.any { msg.contains(it) }
            val msgNeg = negTerms.any { msg.contains(it) }
            val candPos = posTerms.any { cand.contains(it) }
            val candNeg = negTerms.any { cand.contains(it) }
            if ((msgPos && !msgNeg && candNeg && !candPos) || (msgNeg && !msgPos && candPos && !candNeg)) {
                return true
            }
        }
        return false
    }

    private fun normalizeUnicode(raw: String): String = raw
        .lowercase(Locale.ROOT)
        // Keep ALL Unicode letters, combining marks (Malayalam matras/viramas!), numbers, and spaces
        .replace(Regex("[^\\p{L}\\p{M}\\p{N}\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    companion object {
        private val POLARITY_TERM_PAIRS = listOf(
            listOf("pass", "passed", "victory", "winner", "topper", "ജയിച്ചു", "പാസ്", "പാസ്സായി") to
                listOf("fail", "failed", "supply", "arrear", "പൊട്ടി", "potti", "potty", "തോറ്റു", "thottu", "ഫെയിൽ", "മൂഞ്ചി"),
            listOf("credit", "credited", "received", "salary", "paisa ayach", "പൈസ അയച്ച", "പൈസ വന്നു", "കിട്ടി", "ക്രെഡിറ്റ്") to
                listOf("debit", "debited", "spent", "deducted", "withdrawn", "പൈസ പോയി", "ഡെബിറ്റ്"),
            listOf("happy", "congrats", "celebrate", "സന്തോഷം") to
                listOf("sad", "crying", "sorry", "heartbreak", "സങ്കടം")
        )
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
