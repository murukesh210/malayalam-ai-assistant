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
import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException
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
    val understoodMeaning: String = "",
    val replyMalayalam: String = "",
    val matchedDialogueId: String? = null,
    val playDialogueId: String? = null,
    val expectFollowUp: Boolean = false,
    val shouldReact: Boolean = true,
    val action: GeminiActionSpec = GeminiActionSpec(),
    val ttsStyle: String = "cheerful and friendly"
) {
    /** Unified non-blank dialogue ID regardless of whether `matchedDialogueId` or `playDialogueId` was returned. */
    val resolvedDialogueId: String?
        get() = matchedDialogueId?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            ?: playDialogueId?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
}

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
 * Bulletproof Cross-Lingual Google AI Studio Brain Client (`0 MB` phone RAM):
 *  - Sends a **single `user` content turn** (embedding recent context inline) so `thinkingLevel: "minimal"`
 *    on `gemini-3.5-flash-lite` NEVER triggers multi-turn `400 INVALID_ARGUMENT` (`thoughtSignature` errors).
 *  - Enforces strict `responseSchema` + `responseMimeType: "application/json"` with `maxOutputTokens = 500`
 *    (voice/text chat) and `350` (background notifications) so Malayalam Unicode JSON is never truncated.
 *  - Evicts stale sockets (`connectionPool.evictAll()`) and performs 1x immediate fresh-socket retry on
 *    `IOException` / `SocketTimeoutException` / `EOFException`.
 *  - Logs every call to `DiagnosticLogStore` (last 20 events) for Settings -> Live AI & Connection Log.
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

        // Single `user` content turn with embedded recent context -> ZERO multi-turn 400 errors with thinkingLevel="minimal"!
        val recentSummary = recentTurns.takeLast(6)
            .filter { it.text.isNotBlank() }
            .joinToString("\n") { t ->
                val speaker = if (t.isUser) "User" else "Assistant"
                "$speaker: ${t.text.take(180)}"
            }

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
                if (recentSummary.isNotBlank()) {
                    appendLine("Recent context:")
                    appendLine(recentSummary)
                    appendLine("---")
                }
                if (!screenContext.isNullOrBlank()) {
                    appendLine("Visible screen text: ${screenContext.take(800)}")
                }
                if (!userText.isNullOrBlank()) {
                    appendLine("Current user message: $userText")
                } else {
                    appendLine("Listen to the user's spoken Malayalam / Manglish / English audio above, transcribe it into heardTranscript, interpret its real-world meaning, and reply in strict JSON.")
                }
            }.trim()
            add(buildJsonObject { put("text", promptLine) })
        }

        val contents = buildJsonArray {
            add(buildJsonObject {
                put("role", "user")
                put("parts", currentParts)
            })
        }

        val inputLabel = userText?.take(80) ?: "[Voice WAV ${wavAudioBytes?.size ?: 0}B]"
        val attempt = callWithModelFallback(
            apiKey = cleanKey,
            systemInstruction = systemInstruction,
            contents = contents,
            responseSchema = buildAssistantResponseSchema(),
            temperature = 0.3,
            maxTokens = 500,
            client = SharedGeminiHttp.client
        )

        val parsed = attempt.text?.let { parseBrainJson(it) }
        DiagnosticLogStore.record(
            source = if (wavAudioBytes != null) "Voice Chat" else "Text Chat",
            inputText = inputLabel,
            httpCode = attempt.httpCode,
            latencyMs = attempt.elapsedMs,
            understoodMeaning = parsed?.understoodMeaning.orEmpty(),
            matchedDialogueId = parsed?.resolvedDialogueId,
            errorMessage = if (parsed != null) "" else attempt.errorSummary.ifBlank { "JSON parse failed" }
        )
        parsed
    }

    /**
     * Tier-2 Universal Cross-Lingual Notification Matcher (`maxOutputTokens = 350`, strict `responseSchema`,
     * single `user` turn, zero chat history):
     * Understands pure Malayalam (`എക്സാം പൊട്ടി`, `മാച്ച് തോറ്റു`), Manglish (`pareeksha potti`, `thottu`),
     * English, Hindi, and emojis conceptually against each dialogue's full user-written `"rule"`.
     */
    suspend fun analyzeNotificationFast(
        apiKey: String,
        sender: String,
        messageText: String,
        vaultDialogues: List<VaultDialogueSummary>,
        packPersonaInstruction: String = "",
        sourceLabel: String = "Notification"
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
                            "Source: $sourceLabel\nSender: ${sender.take(60)}\nMessage: ${messageText.take(400)}"
                        )
                    })
                })
            })
        }

        val attempt = callWithModelFallback(
            apiKey = cleanKey,
            systemInstruction = systemInstruction,
            contents = contents,
            responseSchema = buildNotificationResponseSchema(),
            temperature = 0.2,
            maxTokens = 350,
            client = SharedGeminiHttp.ultraFastNotificationClient
        )

        val parsed = attempt.text?.let { parseBrainJson(it) }
        DiagnosticLogStore.record(
            source = sourceLabel,
            inputText = messageText,
            httpCode = attempt.httpCode,
            latencyMs = attempt.elapsedMs,
            understoodMeaning = parsed?.understoodMeaning.orEmpty(),
            matchedDialogueId = parsed?.resolvedDialogueId,
            errorMessage = if (parsed != null) "" else attempt.errorSummary.ifBlank { "JSON parse failed" }
        )
        parsed
    }

    /**
     * Convenience alias used by rich diagnostics / Test Simulator.
     */
    suspend fun analyzeIncomingMessage(
        apiKey: String,
        sender: String,
        source: String,
        messageText: String,
        vaultDialogues: List<VaultDialogueSummary>,
        packPersonaInstruction: String = ""
    ): GeminiBrainResponse? = analyzeNotificationFast(
        apiKey = apiKey,
        sender = sender,
        messageText = messageText,
        vaultDialogues = vaultDialogues,
        packPersonaInstruction = packPersonaInstruction,
        sourceLabel = source
    )

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

        val attempt = callWithModelFallback(
            apiKey = cleanKey,
            systemInstruction = systemInstruction,
            contents = contents,
            responseSchema = null,
            temperature = 0.25,
            maxTokens = 350,
            client = SharedGeminiHttp.client
        )
        val rawText = attempt.text ?: return@withContext null

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
                put("maxOutputTokens", 120)
            })
        }

        var lastErr = "Connection failed"
        for (model in BRAIN_MODELS) {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$cleanKey"
            val req = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            val t0 = System.currentTimeMillis()
            val res = runCatching {
                SharedGeminiHttp.client.newCall(req).execute().use { resp ->
                    val elapsed = System.currentTimeMillis() - t0
                    val body = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        val text = extractCandidateText(body)?.trim().orEmpty()
                        _lastDebugStatus.value = "OK 200 ($model, ${elapsed}ms)"
                        DiagnosticLogStore.record("Test Key", model, resp.code, elapsed, "API key valid")
                        return@withContext Result.success("✅ Connected ($model): ${text.ifBlank { "Ready!" }}")
                    } else {
                        lastErr = "HTTP ${resp.code} ($model): ${extractApiErrorMessage(body)}"
                        _lastDebugStatus.value = lastErr
                        DiagnosticLogStore.record("Test Key", model, resp.code, elapsed, errorMessage = lastErr)
                        if (resp.code == 401 || resp.code == 403) {
                            return@withContext Result.failure(IllegalStateException(lastErr))
                        }
                    }
                }
            }
            res.onFailure {
                val elapsed = System.currentTimeMillis() - t0
                lastErr = "${it.javaClass.simpleName}: ${it.message}"
                _lastDebugStatus.value = lastErr
                DiagnosticLogStore.record("Test Key", model, 0, elapsed, errorMessage = lastErr)
            }
        }
        Result.failure(IllegalStateException(lastErr))
    }

    private data class HttpAttemptResult(
        val text: String? = null,
        val httpCode: Int = 0,
        val elapsedMs: Long = 0L,
        val errorSummary: String = ""
    )

    private fun callWithModelFallback(
        apiKey: String,
        systemInstruction: String,
        contents: JsonArray,
        responseSchema: JsonObject?,
        temperature: Double,
        maxTokens: Int,
        client: OkHttpClient
    ): HttpAttemptResult {
        var lastAttempt = HttpAttemptResult(errorSummary = "No model responded")
        for (model in BRAIN_MODELS) {
            val useThinkingMinimal = model.startsWith("gemini-3")
            val primaryPayload = buildRequestPayload(
                systemInstruction = systemInstruction,
                contents = contents,
                responseSchema = responseSchema,
                temperature = temperature,
                maxTokens = maxTokens,
                includeMinimalThinking = useThinkingMinimal
            )
            val attempt = executeWithFreshSocketRetry(model, apiKey, primaryPayload.toString(), client)
            lastAttempt = attempt
            if (attempt.text != null) {
                _lastDebugStatus.value = "200 OK ($model, ${attempt.elapsedMs}ms)"
                return attempt
            }

            // If HTTP 400 (e.g. model rejects thinkingConfig or strict schema), retry once cleanly without thinkingConfig
            if (attempt.httpCode == 400 && (useThinkingMinimal || responseSchema != null)) {
                val plainPayload = buildRequestPayload(
                    systemInstruction = systemInstruction,
                    contents = contents,
                    responseSchema = null,
                    temperature = temperature,
                    maxTokens = maxTokens,
                    includeMinimalThinking = false
                )
                val retryPlain = executeWithFreshSocketRetry(model, apiKey, plainPayload.toString(), client)
                lastAttempt = retryPlain
                if (retryPlain.text != null) {
                    _lastDebugStatus.value = "200 OK ($model, ${retryPlain.elapsedMs}ms)"
                    return retryPlain
                }
                _lastDebugStatus.value = retryPlain.errorSummary
            } else {
                _lastDebugStatus.value = attempt.errorSummary
            }
            if (attempt.httpCode == 401 || attempt.httpCode == 403) return lastAttempt
        }
        return lastAttempt
    }

    private fun executeWithFreshSocketRetry(
        model: String,
        apiKey: String,
        bodyStr: String,
        client: OkHttpClient
    ): HttpAttemptResult {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        var lastError = ""
        var lastElapsed = 0L

        for (attempt in 0..1) {
            val t0 = System.currentTimeMillis()
            val req = Request.Builder()
                .url(url)
                .post(bodyStr.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            try {
                client.newCall(req).execute().use { resp ->
                    lastElapsed = System.currentTimeMillis() - t0
                    val rawBody = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        val text = extractCandidateText(rawBody)
                        if (!text.isNullOrBlank()) {
                            return HttpAttemptResult(text = text, httpCode = resp.code, elapsedMs = lastElapsed)
                        }
                        return HttpAttemptResult(
                            httpCode = resp.code,
                            elapsedMs = lastElapsed,
                            errorSummary = "Empty candidate from $model"
                        )
                    } else {
                        val errMsg = "HTTP ${resp.code} ($model): ${extractApiErrorMessage(rawBody)}"
                        if (attempt == 0 && resp.code in listOf(429, 500, 502, 503)) {
                            lastError = errMsg
                            client.connectionPool.evictAll()
                            continue
                        }
                        return HttpAttemptResult(httpCode = resp.code, elapsedMs = lastElapsed, errorSummary = errMsg)
                    }
                }
            } catch (io: IOException) {
                lastElapsed = System.currentTimeMillis() - t0
                val isSocketError = io is SocketTimeoutException || io is EOFException || true
                lastError = "Network ($model): ${io.javaClass.simpleName} ${io.message?.take(60).orEmpty()}"
                if (attempt == 0 && isSocketError) {
                    // 1x automatic fresh-socket retry: evict dead carrier sockets and immediately retry!
                    runCatching { client.connectionPool.evictAll() }
                    SharedGeminiHttp.evictStaleSockets()
                    continue
                }
            } catch (e: Exception) {
                lastElapsed = System.currentTimeMillis() - t0
                return HttpAttemptResult(elapsedMs = lastElapsed, errorSummary = "Err ($model): ${e.message?.take(60)}")
            }
        }
        return HttpAttemptResult(elapsedMs = lastElapsed, errorSummary = lastError)
    }

    private fun buildRequestPayload(
        systemInstruction: String,
        contents: JsonArray,
        responseSchema: JsonObject?,
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
            if (responseSchema != null) {
                put("responseSchema", responseSchema)
            }
            put("temperature", temperature)
            put("maxOutputTokens", maxTokens)
            if (includeMinimalThinking) {
                put("thinkingConfig", buildJsonObject {
                    put("thinkingLevel", "minimal")
                })
            }
        })
    }

    private fun buildNotificationResponseSchema(): JsonObject = buildJsonObject {
        put("type", "OBJECT")
        put("properties", buildJsonObject {
            put("understoodMeaning", buildJsonObject { put("type", "STRING") })
            put("matchedDialogueId", buildJsonObject { put("type", "STRING"); put("nullable", true) })
            put("shouldReact", buildJsonObject { put("type", "BOOLEAN") })
            put("replyMalayalam", buildJsonObject { put("type", "STRING") })
            put("ttsStyle", buildJsonObject { put("type", "STRING") })
        })
        put("required", buildJsonArray {
            add(kotlinx.serialization.json.JsonPrimitive("understoodMeaning"))
            add(kotlinx.serialization.json.JsonPrimitive("shouldReact"))
            add(kotlinx.serialization.json.JsonPrimitive("replyMalayalam"))
            add(kotlinx.serialization.json.JsonPrimitive("ttsStyle"))
        })
    }

    private fun buildAssistantResponseSchema(): JsonObject = buildJsonObject {
        put("type", "OBJECT")
        put("properties", buildJsonObject {
            put("heardTranscript", buildJsonObject { put("type", "STRING") })
            put("understoodMeaning", buildJsonObject { put("type", "STRING") })
            put("replyMalayalam", buildJsonObject { put("type", "STRING") })
            put("matchedDialogueId", buildJsonObject { put("type", "STRING"); put("nullable", true) })
            put("expectFollowUp", buildJsonObject { put("type", "BOOLEAN") })
            put("ttsStyle", buildJsonObject { put("type", "STRING") })
            put("action", buildJsonObject {
                put("type", "OBJECT")
                put("properties", buildJsonObject {
                    put("type", buildJsonObject { put("type", "STRING") })
                    put("hour", buildJsonObject { put("type", "INTEGER"); put("nullable", true) })
                    put("minute", buildJsonObject { put("type", "INTEGER"); put("nullable", true) })
                    put("label", buildJsonObject { put("type", "STRING") })
                    put("value", buildJsonObject { put("type", "STRING") })
                })
                put("required", buildJsonArray {
                    add(kotlinx.serialization.json.JsonPrimitive("type"))
                })
            })
        })
        put("required", buildJsonArray {
            add(kotlinx.serialization.json.JsonPrimitive("understoodMeaning"))
            add(kotlinx.serialization.json.JsonPrimitive("replyMalayalam"))
            add(kotlinx.serialization.json.JsonPrimitive("expectFollowUp"))
            add(kotlinx.serialization.json.JsonPrimitive("action"))
            add(kotlinx.serialization.json.JsonPrimitive("ttsStyle"))
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
        }.getOrNull()?.take(90) ?: "Check key/network"
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
            val resolved = parsed.resolvedDialogueId
            parsed.copy(
                matchedDialogueId = resolved,
                playDialogueId = resolved
            )
        }.getOrNull()
    }

    /**
     * Formats every playable dialogue with its full user-written `"rule"` (When to Play) and `"specificity"`
     * so Gemini compares conceptual meaning across Malayalam, Manglish, English, Hindi, and emojis.
     */
    private fun formatVaultForPrompt(vault: List<VaultDialogueSummary>): String {
        val playableOnly = vault.filter { it.hasAudio }
        if (playableOnly.isEmpty()) return "[]"
        return playableOnly.joinToString(prefix = "[", postfix = "]") { d ->
            val fullRule = d.situations.joinToString(", ").replace("\"", "'").replace("\n", " ")
            val safeName = d.title.replace("\"", "'")
            """{"id":"${d.id}","name":"$safeName","specificity":"${d.scope}","rule":"$fullRule"}"""
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

            Active Voice Pack Playable Dialogues:
            ${formatVaultForPrompt(vault)}

            UNIVERSAL CROSS-LINGUAL SITUATION-UNDERSTANDING RUBRIC (CRITICAL):
            1. First interpret the real-world meaning, slang, and emotional tone of the user's speech/text regardless of language or script — pure Malayalam (`എക്സാം പൊട്ടി`, `പരീക്ഷ തോറ്റു`, `മാച്ച് തോറ്റു`, `ജയിച്ചു`, `പൈസ അയച്ചിട്ടുണ്ട്`, `പൈസ വന്നു`), Manglish (`pareeksha potti`, `fail aayi`, `thottu`, `pass aayi`, `paisa vannu`), English, Hindi, and emojis.
            2. Kerala colloquialisms: `"പൊട്ടി"` / `"potti"` / `"potty"` / `"മൂഞ്ചി"` / `"തോറ്റു"` / `"thottu"` / `"fail aayi"` in exam, test, match, or task contexts ALWAYS mean FAILURE / NEGATIVE OUTCOME.
            3. Compare that interpreted real-world situation conceptually against each dialogue's `"rule"` (e.g. if a rule says "negative situation like failed in test/exam/match", then `എക്സാം പൊട്ടി`, `മാച്ച് തോറ്റു`, `test potti`, and `lost the game` ALL match it!).
            4. SPECIFICITY-FIRST & ANTI-OPPOSITE PRIORITY:
               - A matching `"specificity":"specific"` dialogue ALWAYS beats a `"specificity":"broad"` mood dialogue.
               - If NO specific dialogue fits, fall back to a matching `"broad"` mood dialogue.
               - NEVER match opposites (`എക്സാം പാസ്സായി` / `passed` MUST NEVER match a failure dialogue; `money credited` MUST NEVER match `debited`).
            5. Alarm Rules:
               - If the user wants an alarm WITHOUT a time: action.type="none", expectFollowUp=true, replyMalayalam="എത്ര മണിക്കാണ് അലാറം വെക്കേണ്ടത്?".
               - If a time is known (now or in Recent context): action.type="set_alarm", action.hour=<0..23>, action.minute=<0..59>, action.label="Assistant Alarm", expectFollowUp=false.
            6. Other actions: open_app, dial_call, add_todo.
            7. Put the exact matching dialogue ID (or null) in `matchedDialogueId`, and a short English summary of the event & emotion in `understoodMeaning`.
        """.trimIndent()
    }

    private fun buildIncomingReactorInstruction(
        vault: List<VaultDialogueSummary>,
        packPersonaInstruction: String
    ): String {
        val personaLine = packPersonaInstruction.ifBlank { "Warm, witty Malayalam friend style." }
        return """
            You are a cross-lingual Malayalam notification & message intelligence engine.
            Active Voice Pack Persona: $personaLine
            Active Voice Pack Playable Dialogues:
            ${formatVaultForPrompt(vault)}

            UNIVERSAL CROSS-LINGUAL SITUATION-UNDERSTANDING RUBRIC (CRITICAL):
            1. First translate/interpret the real-world meaning, slang, and emotional tone of the incoming message regardless of language or script — understand pure Malayalam (`എക്സാം പൊട്ടി`, `പരീക്ഷ തോറ്റു`, `മാച്ച് തോറ്റു`, `ജയിച്ചു`, `പൈസ അയച്ചിട്ടുണ്ട്`, `പൈസ വന്നു`), Manglish (`pareeksha potti`, `pareeksha potty`, `fail aayi`, `thottu`, `pass aayi`, `paisa vannu`), English, Hindi, and emojis.
            2. Kerala colloquialisms: `"പൊട്ടി"` / `"potti"` / `"potty"` / `"മൂഞ്ചി"` / `"തോറ്റു"` / `"thottu"` / `"fail aayi"` in exam, test, match, or task contexts ALWAYS mean FAILURE / NEGATIVE OUTCOME.
            3. Compare that interpreted real-world situation conceptually against each dialogue's `"rule"`. For example, if a dialogue rule says "play this dialogue when there is a negative situation like I failed in a test or exam or any match or failed in any item", then `"എക്സാം പൊട്ടി"`, `"മാച്ച് തോറ്റു"`, `"test potti"`, and `"lost the game"` ALL match that dialogue!
            4. SPECIFICITY-FIRST & ANTI-OPPOSITE PRIORITY:
               - Specific event match (`"specificity":"specific"`) ALWAYS beats broad mood match (`"specificity":"broad"`).
               - If no specific dialogue matches, fall back to a matching broad mood dialogue.
               - NEVER match opposites (`"എക്സാം പാസ്സായി"` / `"exam passed"` MUST NEVER match a failure dialogue; `"credited"` MUST NEVER match `"debited"`).
            5. Set `shouldReact` = false ONLY for OTPs, spam, ads, marketing promos, or boring delivery tracking. ALWAYS set `shouldReact` = true for bank credit/debit, UPI/GPay/PhonePe transfers, exam/match results, and personal/friend messages.
            6. Put the short English summary of the event & emotion in `understoodMeaning`, the exact matching dialogue ID (or null) in `matchedDialogueId`, and a short natural 1-sentence Malayalam reaction in `replyMalayalam` (in Active Voice Pack Persona) in case no dialogue matched.
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
