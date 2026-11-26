package com.malayalam.assistant.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import com.malayalam.assistant.data.DialogueEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlaybackState(
    val isPlaying: Boolean = false,
    val activeDialogueId: String? = null,
    val activeTitle: String = "",
    val activeTranscript: String = ""
)

/**
 * Lightweight singleton `MediaPlayer` controller shared by:
 *  - Floating Gemini Assistant Overlay (with instant Stop bar)
 *  - Background Notification Listener reactions
 *  - Dialogue Vault & Review Cards
 */
class DialogueAudioPlayer(private val context: Context) {

    private val lock = Any()
    private var mediaPlayer: MediaPlayer? = null

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    fun playEntry(entry: DialogueEntry) {
        playUri(
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
    ) {
        if (uriString.isBlank()) return
        synchronized(lock) {
            stopUnlocked()
            runCatching {
                val uri = Uri.parse(uriString)
                val player = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    setDataSource(context, uri)
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
            }.onFailure {
                stopUnlocked()
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            stopUnlocked()
        }
    }

    private fun stopUnlocked() {
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
