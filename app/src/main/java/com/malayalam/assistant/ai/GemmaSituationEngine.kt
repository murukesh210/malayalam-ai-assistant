package com.malayalam.assistant.ai

import android.content.Context
import com.malayalam.assistant.data.AppPreferences
import com.malayalam.assistant.data.GemmaClassificationJson
import com.malayalam.assistant.data.SituationCategory
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicInteger

/**
 * Situation Classifier & Malayalam-First Light Conversational Responder:
 *  - On 2 GB RAM devices (`ultraLight2GbMode`), stays in 0-RAM smart conversational mode by default
 *    (never repeating robotic `"Offline Lightweight Mode active..."` banners!).
 *  - On 4 GB+ RAM devices, uses `Gemma 3 1B` (`dynamic_int4`) when downloaded.
 */
class GemmaSituationEngine(
    private val context: Context,
    private val coordinator: ModelCoordinator,
    private val downloader: ModelDownloadManager,
    private val prefs: AppPreferences
) {
    private val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val replyRotationIndex = AtomicInteger(0)

    private suspend fun selectActiveGemmaModel(): ManagedModelId? {
        val settings = prefs.snapshot()
        val totalRamMb = AppPreferences.getTotalRamMb(context)
        if (
            !settings.lowRamSafeMode &&
            !settings.ultraLight2GbMode &&
            settings.enableGemma4E2bOptional &&
            totalRamMb >= 7000L &&
            downloader.isModelReady(ManagedModelId.GEMMA_4_E2B_OPTIONAL)
        ) {
            return ManagedModelId.GEMMA_4_E2B_OPTIONAL
        }
        if (!settings.ultraLight2GbMode && downloader.isModelReady(ManagedModelId.GEMMA_3_1B)) {
            return ManagedModelId.GEMMA_3_1B
        }
        return null
    }

    suspend fun classifyHeardDialogue(
        heardMalayalam: String,
        fileName: String
    ): GemmaClassificationJson {
        val modelId = selectActiveGemmaModel()
        if (modelId != null) {
            val modelFile = downloader.primaryModelFile(modelId)
            val prompt = buildString {
                appendLine("Malayalam audio transcript: \"${heardMalayalam.ifBlank { fileName.substringBeforeLast('.') }}\"")
                appendLine("Audio file name: \"$fileName\"")
                appendLine("Return ONLY valid JSON with keys: title, heardText, category, situations, recommendedApps.")
                appendLine("Allowed category values: exam_fail | money | alarm | low_battery | romance | scolding | greeting | celebration | warning | custom")
                appendLine("Include 4 to 7 short English + Manglish trigger phrases in situations.")
            }

            val rawJson = runCatching {
                coordinator.withModel(
                    modelId = modelId,
                    factory = { LiteRtLlmSession(context, modelFile).also { it.initialize() } }
                ) { session ->
                    session.generateText(
                        systemInstruction = CLASSIFIER_SYSTEM_INSTRUCTION,
                        userPrompt = prompt,
                        temperature = 0.15,
                        maxOutputTokens = 160
                    )
                }
            }.getOrNull()

            if (!rawJson.isNullOrBlank()) {
                parseClassificationJson(rawJson, heardMalayalam, fileName)?.let { return it }
            }
        }

        return heuristicClassify(heardMalayalam, fileName)
    }

    /**
     * Natural Malayalam-first (`ML`) or English (`EN`) response with ZERO repetitive canned banners.
     */
    suspend fun generateAssistantReply(
        userMessage: String,
        screenContext: String? = null,
        matchedDialogueTitle: String? = null
    ): String {
        val settings = prefs.snapshot()
        val ml = settings.isMalayalamMode

        // If a movie dialogue reaction already matched & played, return a crisp natural caption!
        if (!matchedDialogueTitle.isNullOrBlank()) {
            return if (ml) "🎬 $matchedDialogueTitle" else "🎬 Playing: $matchedDialogueTitle"
        }

        val modelId = selectActiveGemmaModel()
        if (modelId != null) {
            val modelFile = downloader.primaryModelFile(modelId)
            val fullPrompt = if (!screenContext.isNullOrBlank()) {
                "Visible screen text:\n${screenContext.take(1200)}\n\nUser request: $userMessage"
            } else {
                userMessage
            }
            val sysPrompt = if (ml) CHAT_SYSTEM_MALAYALAM else CHAT_SYSTEM_ENGLISH
            val reply = runCatching {
                coordinator.withModel(
                    modelId = modelId,
                    factory = { LiteRtLlmSession(context, modelFile).also { it.initialize() } }
                ) { session ->
                    session.generateText(
                        systemInstruction = sysPrompt,
                        userPrompt = fullPrompt,
                        temperature = 0.35,
                        maxOutputTokens = 160
                    )
                }
            }.getOrNull()

            if (!reply.isNullOrBlank()) return reply
        }

        if (!screenContext.isNullOrBlank()) {
            val snippet = screenContext.take(140)
            return if (ml) {
                "സ്ക്രീനിൽ കാണുന്നത്: \"$snippet\""
            } else {
                "On screen: \"$snippet\""
            }
        }

        return craftZeroRamNaturalReply(userMessage, ml)
    }

    private fun craftZeroRamNaturalReply(input: String, ml: Boolean): String {
        val lower = input.lowercase().trim()
        val rot = replyRotationIndex.getAndIncrement()

        return when {
            listOf("നമസ്കാരം", "ഹായ്", "ഹലോ", "hi", "hello", "hey", "namaskaram", "eda", "machane")
                .any { lower == it || lower.startsWith("$it ") } -> {
                val mlGreetings = listOf(
                    "നമസ്കാരം! എന്ത് സഹായമാണ് വേണ്ടത്?",
                    "ഹായ്! അലാറം വെക്കാനോ ആപ്പ് തുറക്കാനോ പറയൂ.",
                    "നമസ്കാരം! ഞാൻ കേൾക്കുന്നുണ്ട്, പറഞ്ഞോളൂ."
                )
                val enGreetings = listOf(
                    "Namaskaram! How can I help you?",
                    "Hey! Ask me to set an alarm, call someone, or open an app.",
                    "Hello! Tap the orb anytime to speak."
                )
                if (ml) mlGreetings[rot % mlGreetings.size] else enGreetings[rot % enGreetings.size]
            }

            listOf("സുഖമാണോ", "സുഖം", "sugalle", "sukhamano", "how are you", "nthokke und", "enthokke")
                .any { lower.contains(it) } -> {
                if (ml) "നല്ല സുഖം! നിങ്ങൾക്കോ? എന്ത് ചെയ്യണം എന്ന് പറയൂ." else "Doing great and running super light! What can I do for you?"
            }

            listOf("നന്ദി", "താങ്ക്സ്", "thanks", "thank you", "nanni")
                .any { lower.contains(it) } -> {
                if (ml) "സന്തോഷം! എപ്പോൾ വേണമെങ്കിലും വിളിക്കൂ." else "Happy to help anytime!"
            }

            listOf("ആരാണ്", "പേരെന്താ", "who are you", "your name", "entha cheyya", "help", "സഹായം")
                .any { lower.contains(it) } -> {
                if (ml) {
                    "ഞാൻ നിങ്ങളുടെ മലയാളം വോയ്‌സ് അസിസ്റ്റന്റാണ്. '6:30-ന് അലാം വെക്ക്', 'വാട്സ്ആപ്പ് തുറക്ക്', 'അമ്മയെ വിളിക്ക്' എന്നെല്ലാം പറയാം!"
                } else {
                    "I'm your Malayalam Voice Assistant. Try saying 'Set alarm for 6:30 AM', 'Open WhatsApp', 'Call Amma', or 'Battery status'."
                }
            }

            else -> {
                val mlFallbacks = listOf(
                    "മനസ്സിലായില്ല — 'അലാം വെക്ക്', 'വാട്സ്ആപ്പ് തുറക്ക്', 'സമയം എത്രയായി' എന്നിങ്ങനെ പരീക്ഷിച്ചു നോക്കൂ.",
                    "അതിന് ചേരുന്ന ഡയലോഗോ കമാൻഡോ കണ്ടെത്തിയില്ല. മെനുവിൽ (☰) പുതിയ മലയാളം ഡയലോഗ് ചേർക്കാം!",
                    "എന്ത് ചെയ്യണം എന്ന് ഒന്ന് കൂടി വ്യക്തമാക്കാമോ? (ഉദാ: 'രാവിലെ 6 മണിക്ക് അലാം', 'കോൾ അമ്മ')"
                )
                val enFallbacks = listOf(
                    "Try a command like 'Set alarm for 6:30 AM', 'Open WhatsApp', 'Call Amma', or add a dialogue clip in ☰ Vault.",
                    "No matching command or movie clip found for that yet. You can link MP3 reactions in ☰ Dialogue Vault!",
                    "Could you rephrase that? I handle alarms, calls, opening apps, reminders, battery/time, and Malayalam dialogue reactions."
                )
                if (ml) mlFallbacks[rot % mlFallbacks.size] else enFallbacks[rot % enFallbacks.size]
            }
        }
    }

    private fun parseClassificationJson(
        raw: String,
        fallbackTranscript: String,
        fileName: String
    ): GemmaClassificationJson? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val jsonSlice = raw.substring(start, end + 1)
        return runCatching {
            val decoded = lenientJson.decodeFromString<GemmaClassificationJson>(jsonSlice)
            val validCat = SituationCategory.fromId(decoded.category).id
            val normalizedSituations = decoded.situations
                .flatMap { it.split(",") }
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
            decoded.copy(
                title = decoded.title.ifBlank { cleanTitleFromFileName(fileName) },
                heardText = decoded.heardText.ifBlank { fallbackTranscript },
                category = validCat,
                situations = normalizedSituations.ifEmpty { defaultSituationsFor(validCat) },
                recommendedApps = decoded.recommendedApps.ifEmpty { defaultAppsFor(validCat) }
            )
        }.getOrNull()
    }

    private fun heuristicClassify(heardText: String, fileName: String): GemmaClassificationJson {
        val combined = "$heardText ${fileName.substringBeforeLast('.')}".lowercase()
        val category = when {
            listOf("പരീക്ഷ", "തോറ്റു", "മാർക്ക്", "സപ്ലി", "exam", "fail", "supply", "arrear", "potti", "mark", "yodha").any { combined.contains(it) } ->
                SituationCategory.EXAM_FAIL.id
            listOf("പൈസ", "കാശു", "ശമ്പളം", "ബാങ്ക്", "money", "paisa", "salary", "bank", "credited", "debited").any { combined.contains(it) } ->
                SituationCategory.MONEY.id
            listOf("എഴുന്നേൽ", "ഉറക്കം", "രാവിലെ", "alarm", "wake", "morning", "sleep").any { combined.contains(it) } ->
                SituationCategory.ALARM.id
            listOf("ബാറ്ററി", "ചാർജ്", "battery", "charge", "low").any { combined.contains(it) } ->
                SituationCategory.LOW_BATTERY.id
            listOf("പ്രേമം", "ഇഷ്ടം", "സ്നേഹം", "love", "romance", "crush", "premam").any { combined.contains(it) } ->
                SituationCategory.ROMANCE.id
            listOf("വഴക്ക്", "അടി", "പണി", "scold", "angry", "late", "mistake").any { combined.contains(it) } ->
                SituationCategory.SCOLDING.id
            listOf("നമസ്കാരം", "ഹലോ", "സ്വാഗതം", "hello", "welcome", "greeting", "eda", "machane").any { combined.contains(it) } ->
                SituationCategory.GREETING.id
            listOf("ജയിച്ചു", "പൊളി", "അടിച്ചു", "win", "congrats", "poli", "celebrat", "success").any { combined.contains(it) } ->
                SituationCategory.CELEBRATION.id
            listOf("സൂക്ഷി", "അപകടം", "ശ്രദ്ധി", "warning", "alert", "urgent", "danger").any { combined.contains(it) } ->
                SituationCategory.WARNING.id
            else -> SituationCategory.CUSTOM.id
        }

        return GemmaClassificationJson(
            title = cleanTitleFromFileName(fileName),
            heardText = heardText.ifBlank { cleanTitleFromFileName(fileName) },
            category = category,
            situations = defaultSituationsFor(category),
            recommendedApps = defaultAppsFor(category)
        )
    }

    private fun cleanTitleFromFileName(fileName: String): String {
        return fileName.substringBeforeLast('.')
            .replace(Regex("[_-]+"), " ")
            .trim()
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            .ifBlank { "Malayalam Dialogue" }
    }

    private fun defaultSituationsFor(category: String): List<String> = when (category) {
        "exam_fail" -> listOf("You failed in your exam", "arrear supply result", "low marks", "exam potti", "പരീക്ഷ തോറ്റു")
        "money" -> listOf("salary credited bank", "account debited upi", "no money broke", "paisa vannu")
        "alarm" -> listOf("wake up morning alarm", "ezhunelkk time aayi", "overslept late morning")
        "low_battery" -> listOf("battery low 15%", "connect charger now", "phone dying low power")
        "romance" -> listOf("crush texted me", "i miss you love", "premam ishtam message")
        "scolding" -> listOf("you are late again", "big mistake scolding", "pani paali roast")
        "greeting" -> listOf("hello welcome friend", "namaskaram machane", "incoming message greeting")
        "celebration" -> listOf("congratulations you won", "passed exam success", "poli sadhanam party")
        "warning" -> listOf("urgent deadline alert", "be careful warning", "security alert")
        else -> listOf("general reaction", "funny malayalam dialogue", "chat reply")
    }

    private fun defaultAppsFor(category: String): List<String> = when (category) {
        "exam_fail", "money", "warning" -> listOf("com.google.android.gm", "com.google.android.apps.messaging")
        "romance", "greeting", "scolding", "celebration" -> listOf("com.whatsapp", "com.instagram.android")
        else -> listOf("com.whatsapp", "com.google.android.gm")
    }

    companion object {
        private val CLASSIFIER_SYSTEM_INSTRUCTION = """
            You classify spoken Malayalam movie/comedy dialogues into phone notification & assistant trigger situations.
            Output ONLY compact JSON matching:
            {"title":"...","heardText":"...","category":"exam_fail|money|alarm|low_battery|romance|scolding|greeting|celebration|warning|custom","situations":["english phrase","manglish phrase"],"recommendedApps":["com.google.android.gm","com.whatsapp"]}
        """.trimIndent()

        private val CHAT_SYSTEM_MALAYALAM = """
            You are a warm, witty Malayalam voice assistant. Reply briefly in natural Malayalam (1-2 short sentences).
        """.trimIndent()

        private val CHAT_SYSTEM_ENGLISH = """
            You are a sleek, concise voice assistant. Reply in 1-2 short sentences and understand English, Malayalam, and Manglish.
        """.trimIndent()
    }
}
