package com.malayalam.assistant.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
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
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Manages swappable, importable, and exportable Actor Voice Packs (`filesDir/packs/<packId>/`):
 *  - Migrates existing `SafVaultRepository` dialogues seamlessly into `"default_custom"` (`My Custom Pack`)
 *    so zero previously linked MP3s are ever lost.
 *  - Supports switching to any single pack OR `"__ALL_PACKS__"` (`All Packs Combined`).
 *  - Imports standard `.zip` / `.mlpack` archives via `java.util.zip.ZipInputStream` (zero extra deps!)
 *    and exports any pack into a portable `.mlpack.zip`.
 */
class VoicePackRepository(
    private val context: Context,
    private val prefs: AppPreferences,
    private val legacyVault: SafVaultRepository
) {
    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val packsRootDir = File(context.filesDir, "packs").apply { mkdirs() }

    private val _packs = MutableStateFlow<List<VoicePack>>(emptyList())
    val packs: StateFlow<List<VoicePack>> = _packs.asStateFlow()

    private val _activePackId = MutableStateFlow(VoicePack.DEFAULT_PACK_ID)
    val activePackId: StateFlow<String> = _activePackId.asStateFlow()

    private val _activeDialogues = MutableStateFlow<List<DialogueEntry>>(emptyList())
    val activeDialogues: StateFlow<List<DialogueEntry>> = _activeDialogues.asStateFlow()

    suspend fun initialize() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val savedActive = prefs.snapshot().activePackId
            val loaded = loadAllPacksFromDisk()

            val finalPacks = if (loaded.none { it.packId == VoicePack.DEFAULT_PACK_ID }) {
                // Migrate existing legacy dialogues into "My Custom Pack" so nothing is lost!
                val migratedDialogues = legacyVault.dialogues.value
                val defaultPack = VoicePack(
                    packId = VoicePack.DEFAULT_PACK_ID,
                    packName = "My Custom Pack",
                    actor = "Custom",
                    ttsVoice = prefs.snapshot().geminiVoiceName,
                    personaInstruction = "Warm, witty, natural Malayalam friend style.",
                    dialogues = migratedDialogues
                )
                writePackJsonUnlocked(defaultPack)
                listOf(defaultPack) + loaded
            } else {
                loaded
            }

            _packs.value = finalPacks
            val validActive = if (savedActive == VoicePack.ALL_PACKS_ID || finalPacks.any { it.packId == savedActive }) {
                savedActive
            } else {
                VoicePack.DEFAULT_PACK_ID
            }
            _activePackId.value = validActive
            refreshActiveDialoguesUnlocked()
        }
    }

    fun getActivePackMeta(): VoicePack {
        val id = _activePackId.value
        val all = _packs.value
        if (id == VoicePack.ALL_PACKS_ID) {
            return VoicePack(
                packId = VoicePack.ALL_PACKS_ID,
                packName = "All Packs Combined",
                actor = "All Actors",
                ttsVoice = all.firstOrNull()?.ttsVoice ?: "Kore",
                personaInstruction = "Warm, witty, dramatic Malayalam cinema style.",
                dialogues = all.flatMap { it.dialogues }
            )
        }
        return all.firstOrNull { it.packId == id }
            ?: all.firstOrNull()
            ?: VoicePack(packId = VoicePack.DEFAULT_PACK_ID, packName = "My Custom Pack")
    }

    fun getTargetPackIdForEditing(): String {
        val cur = _activePackId.value
        return if (cur == VoicePack.ALL_PACKS_ID) VoicePack.DEFAULT_PACK_ID else cur
    }

    suspend fun selectActivePack(packId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            _activePackId.value = packId
            prefs.setActivePackId(packId)
            val chosenPack = _packs.value.firstOrNull { it.packId == packId }
            if (chosenPack != null && chosenPack.ttsVoice.isNotBlank()) {
                prefs.setGeminiVoiceName(chosenPack.ttsVoice)
            }
            refreshActiveDialoguesUnlocked()
        }
    }

    suspend fun createPack(
        packName: String,
        actor: String,
        ttsVoice: String,
        personaInstruction: String
    ): VoicePack = withContext(Dispatchers.IO) {
        mutex.withLock {
            val slug = packName.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(24)
            val id = "${slug.ifBlank { "pack" }}_${UUID.randomUUID().toString().take(6)}"
            val created = VoicePack(
                packId = id,
                packName = packName.trim().ifBlank { "New Voice Pack" },
                actor = actor.trim().ifBlank { "Custom" },
                ttsVoice = ttsVoice.ifBlank { "Kore" },
                personaInstruction = personaInstruction.trim()
                    .ifBlank { "Warm, witty, natural Malayalam conversational style." },
                dialogues = emptyList()
            )
            packAudioDir(id).mkdirs()
            writePackJsonUnlocked(created)
            _packs.value = _packs.value + created
            _activePackId.value = id
            prefs.setActivePackId(id)
            prefs.setGeminiVoiceName(created.ttsVoice)
            refreshActiveDialoguesUnlocked()
            created
        }
    }

    suspend fun deletePack(packId: String) = withContext(Dispatchers.IO) {
        if (packId == VoicePack.DEFAULT_PACK_ID) return@withContext
        mutex.withLock {
            packDir(packId).deleteRecursively()
            _packs.value = _packs.value.filterNot { it.packId == packId }
            if (_activePackId.value == packId) {
                _activePackId.value = VoicePack.DEFAULT_PACK_ID
                prefs.setActivePackId(VoicePack.DEFAULT_PACK_ID)
            }
            refreshActiveDialoguesUnlocked()
        }
    }

    suspend fun upsertDialogueInPack(packId: String, entry: DialogueEntry) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val resolvedPackId = if (packId == VoicePack.ALL_PACKS_ID) VoicePack.DEFAULT_PACK_ID else packId
            val updatedPacks = _packs.value.map { pack ->
                if (pack.packId != resolvedPackId) pack
                else {
                    val list = pack.dialogues.toMutableList()
                    val idx = list.indexOfFirst { it.id == entry.id }
                    if (idx >= 0) list[idx] = entry else list.add(0, entry)
                    val newPack = pack.copy(dialogues = list, updatedAtEpochMs = System.currentTimeMillis())
                    writePackJsonUnlocked(newPack)
                    newPack
                }
            }
            _packs.value = updatedPacks
            refreshActiveDialoguesUnlocked()

            if (resolvedPackId == VoicePack.DEFAULT_PACK_ID) {
                legacyVault.upsertDialogue(entry, null)
            }
        }
    }

    suspend fun deleteDialogueFromActivePack(dialogueId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updatedPacks = _packs.value.map { pack ->
                if (pack.dialogues.none { it.id == dialogueId }) pack
                else {
                    val newPack = pack.copy(
                        dialogues = pack.dialogues.filterNot { it.id == dialogueId },
                        updatedAtEpochMs = System.currentTimeMillis()
                    )
                    writePackJsonUnlocked(newPack)
                    newPack
                }
            }
            _packs.value = updatedPacks
            refreshActiveDialoguesUnlocked()
            legacyVault.deleteDialogue(dialogueId)
        }
    }

    suspend fun copyAudioIntoPack(packId: String, sourceUri: Uri): Pair<String, String>? =
        withContext(Dispatchers.IO) {
            val resolvedPackId = if (packId == VoicePack.ALL_PACKS_ID) VoicePack.DEFAULT_PACK_ID else packId
            val rawName = queryDisplayName(sourceUri) ?: "clip_${System.currentTimeMillis()}.mp3"
            val safeName = sanitizeAudioName(rawName)
            val dest = File(packAudioDir(resolvedPackId), safeName)
            dest.parentFile?.mkdirs()

            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 16 * 1024)
                }
            } ?: return@withContext null

            safeName to Uri.fromFile(dest).toString()
        }

    suspend fun attachAudioToExistingDialogue(dialogueId: String, sourceUri: Uri): DialogueEntry? =
        withContext(Dispatchers.IO) {
            val ownerPack = _packs.value.firstOrNull { p -> p.dialogues.any { it.id == dialogueId } }
                ?: return@withContext null
            val copied = copyAudioIntoPack(ownerPack.packId, sourceUri) ?: return@withContext null
            val target = ownerPack.dialogues.first { it.id == dialogueId }
            val updated = target.copy(fileName = copied.first, safUriString = copied.second)
            upsertDialogueInPack(ownerPack.packId, updated)
            updated
        }

    /**
     * Imports a `.zip` or `.mlpack` archive via standard `java.util.zip.ZipInputStream`:
     *  - Extracts `pack.json` and all `.mp3`/`.wav`/`.ogg`/`.m4a` clips safely into `filesDir/packs/<packId>/audio/`.
     *  - If the archive contains only audio clips (no `pack.json`), auto-creates a pack named after the ZIP file
     *    so the user can tap **"✨ Auto-Listen & Tag All Clips with AI"**!
     */
    suspend fun importPackFromZip(sourceUri: Uri): PackImportResult = withContext(Dispatchers.IO) {
        val rawZipName = queryDisplayName(sourceUri) ?: "Imported_Pack.mlpack"
        val cleanBaseName = rawZipName
            .removeSuffix(".mlpack.zip")
            .removeSuffix(".mlpack")
            .removeSuffix(".zip")
            .replace(Regex("[_-]+"), " ")
            .trim()
            .ifBlank { "Imported Voice Pack" }

        val tempId = "import_${UUID.randomUUID().toString().take(8)}"
        val targetDir = packDir(tempId).apply { mkdirs() }
        val audioDir = packAudioDir(tempId).apply { mkdirs() }
        val canonicalRoot = targetDir.canonicalPath

        var manifestJsonText: String? = null
        val extractedAudioFiles = mutableListOf<File>()

        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            ZipInputStream(input).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val entryFileName = File(entry.name).name
                        val lower = entryFileName.lowercase()
                        if (lower == "pack.json") {
                            manifestJsonText = zis.readBytes().toString(Charsets.UTF_8)
                        } else if (SUPPORTED_EXTS.any { lower.endsWith(it) }) {
                            val safeName = sanitizeAudioName(entryFileName)
                            val outFile = File(audioDir, safeName)
                            if (outFile.canonicalPath.startsWith(canonicalRoot)) {
                                outFile.outputStream().use { out -> zis.copyTo(out, bufferSize = 16 * 1024) }
                                extractedAudioFiles.add(outFile)
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } ?: error("Cannot open selected pack archive.")

        val parsedManifest = manifestJsonText?.let { raw ->
            runCatching { json.decodeFromString<VoicePack>(raw) }.getOrNull()
        }

        val audioByName = extractedAudioFiles.associateBy { it.name.lowercase() }
        val finalPack: VoicePack
        val missingManifest: Boolean

        if (parsedManifest != null) {
            missingManifest = false
            val linkedDialogues = parsedManifest.dialogues.map { d ->
                val matchedFile = audioByName[d.fileName.lowercase()]
                    ?: audioByName[sanitizeAudioName(d.fileName).lowercase()]
                if (matchedFile != null) {
                    d.copy(fileName = matchedFile.name, safUriString = Uri.fromFile(matchedFile).toString())
                } else d
            }
            finalPack = parsedManifest.copy(
                packId = tempId,
                packName = parsedManifest.packName.ifBlank { cleanBaseName },
                dialogues = linkedDialogues,
                updatedAtEpochMs = System.currentTimeMillis()
            )
        } else {
            missingManifest = true
            val rawDialogues = extractedAudioFiles.map { file ->
                val prettyTitle = file.nameWithoutExtension.replace(Regex("[_-]+"), " ").trim()
                DialogueEntry(
                    id = UUID.randomUUID().toString(),
                    fileName = file.name,
                    safUriString = Uri.fromFile(file).toString(),
                    title = prettyTitle.ifBlank { file.name },
                    heardText = "",
                    category = SituationCategory.CUSTOM.id,
                    situations = listOf(prettyTitle),
                    scope = "specific"
                )
            }
            finalPack = VoicePack(
                packId = tempId,
                packName = cleanBaseName,
                actor = cleanBaseName,
                ttsVoice = prefs.snapshot().geminiVoiceName,
                personaInstruction = "Witty Malayalam style inspired by $cleanBaseName.",
                dialogues = rawDialogues
            )
        }

        mutex.withLock {
            writePackJsonUnlocked(finalPack)
            _packs.value = _packs.value + finalPack
            _activePackId.value = finalPack.packId
            prefs.setActivePackId(finalPack.packId)
            prefs.setGeminiVoiceName(finalPack.ttsVoice)
            refreshActiveDialoguesUnlocked()
        }

        PackImportResult(
            pack = finalPack,
            importedAudioCount = extractedAudioFiles.size,
            missingManifestAutoCreated = missingManifest
        )
    }

    /**
     * Exports a Voice Pack (`pack.json` + all linked audio files) as a single portable `.mlpack.zip`.
     */
    suspend fun exportPackToZip(packId: String, destUri: Uri): Int = withContext(Dispatchers.IO) {
        val pack = _packs.value.firstOrNull { it.packId == packId }
            ?: error("Voice pack not found")
        var audioCount = 0

        context.contentResolver.openOutputStream(destUri, "wt")?.use { outStream ->
            ZipOutputStream(outStream).use { zos ->
                // 1. Write portable pack.json
                val portableDialogues = pack.dialogues.map { it.copy(safUriString = "") }
                val manifestBytes = json.encodeToString(pack.copy(dialogues = portableDialogues))
                    .toByteArray(Charsets.UTF_8)
                zos.putNextEntry(ZipEntry("pack.json"))
                zos.write(manifestBytes)
                zos.closeEntry()

                // 2. Write each linked audio clip into audio/
                val addedNames = mutableSetOf<String>()
                for (entry in pack.dialogues) {
                    if (entry.safUriString.isBlank()) continue
                    val fileName = sanitizeAudioName(entry.fileName.ifBlank { "${entry.id}.mp3" })
                    if (!addedNames.add(fileName.lowercase())) continue
                    val uri = runCatching { Uri.parse(entry.safUriString) }.getOrNull() ?: continue
                    val input = runCatching {
                        if (uri.scheme == "file") File(uri.path ?: return@runCatching null).inputStream()
                        else context.contentResolver.openInputStream(uri)
                    }.getOrNull() ?: continue

                    input.use { stream ->
                        zos.putNextEntry(ZipEntry("audio/$fileName"))
                        stream.copyTo(zos, bufferSize = 16 * 1024)
                        zos.closeEntry()
                        audioCount++
                    }
                }
            }
        } ?: error("Cannot write to selected export destination.")
        audioCount
    }

    fun hasPlayableAudio(entry: DialogueEntry): Boolean = legacyVault.hasPlayableAudio(entry)

    suspend fun readAudioBytesForAi(uriString: String, fileName: String): Pair<ByteArray, String>? =
        legacyVault.readAudioBytesForAi(uriString, fileName)

    private fun packDir(packId: String): File = File(packsRootDir, packId)
    private fun packAudioDir(packId: String): File = File(packDir(packId), "audio")

    private fun writePackJsonUnlocked(pack: VoicePack) {
        val dir = packDir(pack.packId).apply { mkdirs() }
        File(dir, "pack.json").writeText(json.encodeToString(pack))
    }

    private fun loadAllPacksFromDisk(): List<VoicePack> {
        val dirs = packsRootDir.listFiles()?.filter { it.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            val manifest = File(dir, "pack.json")
            if (!manifest.exists()) return@mapNotNull null
            runCatching { json.decodeFromString<VoicePack>(manifest.readText()) }.getOrNull()
        }.sortedBy { if (it.packId == VoicePack.DEFAULT_PACK_ID) 0 else 1 }
    }

    private fun refreshActiveDialoguesUnlocked() {
        val id = _activePackId.value
        _activeDialogues.value = if (id == VoicePack.ALL_PACKS_ID) {
            _packs.value.flatMap { it.dialogues }
        } else {
            _packs.value.firstOrNull { it.packId == id }?.dialogues.orEmpty()
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun sanitizeAudioName(raw: String): String {
        val cleaned = raw.replace(Regex("[^a-zA-Z0-9._ -]"), "_").trim()
        return if (SUPPORTED_EXTS.any { cleaned.lowercase().endsWith(it) }) cleaned else "$cleaned.mp3"
    }

    companion object {
        private val SUPPORTED_EXTS = listOf(".mp3", ".wav", ".ogg", ".m4a")
    }
}
