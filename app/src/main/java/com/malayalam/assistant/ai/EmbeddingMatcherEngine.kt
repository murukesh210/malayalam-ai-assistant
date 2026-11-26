package com.malayalam.assistant.ai

import android.content.Context
import com.malayalam.assistant.data.DialogueEntry
import com.malayalam.assistant.data.MatchCandidate
import com.malayalam.assistant.data.SafVaultRepository
import kotlin.math.sqrt

/**
 * Smart 2 GB / 4 GB RAM Dialogue Matcher:
 *  - Works out-of-the-box with **0 AI in RAM** via Malayalam + Manglish + English synonym &
 *    partial-word stem matching (`minimum threshold >= 0.25f`).
 *  - Strictly suppresses unrelated messages (`< 0.12f` when zero concept/token/phrase overlap)
 *    so random emails or chats NEVER falsely play the first clip!
 *  - Seamlessly upgrades to `EmbeddingGemma 2 Text (270M)` if downloaded.
 */
class EmbeddingMatcherEngine(
    private val context: Context,
    private val coordinator: ModelCoordinator,
    private val downloader: ModelDownloadManager,
    private val vaultRepository: SafVaultRepository
) {

    suspend fun computeDocumentEmbeddingIfInstalled(entry: DialogueEntry): FloatArray? {
        if (!downloader.isModelReady(ManagedModelId.EMBEDDING_GEMMA_270M)) return null
        val modelFile = downloader.primaryModelFile(ManagedModelId.EMBEDDING_GEMMA_270M)
        val docInput = "task: search result | text: ${entry.compositeSearchDocument().trim()}"
        return coordinator.withModel(
            modelId = ManagedModelId.EMBEDDING_GEMMA_270M,
            factory = {
                LiteRtEmbeddingSession(context, modelFile).also { it.initialize() }
            }
        ) { session ->
            session.embedText(docInput)
        }
    }

    suspend fun rankDialogues(
        rawQuery: String,
        appPackageHint: String? = null,
        requirePlayableAudio: Boolean = false,
        topK: Int = 3
    ): List<MatchCandidate> {
        val allDialogues = vaultRepository.dialogues.value
        val dialogues = if (requirePlayableAudio) {
            allDialogues.filter { vaultRepository.hasPlayableAudio(it) }
        } else {
            allDialogues
        }
        if (dialogues.isEmpty() || rawQuery.isBlank()) return emptyList()

        // Optional neural EmbeddingGemma 2 (270M) path when installed
        if (downloader.isModelReady(ManagedModelId.EMBEDDING_GEMMA_270M)) {
            val modelFile = downloader.primaryModelFile(ManagedModelId.EMBEDDING_GEMMA_270M)
            val queryPrompt = "task: search query | text: ${rawQuery.trim()}"
            val neuralMatches = runCatching {
                coordinator.withModel(
                    modelId = ManagedModelId.EMBEDDING_GEMMA_270M,
                    factory = {
                        LiteRtEmbeddingSession(context, modelFile).also { it.initialize() }
                    }
                ) { session ->
                    val queryVec = session.embedText(queryPrompt) ?: return@withModel null
                    val cachedVectors = vaultRepository.vectors.value.toMutableMap()
                    var dirtyCache = false

                    val scored = dialogues.map { entry ->
                        val docVec = cachedVectors[entry.id] ?: session.embedText(
                            "task: search result | text: ${entry.compositeSearchDocument().trim()}"
                        )?.also {
                            cachedVectors[entry.id] = it
                            dirtyCache = true
                        }
                        val lex = lexicalSimilarity(rawQuery, entry)
                        val baseSim = if (docVec != null && docVec.size == queryVec.size) {
                            cosineSimilarity(queryVec, docVec)
                        } else {
                            lex
                        }
                        val appBoost = if (appPackageHint != null && entry.recommendedApps.contains(appPackageHint) && lex >= 0.18f) 0.04f else 0f
                        val playableBoost = if (vaultRepository.hasPlayableAudio(entry) && lex >= 0.20f) 0.03f else 0f
                        val blended = (baseSim * 0.72f + lex * 0.28f + appBoost + playableBoost).coerceIn(0f, 1f)
                        MatchCandidate(entry = entry, score = blended, usedNeuralEmbedding = docVec != null)
                    }

                    if (dirtyCache) {
                        vaultRepository.updateAllEmbeddings(cachedVectors)
                    }
                    scored.sortedByDescending { it.score }.take(topK)
                }
            }.getOrNull()

            if (!neuralMatches.isNullOrEmpty()) {
                return neuralMatches
            }
        }

        // Smart Zero-RAM Malayalam + Manglish + English Matcher
        return dialogues.map { entry ->
            val lex = lexicalSimilarity(rawQuery, entry)
            // Only apply app/audio tie-breaker boosts when there is genuine semantic/lexical overlap!
            val appBoost = if (lex >= 0.22f && appPackageHint != null && entry.recommendedApps.contains(appPackageHint)) 0.04f else 0f
            val playableBoost = if (lex >= 0.22f && vaultRepository.hasPlayableAudio(entry)) 0.03f else 0f
            MatchCandidate(
                entry = entry,
                score = (lex + appBoost + playableBoost).coerceIn(0f, 0.99f),
                usedNeuralEmbedding = false
            )
        }.sortedByDescending { it.score }.take(topK)
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var normA = 0f
        var normB = 0f
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom <= 1e-8f) 0f else (dot / denom).coerceIn(0f, 1f)
    }

    /**
     * Calibrated Zero-RAM Similarity (`[0f..0.99f]`):
     *  - Unrelated messages with zero token, stem, or synonym overlap return `<= 0.08f` (stays silent!).
     *  - Partial word / synonym match (e.g. `"arrear published"` or `"You failed in exam"` vs `exam_fail`)
     *    scores `0.45f .. 0.95f` (triggers cleanly above `0.25f`–`0.35f` threshold!).
     */
    fun lexicalSimilarity(query: String, entry: DialogueEntry): Float {
        val rawQTokens = tokenize(query)
        val rawDTokens = tokenize(entry.compositeSearchDocument())
        if (rawQTokens.isEmpty() || rawDTokens.isEmpty()) return 0f

        val qExpanded = expandConcepts(rawQTokens)
        val dExpanded = expandConcepts(rawDTokens)

        // 1. Exact + synonym token overlap
        val exactOverlap = qExpanded.intersect(dExpanded).size

        // 2. Partial-word / stem prefix overlap (e.g., "fail" <-> "failed", "arrear" <-> "arrears")
        var stemMatches = 0
        for (qt in rawQTokens) {
            if (qt.length < 4) continue
            val qStem = stemWord(qt)
            val matched = dExpanded.any { dt ->
                dt.length >= 4 && (dt.startsWith(qStem) || qStem.startsWith(stemWord(dt)))
            }
            if (matched) stemMatches++
        }

        // 3. Situation phrase substring check
        val qLower = query.lowercase().trim()
        val phraseHit = entry.situations.any { sit ->
            val s = sit.lowercase().trim()
            s.length >= 4 && (qLower.contains(s) || (qLower.length >= 5 && s.contains(qLower)))
        }

        // STRICT NOISE GATE: if zero tokens, zero stems, and zero phrases matched, stay near 0!
        if (exactOverlap == 0 && stemMatches == 0 && !phraseHit) {
            return 0.04f
        }

        val effectiveQuerySize = rawQTokens.size.coerceAtLeast(1).toFloat()
        val directHitRatio = ((stemMatches.coerceAtLeast(exactOverlap.coerceAtMost(rawQTokens.size))).toFloat() / effectiveQuerySize)
            .coerceIn(0f, 1f)
        val conceptScore = (exactOverlap.toFloat() / qExpanded.size.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
        val categoryHit = if (qExpanded.contains(entry.category.lowercase())) 0.24f else 0f
        val phraseBonus = if (phraseHit) 0.30f else 0f

        val score = (directHitRatio * 0.44f) + (conceptScore * 0.28f) + categoryHit + phraseBonus
        return score.coerceIn(0.26f, 0.98f)
    }

    private fun stemWord(word: String): String {
        return word
            .removeSuffix("ing")
            .removeSuffix("ed")
            .removeSuffix("es")
            .removeSuffix("s")
            .let { if (it.length >= 3) it else word }
    }

    private fun tokenize(text: String): Set<String> {
        return text.lowercase()
            .split(Regex("[^\\p{L}\\p{M}\\p{N}%]+"))
            .filter { it.length >= 2 && it !in STOP_WORDS }
            .toSet()
    }

    private fun expandConcepts(tokens: Set<String>): Set<String> {
        val expanded = tokens.toMutableSet()
        val stems = tokens.map { stemWord(it) }.toSet()
        for ((canonical, aliases) in CONCEPT_SYNONYMS) {
            if (
                tokens.contains(canonical) ||
                tokens.any { it in aliases } ||
                stems.any { s -> s.length >= 4 && aliases.any { a -> a.startsWith(s) || s.startsWith(a) } }
            ) {
                expanded.add(canonical)
                expanded.addAll(aliases)
            }
        }
        return expanded
    }

    companion object {
        const val MIN_SAFE_THRESHOLD = 0.25f

        private val STOP_WORDS = setOf(
            "the", "and", "for", "you", "your", "are", "was", "have", "has", "with", "from",
            "this", "that", "in", "on", "to", "of", "is", "it", "at", "an", "be", "my", "me",
            "dear", "student", "sir", "madam", "regards", "please", "mail", "message"
        )

        private val CONCEPT_SYNONYMS: Map<String, Set<String>> = mapOf(
            "exam_fail" to setOf(
                "exam", "failed", "fail", "failure", "arrear", "arrears", "supply", "suppli",
                "supplementary", "backlog", "marks", "result", "results", "potti", "mark",
                "grade", "revaluation", "yodha", "പരീക്ഷ", "തോറ്റു", "സപ്ലി", "മാർക്ക്", "റിസൾട്ട്"
            ),
            "money" to setOf(
                "salary", "credited", "debited", "bank", "upi", "paisa", "cash", "kasu", "kash",
                "balance", "broke", "bill", "payment", "neft", "imps", "gpay", "പൈസ", "കാശു", "ശമ്പളം", "ബാങ്ക്"
            ),
            "alarm" to setOf(
                "wake", "wakeup", "morning", "sleep", "ezhunelkk", "ezhunnelkku", "alarm", "snooze",
                "എഴുന്നേൽക്ക്", "ഉറക്കം", "അലാറം", "അലാം"
            ),
            "low_battery" to setOf(
                "battery", "charge", "charger", "power", "dying", "15%", "10%", "5%", "low",
                "ബാറ്ററി", "ചാർജ്"
            ),
            "romance" to setOf(
                "love", "crush", "miss", "heart", "date", "premam", "ishtam", "babe", "darling",
                "പ്രേമം", "ഇഷ്ടം", "സ്നേഹം"
            ),
            "scolding" to setOf(
                "late", "lazy", "scold", "angry", "mistake", "blunder", "pani", "vazhakku", "idi",
                "വഴക്ക്", "പണി"
            ),
            "greeting" to setOf(
                "hello", "welcome", "namaskaram", "calling", "hey", "machane", "eda",
                "നമസ്കാരം", "ഹലോ", "സ്വാഗതം"
            ),
            "celebration" to setOf(
                "won", "passed", "success", "congrats", "congratulations", "party", "poli", "adichu",
                "win", "promoted", "ജയിച്ചു", "പൊളി", "വിജയം"
            ),
            "warning" to setOf(
                "danger", "alert", "urgent", "careful", "deadline", "warning", "sradhikkuka",
                "സൂക്ഷിക്കുക", "അറിയിപ്പ്"
            )
        )
    }
}
