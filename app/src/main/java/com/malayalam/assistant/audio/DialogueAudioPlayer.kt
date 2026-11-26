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
import android.speech.tts.UtteranceProgressListener
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
 * Deduplicates automatic background triggers (`WhatsApp` + `SMS` + `Gmail` + `NotificationListener`)
 * with a strict 60-second cooldown so the same message NEVER loops or repeats twice.
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
 *  1. `MediaPlayer` for Malayalam movie clips (`isLooping = false`, AudioFocus, auto-release on finish).
 *  2. **Live Streaming `AudioTrack`** (`24000 Hz`, mono, 16-bit PCM, `MODE_STREAM`) fed chunk-by-chunk
 *     directly from `GeminiTtsClient.streamPcm24kMono(...)` so speech begins within ~1 second!
 *  3. Offline Android `TextToSpeech` (`ml-IN` / `en-IN`) fallback ONLY when offline or no API key,
 *     with `onFinished` callback support for automatic follow-up mic activation!
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
    private var pendingTtsOnFinished: (() -> Unit)? = null

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
            releaseAndResetUnlocked()
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
                releaseAndResetUnlocked()
                false
            }
        }
    }

    /**
     * Streams expressive Gemini TTS directly into a live `AudioTrack` (`MODE_STREAM`) chunk-by-chunk
     * as SSE frames arrive, invoking [onFinished] on the main thread right when speaking ends!
     */
    suspend fun speakNaturalReply(
        text: String,
        apiKey: String,
        ttsClient: GeminiTtsClient,
        voiceName: String,
        ttsStyle: String,
        preferMalayalam: Boolean,
        onFinished: (() -> Unit)? = null
    ) {
        if (text.isBlank()) {
            onFinished?.let { mainHandler.post(it) }
            return
        }

        if (apiKey.isNotBlank()) {
            val streamed = streamLiveGeminiTts(
                text = text,
                apiKey = apiKey,
                ttsClient = ttsClient,
                voiceName = voiceName,
                ttsStyle = ttsStyle,
                onFinished = onFinished
            )
            if (streamed) return
        }

        // Offline / no-key fallback
        speakShortReplyIfIdle(text, preferMalayalam, onFinished)
    }

    private suspend fun streamLiveGeminiTts(
        text: String,
        apiKey: String,
        ttsClient: GeminiTtsClient,
        voiceName: String,
        ttsStyle: String,
        onFinished: (() -> Unit)?
    ): Boolean {
        var track: AudioTrack? = null
        var started = false

        val success = try {
            ttsClient.streamPcm24kMono(
                apiKey = apiKey,
                text = text,
                voiceName = voiceName,
                ttsStyle = ttsStyle
            ) { chunk ->
                if (!started) {
                    synchronized(lock) {
                        releaseAndResetUnlocked()
                        requestTransientAudioFocus()
                        val minBuf = AudioTrack.getMinBufferSize(
                            GeminiTtsClient.OUTPUT_SAMPLE_RATE,
                            AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_16BIT
                        ).coerceAtLeast(4096)

                        val created = AudioTrack.Builder()
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
                        created.play()
                        track = created
                        audioTrack = created
                    }
                    started = true
                    postPlaybackState(
                        PlaybackState(
                            isPlaying = true,
                            activeDialogueId = "gemini_tts_stream",
                            activeTitle = text.take(48),
                            activeTranscript = ""
                        )
                    )
                }

                val activeTrack = track
                if (activeTrack != null && activeTrack.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    var offset = 0
                    while (offset < chunk.size && activeTrack.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        val written = activeTrack.write(chunk, offset, minOf(4096, chunk.size - offset))
                        if (written <= 0) break
                        offset += written
                    }
                }
            }
        } catch (_: Exception) {
            false
        } finally {
            if (started) {
                runCatching { track?.stop() }
                releaseAndReset()
                onFinished?.let { cb -> mainHandler.post(cb) }
            }
        }
        return success && started
    }

    fun speakShortReplyIfIdle(
        text: String,
        preferMalayalam: Boolean = true,
        onFinished: (() -> Unit)? = null
    ) {
        if (text.isBlank() || _playbackState.value.isPlaying) {
            onFinished?.let { mainHandler.post(it) }
            return
        }
        val spokenClean = text
            .replace(Regex("[•✅⚠️🔊✦🎬]"), "")
            .lineSequence()
            .firstOrNull()
            ?.trim()
            ?.take(140)
            .orEmpty()
        if (spokenClean.isBlank()) {
            onFinished?.let { mainHandler.post(it) }
            return
        }

        synchronized(lock) {
            pendingTtsOnFinished = onFinished
            if (tts == null) {
                tts = TextToSpeech(context.applicationContext) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        ttsReady = true
                        attachTtsListener()
                        speakInternal(spokenClean, preferMalayalam)
                    } else {
                        pendingTtsOnFinished?.let { mainHandler.post(it) }
                        pendingTtsOnFinished = null
                    }
                }
            } else if (ttsReady) {
                attachTtsListener()
                speakInternal(spokenClean, preferMalayalam)
            }
        }
    }

    private fun attachTtsListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                val cb = synchronized(lock) {
                    val tmp = pendingTtsOnFinished
                    pendingTtsOnFinished = null
                    tmp
                }
                cb?.let { mainHandler.post(it) }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                onDone(utteranceId)
            }
        })
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
            releaseAndResetUnlocked()
        }
    }

    private fun releaseAndResetUnlocked() {
        pcmPlaybackJob?.cancel()
        pcmPlaybackJob = null
        pendingTtsOnFinished = null

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
        mainHandler.post { _playbackState.value = newState }
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
        focusRequest?.let { req -> runCatching { am.abandonAudioFocusRequest(req) } }
        focusRequest = null
    }
}
