package com.malayalam.assistant.ai

import android.content.Context
import com.malayalam.assistant.data.AppPreferences
import com.malayalam.assistant.data.GemmaClassificationJson
import com.malayalam.assistant.data.SituationCategory
import kotlinx.serialization.json.Json

/**
 * Situation Classifier & Light Chat (`Gemma 3 1B` `dynamic_int4` QAT default):
 *  - Defaults strictly to `Gemma 3 1B` (`~529 MB`, `~1.15 GB` peak RAM) on 4 GB RAM devices.
 *  - Uses `Gemma 4 E2B` ONLY when device RAM >= 7.5 GB, Low-RAM Safe Mode is OFF,
 *    the user explicitly enabled the 8 GB+ toggle, and the model file is downloaded.
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

    private suspend fun selectActiveGemmaModel(): ManagedModelId? {
        val settings = prefs.snapshot()
        val totalRamMb = AppPreferences.getTotalRamMb(context)
        if (
            !settings.lowRamSafeMode &&
            settings.enableGemma4E2bOptional &&
            totalRamMb >= 7000L &&
            downloader.isModelReady(ManagedModelId.GEMMA_4_E2B_OPTIONAL)
        ) {
            return ManagedModelId.GEMMA_4_E2B_OPTIONAL
        }
        if (downloader.isModelReady(ManagedModelId.GEMMA_3_1B)) {
            return ManagedModelId.GEMMA_3_1B
        }
        return null
    }

    /**
     * Maps a heard Malayalam transcript (and clip filename hint) into the structured JSON schema:
     * `{ "title", "heardText", "category", "situations", "recommendedApps" }`.
     */
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

        // Offline Malayalam + Manglish heuristic fallback when Gemma 3 1B is not yet downloaded
        return heuristicClassify(heardMalayalam, fileName)
    }

    /**
     * Concise Gemini-style offline chat + screen-assist Q&A (`maxOutputTokens = 160`).
     */
    suspend fun generateAssistantReply(
        userMessage: String,
        screenContext: String? = null
    ): String {
        val modelId = selectActiveGemmaModel()
        if (modelId != null) {
            val modelFile = downloader.primaryModelFile(modelId)
            val fullPrompt = if (!screenContext.isNullOrBlank()) {
                "Visible screen text:\n${screenContext.take(1200)}\n\nUser request: $userMessage"
            } else {
                userMessage
            }
            val reply = runCatching {
                coordinator.withModel(
                    modelId = modelId,
                    factory = { LiteRtLlmSession(context, modelFile).also { it.initialize() } }
                ) { session ->
                    session.generateText(
                        systemInstruction = CHAT_SYSTEM_INSTRUCTION,
                        userPrompt = fullPrompt,
                        temperature = 0.35,
                        maxOutputTokens = 160
                    )
                }
            }.getOrNull()

            if (!reply.isNullOrBlank()) return reply
        }

        return if (!screenContext.isNullOrBlank()) {
            "Screen text captured (${screenContext.length} chars): \"${screenContext.take(180)}...\". Download Gemma 3 1B (dynamic_int4) in Models & Settings for full offline AI answers, or try daily actions like \"Set alarm for 6:30 AM\"."
        } else {
            "Offline Lightweight Mode active. You can run alarms, calls, reminders, apps, and Malayalam dialogue reactions right now — or download Gemma 3 1B in Models & Settings for full on-device chat."
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
            listOf("പരീക്ഷ", "തോറ്റു", "മാർക്ക്", "exam", "fail", "supply", "potti", "mark").any { combined.contains(it) } ->
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
            heardText = heardText.ifBlank { "(Transcript pending — IndicConformer ASR not downloaded yet)" },
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
        "exam_fail" -> listOf("You failed in your exam", "low marks result", "exam potti supply", "arrears published")
        "money" -> listOf("salary credited bank", "account debited upi", "no money broke", "paisa illa kasu")
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

        private val CHAT_SYSTEM_INSTRUCTION = """
            You are a sleek, witty, concise offline Android assistant running locally on a 4 GB RAM smartphone.
            Keep answers clear, helpful, and under 3 short sentences. Understand English, Malayalam, and Manglish.
        """.trimIndent()
    }
}
