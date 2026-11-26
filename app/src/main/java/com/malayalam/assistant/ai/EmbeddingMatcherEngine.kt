package com.malayalam.assistant.ai

import android.content.Context
import com.malayalam.assistant.data.DialogueEntry
import com.malayalam.assistant.data.MatchCandidate
import com.malayalam.assistant.data.SafVaultRepository
import kotlin.math.sqrt

/**
 * Background & Vault Matcher:
 *  - Primary: `EmbeddingGemma 2 Text (270M)` via LiteRT-LM (`task: search query | text: ...` &
 *    `task: search result | text: ...`), strictly coordinated through `ModelCoordinator`.
 *  - Fallback (before model download): Instant ultra-lightweight TF-IDF + character 3-gram +
 *    English/Manglish semantic synonym expander (`~0 MB` extra RAM).
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
        topK: Int = 3
    ): List<MatchCandidate> {
        val dialogues = vaultRepository.dialogues.value
        if (dialogues.isEmpty() || rawQuery.isBlank()) return emptyList()

        // Use neural EmbeddingGemma 2 (270M) whenever installed
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
                        val baseSim = if (docVec != null && docVec.size == queryVec.size) {
                            cosineSimilarity(queryVec, docVec)
                        } else {
                            lexicalSimilarity(rawQuery, entry)
                        }
                        val appBoost = if (appPackageHint != null && entry.recommendedApps.contains(appPackageHint)) 0.05f else 0f
                        val lexicalTieBreak = lexicalSimilarity(rawQuery, entry) * 0.15f
                        val blended = (baseSim * 0.85f + lexicalTieBreak + appBoost).coerceIn(0f, 1f)
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

        // Instant lightweight TF-IDF + Manglish/English synonym fallback before download
        return dialogues.map { entry ->
            val base = lexicalSimilarity(rawQuery, entry)
            val boost = if (appPackageHint != null && entry.recommendedApps.contains(appPackageHint)) 0.06f else 0f
            MatchCandidate(
                entry = entry,
                score = (base + boost).coerceIn(0f, 1f),
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
        return if (denom <= 1e-8f) 0f else ((dot / denom) + 1f) / 2f * 0.35f + (dot / denom).coerceAtLeast(0f) * 0.65f
    }

    /**
     * Fast zero-allocation-heavy TF-IDF / BM25-inspired + 3-gram + Manglish concept similarity
     * calibrated to `[0f..1f]` so a strong match scores `>= 0.68f` and unrelated noise `< 0.35f`.
     */
    fun lexicalSimilarity(query: String, entry: DialogueEntry): Float {
        val qTokens = expandConcepts(tokenize(query))
        val dTokens = expandConcepts(tokenize(entry.compositeSearchDocument()))
        if (qTokens.isEmpty() || dTokens.isEmpty()) return 0f

        val overlap = qTokens.intersect(dTokens).size.toFloat()
        val recall = overlap / qTokens.size.toFloat()
        val jaccard = overlap / (qTokens.union(dTokens).size.toFloat())

        // Phrase / situation exact-substring bonus
        val qLower = query.lowercase()
        val phraseHit = entry.situations.any { sit ->
            val s = sit.lowercase().trim()
            s.length >= 4 && (qLower.contains(s) || s.contains(qLower))
        }

        // Character 3-gram cosine for Manglish spelling variations (e.g. "potti" vs "potty")
        val qGrams = charTrigrams(qLower)
        val dGrams = charTrigrams(entry.compositeSearchDocument().lowercase())
        val gramOverlap = if (qGrams.isNotEmpty() && dGrams.isNotEmpty()) {
            qGrams.intersect(dGrams).size.toFloat() / sqrt((qGrams.size * dGrams.size).toFloat())
        } else 0f

        val raw = (recall * 0.58f) + (jaccard * 0.22f) + (gramOverlap * 0.28f) + (if (phraseHit) 0.22f else 0f)
        return raw.coerceIn(0f, 0.99f)
    }

    private fun tokenize(text: String): Set<String> {
        return text.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 2 && it !in STOP_WORDS }
            .toSet()
    }

    private fun charTrigrams(text: String): Set<String> {
        val compact = text.replace(Regex("\\s+"), " ").trim()
        if (compact.length < 3) return emptySet()
        val out = HashSet<String>(compact.length)
        for (i in 0..compact.length - 3) {
            out.add(compact.substring(i, i + 3))
        }
        return out
    }

    private fun expandConcepts(tokens: Set<String>): Set<String> {
        val expanded = tokens.toMutableSet()
        for ((canonical, aliases) in CONCEPT_SYNONYMS) {
            if (tokens.contains(canonical) || tokens.any { it in aliases }) {
                expanded.add(canonical)
                expanded.addAll(aliases)
            }
        }
        return expanded
    }

    companion object {
        private val STOP_WORDS = setOf(
            "the", "and", "for", "you", "your", "are", "was", "have", "has", "with", "from", "this", "that", "in", "on", "to", "of"
        )

        private val CONCEPT_SYNONYMS: Map<String, Set<String>> = mapOf(
            "exam_fail" to setOf("exam", "failed", "fail", "arrears", "supply", "suppli", "marks", "result", "potti", "mark", "grade"),
            "money" to setOf("salary", "credited", "debited", "bank", "upi", "paisa", "cash", "kasu", "kash", "balance", "broke", "bill", "payment"),
            "alarm" to setOf("wake", "wakeup", "morning", "sleep", "ezhunelkk", "ezhunnelkku", "alarm", "snooze"),
            "low_battery" to setOf("battery", "charge", "charger", "power", "dying", "15%", "10%", "5%", "low"),
            "romance" to setOf("love", "crush", "miss", "heart", "date", "premam", "ishtam", "babe", "darling"),
            "scolding" to setOf("late", "lazy", "scold", "angry", "mistake", "blunder", "pani", "vazhakku", "idi"),
            "greeting" to setOf("hello", "welcome", "namaskaram", "morning", "calling", "hey", "machane", "eda"),
            "celebration" to setOf("won", "passed", "success", "congrats", "congratulations", "party", "poli", "adichu", "win", "promoted"),
            "warning" to setOf("danger", "alert", "urgent", "careful", "deadline", "warning", "sradhikkuka")
        )
    }
}
