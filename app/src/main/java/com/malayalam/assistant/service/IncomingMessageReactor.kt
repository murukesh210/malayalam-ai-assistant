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

/**
 * Two-Tier Zero-Lag Incoming Message Reactor for WhatsApp, SMS, GPay/PhonePe, and Gmail:
 *  - Executes directly on `Dispatchers.IO` (NEVER WorkManager) with a short `PARTIAL_WAKE_LOCK` (max 10s)
 *    AND `WifiManager.WifiLock` (`WIFI_MODE_FULL_LOW_LATENCY` / `WIFI_MODE_FULL_HIGH_PERF`).
 *  - **Tier 1 (< 200 ms, Zero Network):** Local specificity-first & polarity-safe phrase/keyword matcher.
 *    If a `specific` dialogue matches with high confidence, plays the local MP3 IMMEDIATELY with zero cloud wait!
 *  - **Tier 2 (~0.8s–1.2s):** Slim `GeminiBrainClient.analyzeNotificationFast()` (zero chat history,
 *    minimal thinking, 90 max tokens, 3s connect / 5s read timeout) for subtle/indirect sentences or TTS replies.
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
    ): String? = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val cleanMsg = messageText.replace(Regex("\\s+"), " ").trim()
        if (cleanMsg.length < 2) return@withContext null

        if (!bypassCooldownAndGuards && AudioOutputGuard.isLikelyOtp(cleanMsg)) {
            recordMonitorLog(source, sender, cleanMsg, "Skipped (OTP / Code)")
            return@withContext null
        }

        val graph = AppGraph.from(context)
        val settings = graph.prefs.snapshot()

        if (!bypassCooldownAndGuards && !AudioOutputGuard.canPlayReactionNow(context, settings.headphonesOrBluetoothOnly)) {
            recordMonitorLog(source, sender, cleanMsg, "Skipped (DND / Silent / Call / Headphones)")
            return@withContext null
        }

        // Acquire short PARTIAL_WAKE_LOCK (max 10s) + Low-Latency WifiLock so reactions fire with zero Doze lag
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
            // TIER 1: INSTANT LOCAL FAST-PATH (< 200 ms, ZERO NETWORK)
            // =========================================================================
            val instantHit = findConfidentLocalFastMatch(
                message = cleanMsg,
                playableEntries = playableEntries
            )
            if (instantHit != null) {
                if (!bypassCooldownAndGuards &&
                    !TriggerDeduplicator.shouldAllowAutoTrigger(sourceKey, cleanMsg, instantHit.id)
                ) {
                    recordMonitorLog(source, sender, cleanMsg, "Skipped (60s Cooldown)")
                    return@withContext null
                }
                val played = graph.audioPlayer.playEntry(instantHit)
                if (played) {
                    val elapsed = System.currentTimeMillis() - t0
                    val note = "⚡ Instant (${elapsed}ms) '${instantHit.title}' (${activePack.packName})"
                    recordMonitorLog(source, sender, cleanMsg, note)
                    return@withContext note
                }
            }

            // =========================================================================
            // TIER 2: ULTRA-FAST SLIM CLOUD SEMANTIC MATCHER (~0.8s - 1.2s)
            // =========================================================================
            val apiKey = graph.prefs.getDecryptedGeminiApiKey()
            if (apiKey.isNotBlank()) {
                val summaries = GeminiBrainClient.toVaultSummaries(playableEntries) { true }
                val aiDecision = graph.geminiBrainClient.analyzeNotificationFast(
                    apiKey = apiKey,
                    sender = sender.ifBlank { source },
                    messageText = cleanMsg,
                    vaultDialogues = summaries,
                    packPersonaInstruction = activePack.personaInstruction
                )

                if (aiDecision != null) {
                    if (!aiDecision.shouldReact) {
                        recordMonitorLog(source, sender, cleanMsg, "Skipped (Spam / Promo)")
                        return@withContext null
                    }

                    val targetDialogue = aiDecision.playDialogueId?.let { id ->
                        playableEntries.firstOrNull { it.id == id }
                    }

                    val dedupId = targetDialogue?.id ?: "gemini_tts_$source"
                    if (!bypassCooldownAndGuards &&
                        !TriggerDeduplicator.shouldAllowAutoTrigger(sourceKey, cleanMsg, dedupId)
                    ) {
                        recordMonitorLog(source, sender, cleanMsg, "Skipped (60s Cooldown)")
                        return@withContext null
                    }

                    // If playDialogueId matched, play MP3 immediately without waiting on TTS
                    if (targetDialogue != null) {
                        val played = graph.audioPlayer.playEntry(targetDialogue)
                        if (played) {
                            val elapsed = System.currentTimeMillis() - t0
                            val note = "🔊 AI (${elapsed}ms) '${targetDialogue.title}' (${activePack.packName})"
                            recordMonitorLog(source, sender, cleanMsg, note)
                            return@withContext note
                        }
                    }

                    // Otherwise stream short Malayalam reply via gemini-3.8-flash-lite-tts
                    if (aiDecision.replyMalayalam.isNotBlank()) {
                        val elapsed = System.currentTimeMillis() - t0
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
                        return@withContext note
                    }
                    recordMonitorLog(source, sender, cleanMsg, "No reaction needed")
                    return@withContext null
                }
            }

            // =========================================================================
            // OFFLINE FALLBACK (When offline or no API key configured)
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
                        return@withContext note
                    }
                }
            }

            recordMonitorLog(source, sender, cleanMsg, "No matching clip")
            null
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
     * Tier-1 Instant Local Fast-Path (< 200 ms, Zero Network):
     * Scores incoming text against every playable `specific` dialogue in the Active Voice Pack using
     * normalized lowercase phrase + substring + Manglish/Malayalam/English token matching with strict
     * opposite-polarity guards (`pass` vs `fail`, `credit` vs `debit`, `happy` vs `sad`).
     */
    private fun findConfidentLocalFastMatch(
        message: String,
        playableEntries: List<DialogueEntry>
    ): DialogueEntry? {
        if (playableEntries.isEmpty()) return null
        val normMsg = normalizeText(message)
        if (normMsg.length < 2) return null
        val msgTokens = tokenize(normMsg)

        var bestEntry: DialogueEntry? = null
        var bestScore = 0

        // Tier 1 only fast-paths `specific` dialogues (leaving `broad` mood / nuanced lines to Tier 2 AI)
        for (entry in playableEntries) {
            if (entry.scope.equals("broad", ignoreCase = true)) continue

            val candidateBlob = buildString {
                append(entry.title).append(' ')
                append(entry.situations.joinToString(" ")).append(' ')
                append(entry.category)
            }
            val normCand = normalizeText(candidateBlob)
            if (hasOppositePolarity(normMsg, normCand)) continue

            var score = 0
            var directPhraseHit = false
            val matchedKeywords = mutableSetOf<String>()

            // 1. Check each situation phrase directly (e.g., "paisa ayachittund", "exam pass", "salary credited")
            for (rawSit in entry.situations) {
                val sit = normalizeText(rawSit)
                if (sit.length < 3) continue
                if (normMsg.contains(sit)) {
                    // Exact phrase match inside the incoming notification!
                    directPhraseHit = true
                    score += if (sit.contains(' ')) 100 else 75
                } else {
                    val sitTokens = tokenize(sit)
                    val overlap = sitTokens.filter { tok ->
                        tok.length >= 4 && (tok in msgTokens || msgTokens.any { it.startsWith(tok) || tok.startsWith(it) })
                    }
                    if (sitTokens.isNotEmpty() && overlap.size == sitTokens.size && sitTokens.size >= 2) {
                        directPhraseHit = true
                        score += 90
                    } else {
                        matchedKeywords.addAll(overlap)
                    }
                }
            }

            // 2. Check high-signal domain synonym bundles (bank credit/debit, exam pass/fail, Manglish money)
            for ((msgPattern, candPattern) in HIGH_SIGNAL_EVENT_PAIRS) {
                if (msgPattern.containsMatchIn(normMsg) && candPattern.containsMatchIn(normCand)) {
                    directPhraseHit = true
                    score += 85
                }
            }

            // 3. Multi-keyword confidence (2+ distinct trigger keywords)
            score += matchedKeywords.size * 30

            val isConfident = directPhraseHit || matchedKeywords.size >= 2
            if (isConfident && score > bestScore) {
                bestScore = score
                bestEntry = entry
            }
        }

        return if (bestScore >= 60) bestEntry else null
    }

    private fun hasOppositePolarity(msg: String, cand: String): Boolean {
        for ((groupA, groupB) in OPPOSITE_POLARITY_PAIRS) {
            val msgA = groupA.containsMatchIn(msg)
            val msgB = groupB.containsMatchIn(msg)
            val candA = groupA.containsMatchIn(cand)
            val candB = groupB.containsMatchIn(cand)
            if ((msgA && !msgB && candB && !candA) || (msgB && !msgA && candA && !candB)) {
                return true
            }
        }
        return false
    }

    private fun normalizeText(raw: String): String = raw
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun tokenize(norm: String): Set<String> = norm
        .split(' ')
        .filter { it.length >= 3 && it !in STOP_WORDS }
        .toSet()

    companion object {
        private val STOP_WORDS = setOf(
            "the", "and", "for", "you", "your", "are", "was", "has", "have", "from",
            "with", "this", "that", "just", "now", "hai", "hello", "bro", "eda", "machan"
        )

        private val OPPOSITE_POLARITY_PAIRS = listOf(
            Regex("\\b(pass|passed|passing|victory|won|winner|selected|topper|ജയിച്ചു|പാസ്)\\b") to
                Regex("\\b(fail|failed|failing|lost|rejected|supply|arrear|തോറ്റു|ഫെയിൽ)\\b"),
            Regex("\\b(credit|credited|received|deposited|salary|paisa ayach|sent you|കിട്ടി|ക്രെഡിറ്റ്)\\b") to
                Regex("\\b(debit|debited|spent|deducted|withdrawn|paid|bill due|പോയി|ഡെബിറ്റ്)\\b"),
            Regex("\\b(happy|congrats|congratulations|celebrate|party|awesome|സന്തോഷം)\\b") to
                Regex("\\b(sad|cry|crying|sorry|rip|depressed|heartbreak|സങ്കടം)\\b")
        )

        private val HIGH_SIGNAL_EVENT_PAIRS = listOf(
            Regex("\\b(credited|received rs|salary credited|paisa ayach|upi credit|sent you rs|sent rs)\\b") to
                Regex("\\b(credit|credited|money received|salary|paisa|gpay|upi credit|rich|kodeeshwaran)\\b"),
            Regex("\\b(debited|withdrawn|spent rs|deducted|upi debit|paid rs)\\b") to
                Regex("\\b(debit|debited|money gone|spent|broke|paisa poyi|no money)\\b"),
            Regex("\\b(exam fail|failed in|supply|arrear|got failed|തോറ്റു|ഫെയിൽ)\\b") to
                Regex("\\b(exam fail|failed|fail|supply|arrear|തോറ്റു)\\b"),
            Regex("\\b(exam pass|passed the exam|passed with|distinction|first class|പാസ്|ജയിച്ചു)\\b") to
                Regex("\\b(exam pass|passed|victory|topper|ജയിച്ചു|പാസ്)\\b")
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
