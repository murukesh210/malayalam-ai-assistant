package com.malayalam.assistant.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import com.malayalam.assistant.ai.GeminiTtsClient
import com.malayalam.assistant.data.DialogueEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

data class PlaybackState(
    val isPlaying: Boolean = false,
    val activeDialogueId: String? = null,
    val activeTitle: String = "",
    val activeTranscript: String = ""
)

/**
 * Deduplicates automatic background triggers (`SMS` + `Gmail` + `NotificationListener`)
 * by `sourceKey + normalizedTextHash` AND `dialogueId` with a strict 60-second cooldown
 * so the same notification/SMS/email NEVER loops or repeats twice.
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
            val iter = recentFingerprints.entries.iterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                if (now - entry.value > 10 * 60_000L) iter.remove()
            }

            val prevEvent = recentFingerprints[eventKey]
            if (prevEvent != null && now - prevEvent < 10 * 60_000L) {
                return false
            }

            val prevClip = recentFingerprints[clipKey]
            if (prevClip != null && now - prevClip < COOLDOWN_MS) {
                return false
            }
            if (now - lastAnyPlaybackEpochMs < 8_000L) {
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
 * Singleton Audio Player supporting:
 *  1. `MediaPlayer` for Malayalam movie dialogue MP3/WAV/OGG clips (`isLooping = false`,
 *     strict AudioFocus request/abandon, guaranteed `releaseAndReset()` on completion & error
 *     posting `isPlaying = false` on `Dispatchers.Main` so the orb unlocks automatically).
 *  2. `AudioTrack` (`24000 Hz`, mono, 16-bit PCM) for expressive Google AI Studio Gemini TTS.
 *  3. Offline Android `TextToSpeech` (`ml-IN` / `en-IN`) fallback ONLY when offline / no API key.
 */
class DialogueAudioPlayer(private val context: Context) {

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private var mediaPlayer: MediaPlayer? = null
    private var audioTrack: AudioTrack? = null
    private var pcmPlaybackJob: Job? = null
    private var focusRequest: AudioFocusRequest? = null

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    fun playEntry(entry: DialogueEntry, onFinished: (() -> Unit)? = null): Boolean {
        return playUri(
            uriString = entry.safUriString,
            id = entry.id,
            title = entry.title,
            transcript = entry.heardText,
            onFinished = onFinished
        )
    }

    fun playUri(
        uriString: String,
        id: String,
        title: String,
        transcript: String = "",
        onFinished: (() -> Unit)? = null
    ): Boolean {
        val cleaned = uriString.trim()
        if (cleaned.isBlank()) return false
        synchronized(lock) {
            releaseAndResetUnlocked(invokeFinished = false)
            requestTransientAudioFocus()
            return runCatching {
                val uri = Uri.parse(cleaned)
                val player = MediaPlayer().apply {
                    setAudioAttributes(speechMediaAttributes())
                    if (uri.scheme == "file") {
                        val file = File(uri.path ?: error("Invalid file path"))
                        if (!file.exists() || file.length() <= 0L) error("Missing audio file")
                        setDataSource(file.absolutePath)
                    } else {
                        setDataSource(context, uri)
                    }
                    isLooping = false
                    setOnCompletionListener {
                        releaseAndReset()
                        onFinished?.let { cb -> mainHandler.post(cb) }
                    }
                    setOnErrorListener { _, _, _ ->
                        releaseAndReset()
                        onFinished?.let { cb -> mainHandler.post(cb) }
                        true
                    }
                    prepare()
                    start()
                }
                mediaPlayer = player
                postPlaybackState(
                    PlaybackState(
                        isPlaying = true,
                        activeDialogueId = id,
                        activeTitle = title,
                        activeTranscript = transcript
                    )
                )
                true
            }.getOrElse {
                releaseAndResetUnlocked(invokeFinished = false)
                false
            }
        }
    }

    /**
     * Streams decoded 24 kHz 16-bit mono PCM returned by `GeminiTtsClient` directly into `AudioTrack`,
     * animating the center orb while speaking and calling `releaseAndReset()` automatically on finish.
     */
    fun playPcm24kMono(
        pcmBytes: ByteArray,
        displayTitle: String,
        onFinished: (() -> Unit)? = null
    ) {
        if (pcmBytes.isEmpty()) {
            onFinished?.let { mainHandler.post(it) }
            return
        }
        synchronized(lock) {
            releaseAndResetUnlocked(invokeFinished = false)
            requestTransientAudioFocus()
            postPlaybackState(
                PlaybackState(
                    isPlaying = true,
                    activeDialogueId = "gemini_tts",
                    activeTitle = displayTitle,
                    activeTranscript = ""
                )
            )
            pcmPlaybackJob = audioScope.launch {
                var track: AudioTrack? = null
                try {
                    val minBuf = AudioTrack.getMinBufferSize(
                        GeminiTtsClient.OUTPUT_SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    ).coerceAtLeast(4096)

                    track = AudioTrack.Builder()
                        .setAudioAttributes(speechMediaAttributes())
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(GeminiTtsClient.OUTPUT_SAMPLE_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build()
                        )
                        .setBufferSizeInBytes(minBuf * 2)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()

                    synchronized(lock) { audioTrack = track }
                    track.play()

                    var offset = 0
                    val chunkSize = 4096
                    while (offset < pcmBytes.size && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        val toWrite = minOf(chunkSize, pcmBytes.size - offset)
                        val written = track.write(pcmBytes, offset, toWrite)
                        if (written <= 0) break
                        offset += written
                    }
                    runCatching { track.stop() }
                } catch (_: Exception) {
                    // Clean release in finally
                } finally {
                    releaseAndReset()
                    onFinished?.let { cb -> mainHandler.post(cb) }
                }
            }
        }
    }

    /**
     * Primary expressive speech output:
     *  - Uses Google AI Studio `GeminiTtsClient` (natural Malayalam voice) whenever `apiKey` is present.
     *  - Falls back to Android's built-in offline `TextToSpeech` (`ml-IN`) ONLY when offline or no API key.
     */
    suspend fun speakNaturalReply(
        text: String,
        apiKey: String,
        ttsClient: GeminiTtsClient,
        voiceName: String,
        ttsStyle: String,
        preferMalayalam: Boolean
    ) {
        if (text.isBlank()) return
        if (apiKey.isNotBlank()) {
            val pcm = ttsClient.synthesizePcm24kMono(
                apiKey = apiKey,
                text = text,
                voiceName = voiceName,
                ttsStyle = ttsStyle
            )
            if (pcm != null && pcm.isNotEmpty()) {
                playPcm24kMono(pcmBytes = pcm, displayTitle = text.take(48))
                return
            }
        }
        // Fallback ONLY when offline or API key missing/failed
        speakShortReplyIfIdle(text, preferMalayalam)
    }

    fun speakShortReplyIfIdle(text: String, preferMalayalam: Boolean = true) {
        if (text.isBlank() || _playbackState.value.isPlaying) return
        val spokenClean = text
            .replace(Regex("[•✅⚠️🔊✦🎬]"), "")
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
        releaseAndReset()
    }

    fun releaseAndReset() {
        synchronized(lock) {
            releaseAndResetUnlocked(invokeFinished = false)
        }
    }

    private fun releaseAndResetUnlocked(invokeFinished: Boolean) {
        pcmPlaybackJob?.cancel()
        pcmPlaybackJob = null

        runCatching { tts?.stop() }

        runCatching {
            audioTrack?.let { at ->
                if (at.playState == AudioTrack.PLAYSTATE_PLAYING) at.stop()
                at.flush()
                at.release()
            }
        }
        audioTrack = null

        runCatching {
            mediaPlayer?.let { mp ->
                if (mp.isPlaying) mp.stop()
                mp.reset()
                mp.release()
            }
        }
        mediaPlayer = null

        abandonAudioFocus()
        postPlaybackState(PlaybackState())
    }

    private fun postPlaybackState(newState: PlaybackState) {
        mainHandler.post {
            _playbackState.value = newState
        }
    }

    private fun speechMediaAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

    private fun requestTransientAudioFocus() {
        val am = audioManager ?: return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(speechMediaAttributes())
            .build()
        focusRequest = req
        runCatching { am.requestAudioFocus(req) }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        focusRequest?.let { req ->
            runCatching { am.abandonAudioFocusRequest(req) }
        }
        focusRequest = null
    }
}
