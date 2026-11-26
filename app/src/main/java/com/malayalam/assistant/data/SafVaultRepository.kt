package com.malayalam.assistant.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Manages the user's Malayalam Dialogue Vault:
 *  - Supports immediate 1-tap MP3/WAV/OGG linking per dialogue card EVEN BEFORE setting up a full SAF folder
 *    (storing clips safely in app-external `vault_clips/` with `.nomedia`).
 *  - Also supports dedicated persistent SAF folder (`Documents/MalayalamAssistant/`) so clips & `dialogues.json`
 *    survive APK reinstalls.
 */
class SafVaultRepository(
    private val context: Context,
    private val prefs: AppPreferences
) {
    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val _dialogues = MutableStateFlow<List<DialogueEntry>>(emptyList())
    val dialogues: StateFlow<List<DialogueEntry>> = _dialogues.asStateFlow()

    private val _vectors = MutableStateFlow<Map<String, FloatArray>>(emptyMap())
    val vectors: StateFlow<Map<String, FloatArray>> = _vectors.asStateFlow()

    private val fallbackVaultFile = File(context.filesDir, DIALOGUES_JSON_NAME)
    private val fallbackVectorFile = File(context.filesDir, EMBEDDINGS_JSON_NAME)

    suspend fun initializeFromDisk() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val treeUriStr = prefs.snapshot().safTreeUri
            val treeDoc = resolveTreeDoc(treeUriStr)
            if (treeDoc != null) {
                ensureNoMedia(treeDoc)
                loadFromSafOrFallback(treeDoc)
            } else {
                loadFromInternalFallback()
            }

            // Seed helpful starter templates on first launch so user can 1-tap attach MP3s right away
            if (_dialogues.value.isEmpty() && !fallbackVaultFile.exists()) {
                _dialogues.value = defaultStarterTemplates()
                persistUnlocked()
            }
        }
    }

    /**
     * Checks whether `entry` has an attached, readable audio file (either SAF `content://` or local `file://`).
     */
    fun hasPlayableAudio(entry: DialogueEntry): Boolean {
        val raw = entry.safUriString.trim()
        if (raw.isBlank()) return false
        return runCatching {
            val uri = Uri.parse(raw)
            when (uri.scheme) {
                "file" -> File(uri.path ?: return false).let { it.exists() && it.length() > 128L }
                "content" -> {
                    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                        afd.length != 0L
                    } ?: false
                }
                else -> false
            }
        }.getOrDefault(false)
    }

    suspend fun bindSafFolder(treeUri: Uri): Int = withContext(Dispatchers.IO) {
        val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching {
            context.contentResolver.takePersistableUriPermission(treeUri, takeFlags)
        }
        prefs.setSafTreeUri(treeUri.toString())

        mutex.withLock {
            val treeDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return@withLock 0
            ensureNoMedia(treeDoc)
            loadFromSafOrFallback(treeDoc)
            _dialogues.value.size
        }
    }

    /**
     * 1-tap links an `.mp3` / `.wav` / `.ogg` file directly to an existing `DialogueEntry`
     * (works with or without a SAF folder configured).
     */
    suspend fun attachAudioToDialogue(dialogueId: String, sourceUri: Uri): DialogueEntry? =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    sourceUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val copied = copyExternalClipIntoVault(sourceUri) ?: return@withContext null
            mutex.withLock {
                val list = _dialogues.value.toMutableList()
                val idx = list.indexOfFirst { it.id == dialogueId }
                if (idx < 0) return@withLock null
                val updated = list[idx].copy(
                    fileName = copied.first,
                    safUriString = copied.second
                )
                list[idx] = updated
                _dialogues.value = list
                persistUnlocked()
                updated
            }
        }

    /**
     * Copies an externally picked `.mp3`/`.wav`/`.ogg`/`.m4a` file into the SAF folder
     * (or internal `vault_clips/` dir if SAF isn't configured yet) and returns `(fileName, uriString)`.
     */
    suspend fun copyExternalClipIntoVault(sourceUri: Uri): Pair<String, String>? = withContext(Dispatchers.IO) {
        val rawName = queryDisplayName(sourceUri) ?: "clip_${System.currentTimeMillis()}.mp3"
        val safeName = sanitizeAudioFileName(rawName)
        val mime = guessAudioMime(safeName)

        val treeUriStr = prefs.snapshot().safTreeUri
        val treeDoc = resolveTreeDoc(treeUriStr)
        if (treeDoc != null) {
            ensureNoMedia(treeDoc)
            val existing = treeDoc.findFile(safeName)
            val targetDoc = existing ?: treeDoc.createFile(mime, safeName) ?: return@withContext null
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                context.contentResolver.openOutputStream(targetDoc.uri, "wt")?.use { output ->
                    input.copyTo(output, bufferSize = 16 * 1024)
                }
            } ?: return@withContext null
            return@withContext safeName to targetDoc.uri.toString()
        } else {
            val clipsDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "vault_clips").apply { mkdirs() }
            File(clipsDir, ".nomedia").apply { if (!exists()) createNewFile() }
            val dest = File(clipsDir, safeName)
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 16 * 1024)
                }
            } ?: return@withContext null
            return@withContext safeName to Uri.fromFile(dest).toString()
        }
    }

    suspend fun findUnorganizedAudioFiles(pendingFileNames: Set<String>): List<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            val treeDoc = resolveTreeDoc(prefs.snapshot().safTreeUri) ?: return@withContext emptyList()
            ensureNoMedia(treeDoc)
            val indexedNames = _dialogues.value.map { it.fileName.lowercase() }.toSet()
            val pendingLower = pendingFileNames.map { it.lowercase() }.toSet()

            treeDoc.listFiles()
                .filter { doc ->
                    doc.isFile && doc.name != null &&
                        SUPPORTED_AUDIO_EXTENSIONS.any { ext -> doc.name!!.lowercase().endsWith(ext) } &&
                        doc.name!!.lowercase() !in indexedNames &&
                        doc.name!!.lowercase() !in pendingLower
                }
                .map { doc -> doc.name!! to doc.uri.toString() }
        }

    suspend fun upsertDialogue(
        entry: DialogueEntry,
        embedding: FloatArray?
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updatedList = _dialogues.value.toMutableList()
            val idx = updatedList.indexOfFirst {
                it.id == entry.id ||
                    (entry.fileName.isNotBlank() && it.fileName.equals(entry.fileName, ignoreCase = true))
            }
            val finalEntry = if (idx >= 0) {
                val existingId = updatedList[idx].id
                entry.copy(id = existingId)
            } else {
                entry.copy(id = entry.id.ifBlank { UUID.randomUUID().toString() })
            }
            if (idx >= 0) updatedList[idx] = finalEntry else updatedList.add(0, finalEntry)
            _dialogues.value = updatedList

            if (embedding != null && embedding.isNotEmpty()) {
                val updatedMap = _vectors.value.toMutableMap()
                updatedMap[finalEntry.id] = embedding
                _vectors.value = updatedMap
            }
            persistUnlocked()
        }
    }

    suspend fun updateAllEmbeddings(newMap: Map<String, FloatArray>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            _vectors.value = newMap
            persistUnlocked()
        }
    }

    suspend fun deleteDialogue(id: String, deleteUnderlyingFile: Boolean = false) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val target = _dialogues.value.firstOrNull { it.id == id }
            _dialogues.value = _dialogues.value.filterNot { it.id == id }
            val updatedVectors = _vectors.value.toMutableMap()
            updatedVectors.remove(id)
            _vectors.value = updatedVectors
            persistUnlocked()

            if (deleteUnderlyingFile && target != null && target.safUriString.isNotBlank()) {
                runCatching {
                    val uri = Uri.parse(target.safUriString)
                    DocumentFile.fromSingleUri(context, uri)?.delete()
                }
            }
        }
    }

    private fun defaultStarterTemplates(): List<DialogueEntry> = listOf(
        DialogueEntry(
            id = "starter_exam_fail",
            fileName = "",
            safUriString = "",
            title = "Exam Fail / Arrear Reaction (e.g. Yodha)",
            heardText = "പരീക്ഷ തോറ്റു / സപ്ലി കിട്ടി",
            category = SituationCategory.EXAM_FAIL.id,
            situations = listOf(
                "You failed in your exam",
                "exam fail",
                "arrear",
                "supply",
                "low marks result",
                "potti",
                "പരീക്ഷ തോറ്റു"
            ),
            recommendedApps = listOf("com.google.android.gm", "com.whatsapp")
        ),
        DialogueEntry(
            id = "starter_money_salary",
            fileName = "",
            safUriString = "",
            title = "Salary / Bank Money Credited",
            heardText = "പൈസ വന്നു / അക്കൗണ്ടിൽ കാശ് കയറി",
            category = SituationCategory.MONEY.id,
            situations = listOf(
                "salary credited",
                "bank account credited",
                "upi payment received",
                "paisa vannu",
                "ശമ്പളം കിട്ടി"
            ),
            recommendedApps = listOf("com.google.android.gm", "com.google.android.apps.messaging")
        ),
        DialogueEntry(
            id = "starter_low_battery",
            fileName = "",
            safUriString = "",
            title = "Low Battery Warning Clip",
            heardText = "ചാർജ് തീരാറായി വേഗം ചാർജിലിട്",
            category = SituationCategory.LOW_BATTERY.id,
            situations = listOf(
                "battery low 15%",
                "connect charger",
                "phone dying",
                "charge theernnu",
                "ബാറ്ററി കുറവാണ്"
            ),
            recommendedApps = emptyList()
        )
    )

    private fun ensureNoMedia(treeDoc: DocumentFile) {
        if (treeDoc.findFile(".nomedia") == null) {
            runCatching { treeDoc.createFile("application/octet-stream", ".nomedia") }
        }
    }

    private fun resolveTreeDoc(treeUriStr: String): DocumentFile? {
        if (treeUriStr.isBlank()) return null
        return runCatching {
            val uri = Uri.parse(treeUriStr)
            DocumentFile.fromTreeUri(context, uri)?.takeIf { it.canRead() }
        }.getOrNull()
    }

    private suspend fun loadFromSafOrFallback(treeDoc: DocumentFile) {
        val dialoguesDoc = treeDoc.findFile(DIALOGUES_JSON_NAME)
        if (dialoguesDoc != null) {
            val raw = readDocText(dialoguesDoc.uri)
            if (!raw.isNullOrBlank()) {
                runCatching {
                    val parsed = json.decodeFromString<DialogueVaultFile>(raw)
                    _dialogues.value = relinkSafUris(treeDoc, parsed.dialogues)
                }
            }
        } else {
            loadFromInternalFallback()
            if (_dialogues.value.isNotEmpty()) {
                persistUnlocked()
            }
        }

        val vecDoc = treeDoc.findFile(EMBEDDINGS_JSON_NAME)
        if (vecDoc != null) {
            val rawVec = readDocText(vecDoc.uri)
            if (!rawVec.isNullOrBlank()) {
                runCatching {
                    val parsed = json.decodeFromString<EmbeddingsCacheFile>(rawVec)
                    _vectors.value = parsed.vectors.mapValues { it.value.toFloatArray() }
                }
            }
        }
    }

    private fun relinkSafUris(treeDoc: DocumentFile, entries: List<DialogueEntry>): List<DialogueEntry> {
        val filesByName = treeDoc.listFiles()
            .filter { it.isFile && it.name != null }
            .associateBy { it.name!!.lowercase() }
        return entries.map { entry ->
            if (entry.fileName.isBlank()) {
                entry
            } else {
                val matchedDoc = filesByName[entry.fileName.lowercase()]
                if (matchedDoc != null) {
                    entry.copy(safUriString = matchedDoc.uri.toString())
                } else {
                    entry
                }
            }
        }
    }

    private fun loadFromInternalFallback() {
        if (fallbackVaultFile.exists()) {
            runCatching {
                val parsed = json.decodeFromString<DialogueVaultFile>(fallbackVaultFile.readText())
                _dialogues.value = parsed.dialogues
            }
        }
        if (fallbackVectorFile.exists()) {
            runCatching {
                val parsed = json.decodeFromString<EmbeddingsCacheFile>(fallbackVectorFile.readText())
                _vectors.value = parsed.vectors.mapValues { it.value.toFloatArray() }
            }
        }
    }

    private suspend fun persistUnlocked() {
        val vaultPayload = json.encodeToString(
            DialogueVaultFile(
                version = 1,
                updatedAtEpochMs = System.currentTimeMillis(),
                dialogues = _dialogues.value
            )
        )
        val vectorPayload = json.encodeToString(
            EmbeddingsCacheFile(
                modelTag = "embeddinggemma-2-text-270m",
                vectors = _vectors.value.mapValues { it.value.toList() }
            )
        )

        runCatching { fallbackVaultFile.writeText(vaultPayload) }
        runCatching { fallbackVectorFile.writeText(vectorPayload) }

        val treeDoc = resolveTreeDoc(prefs.snapshot().safTreeUri)
        if (treeDoc != null && treeDoc.canWrite()) {
            ensureNoMedia(treeDoc)
            writeDocText(treeDoc, DIALOGUES_JSON_NAME, vaultPayload)
            writeDocText(treeDoc, EMBEDDINGS_JSON_NAME, vectorPayload)
        }
    }

    private fun readDocText(uri: Uri): String? {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
    }

    private fun writeDocText(treeDoc: DocumentFile, fileName: String, content: String) {
        runCatching {
            val doc = treeDoc.findFile(fileName)
                ?: treeDoc.createFile("application/json", fileName)
                ?: return
            context.contentResolver.openOutputStream(doc.uri, "wt")?.bufferedWriter()?.use {
                it.write(content)
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }

    private fun sanitizeAudioFileName(raw: String): String {
        val cleaned = raw.replace(Regex("[^a-zA-Z0-9._ -]"), "_").trim()
        return if (SUPPORTED_AUDIO_EXTENSIONS.any { cleaned.lowercase().endsWith(it) }) {
            cleaned
        } else {
            "$cleaned.mp3"
        }
    }

    private fun guessAudioMime(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".wav") -> "audio/wav"
            lower.endsWith(".ogg") -> "audio/ogg"
            lower.endsWith(".m4a") -> "audio/mp4"
            else -> "audio/mpeg"
        }
    }

    companion object {
        const val DIALOGUES_JSON_NAME = "dialogues.json"
        const val EMBEDDINGS_JSON_NAME = "embeddings_cache.json"
        val SUPPORTED_AUDIO_EXTENSIONS = listOf(".mp3", ".wav", ".ogg", ".m4a")
    }
}
