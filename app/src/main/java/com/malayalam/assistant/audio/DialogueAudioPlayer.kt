package com.malayalam.assistant.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.speech.tts.TextToSpeech
import com.malayalam.assistant.data.DialogueEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Locale

data class PlaybackState(
    val isPlaying: Boolean = false,
    val activeDialogueId: String? = null,
    val activeTitle: String = "",
    val activeTranscript: String = ""
)

/**
 * Deduplicates automatic background triggers (`NotificationListenerService` + `GmailDirectWatcher`)
 * by `sourceKey + normalizedTextHash` AND `dialogueId` with a strict 60-second cooldown
 * so the same notification/email NEVER loops or repeats twice.
 */
object TriggerDeduplicator {
    private const val COOLDOWN_MS = 60_000L
    private val lock = Any()
    private val recentFingerprints = LinkedHashMap<String, Long>(64, 0.75f, true)
    private var lastAnyPlaybackEpochMs: Long = 0L

    fun shouldAllowAutoTrigger(sourceKey: String, messageBody: String, dialogueId: String): Boolean {
        val now = System.currentTimeMillis()
        val normalizedHash = messageBody.trim().lowercase().replace(Regex("\\s+"), " ").hashCode()
        val eventKey = "$sourceKey:$normalizedHash"
        val clipKey = "clip:$dialogueId:$normalizedHash"

        synchronized(lock) {
            // Prune expired entries older than 10 minutes
            val iter = recentFingerprints.entries.iterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                if (now - entry.value > 10 * 60_000L) iter.remove()
            }

            // Never replay an identical notificationKey + textHash within 10 minutes
            val prevEvent = recentFingerprints[eventKey]
            if (prevEvent != null && now - prevEvent < 10 * 60_000L) {
                return false
            }

            // Enforce 60-second cooldown across auto triggers so clips never loop back-to-back
            val prevClip = recentFingerprints[clipKey]
            if (prevClip != null && now - prevClip < COOLDOWN_MS) {
                return false
            }
            if (now - lastAnyPlaybackEpochMs < 12_000L) {
                return false
            }

            recentFingerprints[eventKey] = now
            recentFingerprints[clipKey] = now
            lastAnyPlaybackEpochMs = now
            return true
        }
    }
}

/**
 * Lightweight singleton `MediaPlayer` + optional Android built-in `TextToSpeech` (`ml-IN` / `en-IN`).
 *  - Explicitly enforces `isLooping = false` so audio clips play once cleanly and stop.
 *  - Exposes `playbackState` so the Center Waveform Orb animates smoothly during playback.
 */
class DialogueAudioPlayer(private val context: Context) {

    private val lock = Any()
    private var mediaPlayer: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    fun playEntry(entry: DialogueEntry): Boolean {
        return playUri(
            uriString = entry.safUriString,
            id = entry.id,
            title = entry.title,
            transcript = entry.heardText
        )
    }

    fun playUri(
        uriString: String,
        id: String,
        title: String,
        transcript: String = ""
    ): Boolean {
        val cleaned = uriString.trim()
        if (cleaned.isBlank()) return false
        synchronized(lock) {
            stopUnlocked()
            return runCatching {
                val uri = Uri.parse(cleaned)
                val player = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    if (uri.scheme == "file") {
                        val file = File(uri.path ?: error("Invalid file path"))
                        if (!file.exists() || file.length() <= 0L) error("Missing audio file")
                        setDataSource(file.absolutePath)
                    } else {
                        setDataSource(context, uri)
                    }
                    isLooping = false
                    setOnCompletionListener { stop() }
                    setOnErrorListener { _, _, _ ->
                        stop()
                        true
                    }
                    prepare()
                    start()
                }
                mediaPlayer = player
                _playbackState.value = PlaybackState(
                    isPlaying = true,
                    activeDialogueId = id,
                    activeTitle = title,
                    activeTranscript = transcript
                )
                true
            }.getOrElse {
                stopUnlocked()
                false
            }
        }
    }

    /**
     * Speaks a short Malayalam or English assistant reply aloud using Android's built-in offline TTS
     * ONLY when no movie dialogue clip is currently playing.
     */
    fun speakShortReplyIfIdle(text: String, preferMalayalam: Boolean = true) {
        if (text.isBlank() || _playbackState.value.isPlaying) return
        val spokenClean = text
            .replace(Regex("[•✅⚠️🔊✦]"), "")
            .lineSequence()
            .firstOrNull()
            ?.trim()
            ?.take(140)
            .orEmpty()
        if (spokenClean.isBlank()) return

        synchronized(lock) {
            if (tts == null) {
                tts = TextToSpeech(context.applicationContext) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        ttsReady = true
                        speakInternal(spokenClean, preferMalayalam)
                    }
                }
            } else if (ttsReady) {
                speakInternal(spokenClean, preferMalayalam)
            }
        }
    }

    private fun speakInternal(text: String, preferMalayalam: Boolean) {
        val engine = tts ?: return
        val mlLocale = Locale("ml", "IN")
        val enLocale = Locale("en", "IN")
        val targetLocale = if (preferMalayalam) mlLocale else enLocale
        val res = engine.setLanguage(targetLocale)
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(enLocale)
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "assistant_reply")
    }

    fun stop() {
        synchronized(lock) {
            stopUnlocked()
        }
    }

    private fun stopUnlocked() {
        runCatching { tts?.stop() }
        runCatching {
            mediaPlayer?.let { mp ->
                if (mp.isPlaying) mp.stop()
                mp.reset()
                mp.release()
            }
        }
        mediaPlayer = null
        _playbackState.value = PlaybackState()
    }
}
