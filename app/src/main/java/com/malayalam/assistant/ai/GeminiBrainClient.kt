package com.malayalam.assistant.ai

import android.util.Base64
import com.malayalam.assistant.data.DialogueEntry
import com.malayalam.assistant.data.GemmaClassificationJson
import com.malayalam.assistant.data.SituationCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
data class GeminiActionSpec(
    val type: String = "none", // none | set_alarm | open_app | dial_call | add_todo
    val hour: Int? = null,
    val minute: Int? = null,
    val label: String = "",
    val value: String = ""
)

@Serializable
data class GeminiBrainResponse(
    val heardTranscript: String = "",
    val replyMalayalam: String = "",
    val playDialogueId: String? = null,
    val expectFollowUp: Boolean = false,
    val shouldReact: Boolean = true,
    val action: GeminiActionSpec = GeminiActionSpec(),
    val ttsStyle: String = "cheerful and friendly"
)

data class ConversationHistoryItem(
    val isUser: Boolean,
    val text: String
)

data class VaultDialogueSummary(
    val id: String,
    val title: String,
    val situations: List<String>,
    val scope: String = "specific", // "specific" | "broad"
    val hasAudio: Boolean
)

/**
 * Low-Latency Google AI Studio Brain Client (`0 MB` phone RAM):
 *  - Uses `SharedGeminiHttp` HTTP/2 keep-alive connection pool.
 *  - Sets `thinkingConfig: { thinkingLevel: "minimal" }` + `maxOutputTokens: 220` for ~0.6-1.0s replies.
 *  - Passes last 6 conversation turns + current local time (`HH:mm`, day of week) for multi-turn alarms.
 *  - Retries once cleanly on transient socket timeout & strips markdown fences before JSON parsing.
 *  - Logs exact HTTP status / error codes to `lastDebugStatus` for Settings diagnostics.
 */
class GeminiBrainClient {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val _lastDebugStatus = MutableStateFlow("Ready")
    val lastDebugStatus: StateFlow<String> = _lastDebugStatus.asStateFlow()

    suspend fun askWithTextOrAudio(
        apiKey: String,
        userText: String? = null,
        wavAudioBytes: ByteArray? = null,
        screenContext: String? = null,
        recentTurns: List<ConversationHistoryItem> = emptyList(),
        vaultDialogues: List<VaultDialogueSummary>,
        packPersonaInstruction: String = "",
        preferMalayalam: Boolean = true
    ): GeminiBrainResponse? = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) return@withContext null

        val systemInstruction = buildAssistantSystemInstruction(
            vault = vaultDialogues,
            packPersonaInstruction = packPersonaInstruction,
            preferMalayalam = preferMalayalam
        )
        val contents = buildJsonArray {
            // Include last 6 conversation turns for natural multi-turn follow-ups (e.g. "Which time?" -> "6:30 AM")
            recentTurns.takeLast(6).forEach { turn ->
                if (turn.text.isNotBlank()) {
                    add(buildJsonObject {
                        put("role", if (turn.isUser) "user" else "model")
                        put("parts", buildJsonArray {
                            add(buildJsonObject { put("text", turn.text.take(240)) })
                        })
                    })
                }
            }

            // Current user turn (inline 8 kHz WAV audio and/or text)
            val currentParts = buildJsonArray {
                if (wavAudioBytes != null && wavAudioBytes.isNotEmpty()) {
                    val b64 = Base64.encodeToString(wavAudioBytes, Base64.NO_WRAP)
                    add(buildJsonObject {
                        put("inlineData", buildJsonObject {
                            put("mimeType", "audio/wav")
                            put("data", b64)
                        })
                    })
                }
                val promptLine = buildString {
                    if (!screenContext.isNullOrBlank()) {
                        appendLine("Visible screen text: ${screenContext.take(800)}")
                    }
                    if (!userText.isNullOrBlank()) {
                        appendLine(userText)
                    } else {
                        appendLine("Listen to the user's spoken Malayalam / Manglish / English audio above, transcribe it into heardTranscript, and reply in JSON.")
                    }
                }.trim()
                add(buildJsonObject { put("text", promptLine) })
            }
            add(buildJsonObject {
                put("role", "user")
                put("parts", currentParts)
            })
        }

        val rawText = callWithModelFallback(
            apiKey = cleanKey,
            systemInstruction = systemInstruction,
            contents = contents,
            temperature = 0.35,
            maxTokens = 220,
            client = SharedGeminiHttp.client
        ) ?: return@withContext null

        parseBrainJson(rawText)
    }

    /**
     * Tier-2 Ultra-Fast Slim Cloud Semantic Matcher for Background Notifications (~0.8s–1.2s):
     *  - Sends ZERO chat history and an ultra-compact `id|scope|whenToPlay` catalogue.
     *  - Uses `gemini-3.5-flash-lite` + `thinkingConfig: { thinkingLevel: "minimal" }` + `maxOutputTokens: 90`
     *    over the shared keep-alive pool with tight `connectTimeout = 3s` / `readTimeout = 5s`.
     */
    suspend fun analyzeNotificationFast(
        apiKey: String,
        sender: String,
        messageText: String,
        vaultDialogues: List<VaultDialogueSummary>,
        packPersonaInstruction: String = ""
    ): GeminiBrainResponse? = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) return@withContext null

        val compactCatalog = vaultDialogues
            .filter { it.hasAudio }
            .joinToString("\n") { d ->
                val triggers = d.situations.take(6).joinToString(", ").replace("\n", " ")
                "${d.id}|${d.scope}|${d.title}: $triggers"
            }
            .ifBlank { "NONE" }

        val personaHint = packPersonaInstruction.take(90).ifBlank { "Witty Malayalam friend." }
        val systemInstruction = """
            Match incoming message to a playable clip OR short 1-sentence Malayalam reply ($personaHint).
            Clips (id|scope|name: whenToPlay):
            $compactCatalog
            Rules:
            - shouldReact=false ONLY for OTP/spam/ads/promos. true for bank/UPI/exam/personal messages.
            - Prefer scope=specific over scope=broad. Use broad mood ONLY if no specific clip fits.
            - NEVER mismatch opposites (pass!=fail, credit!=debit).
            - Return ONLY JSON: {"shouldReact":true,"playDialogueId":null,"replyMalayalam":"...","ttsStyle":"cheerful"}
        """.trimIndent()

        val contents = buildJsonArray {
            add(buildJsonObject {
                put("role", "user")
                put("parts", buildJsonArray {
                    add(buildJsonObject {
                        put("text", """{"sender":"${sender.take(40).replace("\"", "'")}","text":"${messageText.take(260).replace("\"", "'")}"}""")
                    })
                })
            })
        }

        val rawText = callWithModelFallback(
            apiKey = cleanKey,
            systemInstruction = systemInstruction,
            contents = contents,
            temperature = 0.2,
            maxTokens = 90,
            client = SharedGeminiHttp.ultraFastNotificationClient
        ) ?: return@withContext null

        parseBrainJson(rawText)
    }

    /**
     * Fast incoming message reactor for Test Simulator / rich diagnostics.
     */
    suspend fun analyzeIncomingMessage(
        apiKey: String,
        sender: String,
        source: String,
        messageText: String,
        vaultDialogues: List<VaultDialogueSummary>,
        packPersonaInstruction: String = ""
    ): GeminiBrainResponse? = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) return@withContext null

        val systemInstruction = buildIncomingReactorInstruction(vaultDialogues, packPersonaInstruction)
        val contents = buildJsonArray {
            add(buildJsonObject {
                put("role", "user")
                put("parts", buildJsonArray {
                    add(buildJsonObject {
                        put(
                            "text",
                            "Source: $source\nSender: $sender\nMessage: ${messageText.take(500)}"
                        )
                    })
                })
            })
        }

        val rawText = callWithModelFallback(
            apiKey = cleanKey,
            systemInstruction = systemInstruction,
            contents = contents,
            temperature = 0.3,
            maxTokens = 200,
            client = SharedGeminiHttp.fastClient
        ) ?: return@withContext null

        parseBrainJson(rawText)
    }

    /**
     * "✨ Auto-Listen & Fill with AI" in Add/Edit Dialogue sheet:
     * Sends the picked audio clip directly inline to `gemini-3.5-flash-lite` so Gemini listens to
     * the Malayalam movie clip and fills Dialogue Name + Manglish/Malayalam/English trigger situations!
     */
    suspend fun autoTagAudioClipWithGemini(
        apiKey: String,
        audioBytes: ByteArray,
        mimeType: String,
        fileName: String
    ): GemmaClassificationJson? = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank() || audioBytes.isEmpty()) return@withContext null

        val b64 = Base64.encodeToString(audioBytes, Base64.NO_WRAP)
        val systemInstruction = """
            You listen to Malayalam movie/comedy audio clips and tag them for a smartphone assistant.
            Return ONLY valid JSON:
            {
              "title": "short catchy dialogue name (e.g. Njan veendum kodeeshwaran)",
              "heardText": "spoken Malayalam / Manglish transcript of the clip",
              "category": "exam_fail | money | alarm | low_battery | romance | scolding | greeting | celebration | warning | custom",
              "situations": ["5 to 8 short Manglish, Malayalam, and English trigger phrases when this clip should play, e.g. eda paisa ayachittund, money received, salary credited, GPay credit"]
            }
        """.trimIndent()

        val contents = buildJsonArray {
            add(buildJsonObject {
                put("role", "user")
                put("parts", buildJsonArray {
                    add(buildJsonObject {
                        put("inlineData", buildJsonObject {
                            put("mimeType", mimeType)
                            put("data", b64)
                        })
                    })
                    add(buildJsonObject {
                        put("text", "Listen to this Malayalam audio clip (file: $fileName) and return the JSON title, heardText, category, and trigger situations.")
                    })
                })
            })
        }

        val rawText = callWithModelFallback(
            apiKey = cleanKey,
            systemInstruction = systemInstruction,
            contents = contents,
            temperature = 0.25,
            maxTokens = 260,
            client = SharedGeminiHttp.client
        ) ?: return@withContext null

        val cleaned = stripMarkdownFences(rawText)
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return@withContext null
        runCatching {
            val decoded = json.decodeFromString<GemmaClassificationJson>(cleaned.substring(start, end + 1))
            decoded.copy(
                title = decoded.title.ifBlank { fileName.substringBeforeLast('.') },
                category = SituationCategory.fromId(decoded.category).id,
                situations = decoded.situations.flatMap { it.split(",") }.map { it.trim() }.filter { it.isNotBlank() }.distinct()
            )
        }.getOrNull()
    }

    suspend fun testApiKey(apiKey: String): Result<String> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Enter your Google AI Studio API key first."))
        }
        val payload = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", "Reply in one short friendly Malayalam sentence saying you are ready.") })
                    })
                })
            })
            put("generationConfig", buildJsonObject {
                put("maxOutputTokens", 64)
            })
        }

        var lastErr = "Connection failed"
        for (model in BRAIN_MODELS) {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$cleanKey"
            val req = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            val res = runCatching {
                SharedGeminiHttp.client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        val text = extractCandidateText(body)?.trim().orEmpty()
                        _lastDebugStatus.value = "OK 200 ($model)"
                        return@withContext Result.success("✅ Connected ($model): ${text.ifBlank { "Ready!" }}")
                    } else {
                        lastErr = "HTTP ${resp.code} ($model): ${extractApiErrorMessage(body)}"
                        _lastDebugStatus.value = lastErr
                        if (resp.code == 401 || resp.code == 403) {
                            return@withContext Result.failure(IllegalStateException(lastErr))
                        }
                    }
                }
            }
            res.onFailure {
                lastErr = "${it.javaClass.simpleName}: ${it.message}"
                _lastDebugStatus.value = lastErr
            }
        }
        Result.failure(IllegalStateException(lastErr))
    }

    private fun callWithModelFallback(
        apiKey: String,
        systemInstruction: String,
        contents: JsonArray,
        temperature: Double,
        maxTokens: Int,
        client: OkHttpClient
    ): String? {
        for (model in BRAIN_MODELS) {
            // Try with minimal thinkingConfig first on 3.x models for ~0.6-1.0s latency
            val useThinkingMinimal = model.startsWith("gemini-3")
            val primaryPayload = buildRequestPayload(
                systemInstruction = systemInstruction,
                contents = contents,
                temperature = temperature,
                maxTokens = maxTokens,
                includeMinimalThinking = useThinkingMinimal
            )
            val attempt = executeWithSingleTimeoutRetry(model, apiKey, primaryPayload.toString(), client)
            if (attempt.text != null) {
                _lastDebugStatus.value = "OK 200 ($model, ${attempt.elapsedMs} ms)"
                return attempt.text
            }

            // If HTTP 400 with thinkingConfig, retry same model once without thinkingConfig
            if (attempt.httpCode == 400 && useThinkingMinimal) {
                val plainPayload = buildRequestPayload(
                    systemInstruction = systemInstruction,
                    contents = contents,
                    temperature = temperature,
                    maxTokens = maxTokens,
                    includeMinimalThinking = false
                )
                val retryPlain = executeWithSingleTimeoutRetry(model, apiKey, plainPayload.toString(), client)
                if (retryPlain.text != null) {
                    _lastDebugStatus.value = "OK 200 ($model, ${retryPlain.elapsedMs} ms)"
                    return retryPlain.text
                }
                _lastDebugStatus.value = retryPlain.errorSummary
            } else {
                _lastDebugStatus.value = attempt.errorSummary
            }
            if (attempt.httpCode == 401 || attempt.httpCode == 403) return null
        }
        return null
    }

    private data class HttpAttemptResult(
        val text: String? = null,
        val httpCode: Int = 0,
        val elapsedMs: Long = 0L,
        val errorSummary: String = ""
    )

    private fun executeWithSingleTimeoutRetry(
        model: String,
        apiKey: String,
        bodyStr: String,
        client: OkHttpClient
    ): HttpAttemptResult {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        var lastError = ""
        for (attempt in 0..1) {
            val t0 = System.currentTimeMillis()
            val req = Request.Builder()
                .url(url)
                .post(bodyStr.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            try {
                client.newCall(req).execute().use { resp ->
                    val elapsed = System.currentTimeMillis() - t0
                    val rawBody = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        val text = extractCandidateText(rawBody)
                        if (!text.isNullOrBlank()) {
                            return HttpAttemptResult(text = text, httpCode = resp.code, elapsedMs = elapsed)
                        }
                        return HttpAttemptResult(
                            httpCode = resp.code,
                            elapsedMs = elapsed,
                            errorSummary = "Empty candidate from $model"
                        )
                    } else {
                        val errMsg = "HTTP ${resp.code} ($model): ${extractApiErrorMessage(rawBody)}"
                        // Retry once on transient 500/503/429
                        if (attempt == 0 && resp.code in listOf(429, 500, 502, 503)) {
                            lastError = errMsg
                            continue
                        }
                        return HttpAttemptResult(httpCode = resp.code, elapsedMs = elapsed, errorSummary = errMsg)
                    }
                }
            } catch (io: IOException) {
                lastError = "Network (${model}): ${io.javaClass.simpleName} ${io.message?.take(50).orEmpty()}"
                if (attempt == 0) continue
            } catch (e: Exception) {
                return HttpAttemptResult(errorSummary = "Err ($model): ${e.message?.take(60)}")
            }
        }
        return HttpAttemptResult(errorSummary = lastError)
    }

    private fun buildRequestPayload(
        systemInstruction: String,
        contents: JsonArray,
        temperature: Double,
        maxTokens: Int,
        includeMinimalThinking: Boolean
    ): JsonObject = buildJsonObject {
        put("systemInstruction", buildJsonObject {
            put("parts", buildJsonArray {
                add(buildJsonObject { put("text", systemInstruction) })
            })
        })
        put("contents", contents)
        put("generationConfig", buildJsonObject {
            put("responseMimeType", "application/json")
            put("temperature", temperature)
            put("maxOutputTokens", maxTokens)
            if (includeMinimalThinking) {
                put("thinkingConfig", buildJsonObject {
                    put("thinkingLevel", "minimal")
                })
            }
        })
    }

    private fun extractCandidateText(responseBody: String): String? {
        return runCatching {
            val root = json.parseToJsonElement(responseBody).jsonObject
            val candidates = root["candidates"]?.jsonArray ?: return@runCatching null
            val parts = candidates.firstOrNull()?.jsonObject
                ?.get("content")?.jsonObject
                ?.get("parts")?.jsonArray ?: return@runCatching null
            parts.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                .joinToString("\n")
                .trim()
        }.getOrNull()
    }

    private fun extractApiErrorMessage(responseBody: String): String {
        return runCatching {
            val root = json.parseToJsonElement(responseBody).jsonObject
            root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        }.getOrNull()?.take(80) ?: "Check key/network"
    }

    private fun stripMarkdownFences(raw: String): String {
        return raw.trim()
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
    }

    private fun parseBrainJson(raw: String): GeminiBrainResponse? {
        val cleaned = stripMarkdownFences(raw)
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val slice = cleaned.substring(start, end + 1)
        return runCatching {
            val parsed = json.decodeFromString<GeminiBrainResponse>(slice)
            parsed.copy(
                playDialogueId = parsed.playDialogueId?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            )
        }.getOrNull()
    }

    private fun formatVaultForPrompt(vault: List<VaultDialogueSummary>): String {
        val playableOnly = vault.filter { it.hasAudio }
        if (playableOnly.isEmpty()) return "[]"
        return playableOnly.joinToString(prefix = "[", postfix = "]") { d ->
            val whenToPlay = d.situations.take(8).joinToString(", ").replace("\"", "'")
            val safeName = d.title.replace("\"", "'")
            """{"id":"${d.id}","name":"$safeName","whenToPlay":"$whenToPlay","scope":"${d.scope}"}"""
        }
    }

    private fun buildAssistantSystemInstruction(
        vault: List<VaultDialogueSummary>,
        packPersonaInstruction: String,
        preferMalayalam: Boolean
    ): String {
        val nowTime = SimpleDateFormat("HH:mm (EEEE)", Locale.getDefault()).format(Date())
        val personaLine = packPersonaInstruction.ifBlank {
            "Warm, witty, natural Malayalam friend style."
        }
        val langNote = if (preferMalayalam) {
            "Write replyMalayalam in natural conversational Malayalam (1-2 short sentences) in this persona style: $personaLine"
        } else {
            "Write replyMalayalam in concise English/Manglish (1-2 short sentences) in this persona style: $personaLine"
        }
        return """
            You are a Malayalam AI smartphone assistant. Current local device time: $nowTime.
            $langNote
            You understand natural Malayalam, Manglish, and English speech and text.

            Active Voice Pack Playable Dialogues:
            ${formatVaultForPrompt(vault)}

            SPECIFICITY-FIRST DIALOGUE SELECTION RUBRIC (CRITICAL):
            1. Analyze the full real-world event, meaning, and emotional tone of the user's speech/text (Malayalam, Manglish, English).
            2. Read every dialogue's "whenToPlay" carefully (understand slang & synonyms like "eda paisa ayachittund" = money received / happy news; "exam pass aayi" = exam passed / happy news).
            3. SPECIFICITY RULE:
               - If a SPECIFIC dialogue (scope="specific") matches the exact event (e.g. "exam passed" OR "money credited"), ALWAYS pick that specific dialogue over a broad mood dialogue (scope="broad" like "happy things").
               - If NO specific dialogue exists for that exact event, fall back to a matching BROAD mood dialogue (scope="broad", e.g., if only "happy things" exists and no "money" dialogue exists, "money credited" MUST match "happy things"!).
               - NEVER mismatch opposites: "exam passed" MUST NEVER match "exam failed"; "money credited" MUST NEVER match "money debited"!
            4. Alarm Rules:
               - If the user wants to set an alarm WITHOUT a time: set action.type="none", expectFollowUp=true, replyMalayalam="എത്ര മണിക്കാണ് അലാറം വെക്കേണ്ടത്?".
               - If a time is known (now or from previous turn): set action.type="set_alarm", action.hour=<0..23>, action.minute=<0..59>, action.label="Assistant Alarm", expectFollowUp=false.
            5. Other actions: open_app, dial_call, add_todo.
            6. Respond ONLY with compact JSON:
               {"heardTranscript":"...","replyMalayalam":"...","playDialogueId":null,"expectFollowUp":false,"action":{"type":"none","hour":null,"minute":null,"label":"","value":""},"ttsStyle":"cheerful and friendly"}
        """.trimIndent()
    }

    private fun buildIncomingReactorInstruction(
        vault: List<VaultDialogueSummary>,
        packPersonaInstruction: String
    ): String {
        val personaLine = packPersonaInstruction.ifBlank { "Warm, witty Malayalam friend style." }
        return """
            You react to incoming WhatsApp messages, SMS, GPay/PhonePe alerts, and Gmail emails for a Malayalam user.
            Active Voice Pack Persona: $personaLine
            Active Voice Pack Playable Dialogues:
            ${formatVaultForPrompt(vault)}

            SPECIFICITY-FIRST RUBRIC (CRITICAL):
            1. Set "shouldReact": false ONLY for OTPs, spam, ads, marketing promos, or boring tracking alerts.
               ALWAYS set "shouldReact": true for: bank credit/debit, UPI/GPay/PhonePe transfers, exam results, AND genuine personal/friend messages.
            2. Analyze full meaning & emotional tone (Malayalam, Manglish, English):
               - If a SPECIFIC dialogue (scope="specific") matches the exact event, ALWAYS choose it over a BROAD mood dialogue (scope="broad").
               - If NO specific dialogue exists for that exact event, fall back to a matching BROAD mood dialogue (e.g. "money credited" -> "happy things" if no money dialogue exists).
               - NEVER mismatch opposites (e.g. "exam passed" NEVER matches "exam failed"; "credited" NEVER matches "debited").
            3. Always write a short, witty 1-sentence Malayalam reply in "replyMalayalam" matching the Active Voice Pack Persona (spoken aloud via streaming TTS if playDialogueId is null).
            4. Respond ONLY with compact JSON:
               {"heardTranscript":"","replyMalayalam":"...","playDialogueId":null,"expectFollowUp":false,"shouldReact":true,"action":{"type":"none","value":""},"ttsStyle":"cheerful and friendly | sympathetic and warm | dramatic and teasing | calm"}
        """.trimIndent()
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val BRAIN_MODELS = listOf(
            "gemini-3.5-flash-lite",
            "gemini-3.8-flash",
            "gemini-2.5-flash"
        )

        fun toVaultSummaries(
            entries: List<DialogueEntry>,
            hasAudioPredicate: (DialogueEntry) -> Boolean
        ): List<VaultDialogueSummary> = entries.map { e ->
            VaultDialogueSummary(
                id = e.id,
                title = e.title,
                situations = e.situations,
                scope = e.scope.ifBlank { "specific" },
                hasAudio = hasAudioPredicate(e)
            )
        }
    }
}
