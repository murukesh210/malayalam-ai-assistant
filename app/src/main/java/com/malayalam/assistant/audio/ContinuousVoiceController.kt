package com.malayalam.assistant.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class VoiceSessionState {
    IDLE,
    LISTENING,
    RECORDING_SPEECH,
    THINKING,
    SPEAKING_OR_PLAYING
}

/**
 * Gemini-Live Style Continuous Hands-Free Conversation Controller:
 *  - State Machine: `IDLE -> LISTENING -> RECORDING_SPEECH -> THINKING -> SPEAKING_OR_PLAYING -> (300ms echo guard) -> LISTENING`.
 *  - Never times out while silent in continuous mode.
 *  - Stops `AudioRecord` BEFORE thinking & playback so the mic never hears its own TTS/movie clip.
 *  - Supports instant barge-in (tapping orb during `SPEAKING_OR_PLAYING` stops audio & jumps straight to `LISTENING`).
 *  - Cleanly stops when app enters background or user taps `Stop Listening (⏹)`.
 */
class ContinuousVoiceController(
    private val scope: CoroutineScope,
    private val audioPlayer: DialogueAudioPlayer,
    private val onProcessCapturedWav: suspend (wavBytes: ByteArray, onPlaybackFinished: () -> Unit) -> Unit
) {
    private val recorder = SmartVoiceRecorder()
    private var sessionJob: Job? = null
    private var sessionActive = false
    private var continuousEnabled = true

    private val _state = MutableStateFlow(VoiceSessionState.IDLE)
    val state: StateFlow<VoiceSessionState> = _state.asStateFlow()

    private val _micRmsLevel = MutableStateFlow(0f)
    val micRmsLevel: StateFlow<Float> = _micRmsLevel.asStateFlow()

    val isSessionRunning: Boolean
        get() = sessionActive

    fun startSession(continuousMode: Boolean) {
        continuousEnabled = continuousMode
        sessionActive = true
        audioPlayer.stop()
        launchListeningCycle()
    }

    fun stopSession() {
        sessionActive = false
        recorder.stopRecordingNow()
        sessionJob?.cancel()
        sessionJob = null
        audioPlayer.stop()
        _micRmsLevel.value = 0f
        _state.value = VoiceSessionState.IDLE
    }

    /**
     * Barge-in or turn control when the user taps the center orb:
     *  - `IDLE` -> starts live session
     *  - `SPEAKING_OR_PLAYING` -> interrupts playback immediately and resumes `LISTENING`!
     *  - `RECORDING_SPEECH` -> finalizes speech immediately and sends to Gemini
     *  - `LISTENING` -> ends continuous session cleanly
     */
    fun onOrbTapped(continuousMode: Boolean) {
        when (_state.value) {
            VoiceSessionState.IDLE -> startSession(continuousMode)
            VoiceSessionState.SPEAKING_OR_PLAYING -> {
                audioPlayer.stop()
                if (sessionActive && continuousEnabled) {
                    launchListeningCycle(withEchoGuard = false)
                } else {
                    _state.value = VoiceSessionState.IDLE
                }
            }
            VoiceSessionState.RECORDING_SPEECH -> {
                recorder.stopRecordingNow()
            }
            VoiceSessionState.LISTENING -> {
                stopSession()
            }
            VoiceSessionState.THINKING -> {
                // Keep thinking uninterrupted
            }
        }
    }

    /**
     * Called from `MainActivity.onStop()` or when enabling Background Mode so foreground mic
     * never runs while app is backgrounded.
     */
    fun stopMicForBackground() {
        if (_state.value == VoiceSessionState.LISTENING || _state.value == VoiceSessionState.RECORDING_SPEECH) {
            sessionActive = false
            recorder.stopRecordingNow()
            sessionJob?.cancel()
            sessionJob = null
            _micRmsLevel.value = 0f
            _state.value = VoiceSessionState.IDLE
        }
    }

    private fun launchListeningCycle(withEchoGuard: Boolean = false) {
        sessionJob?.cancel()
        sessionJob = scope.launch {
            if (withEchoGuard) {
                delay(ECHO_GUARD_MS)
            }
            if (!isActive || !sessionActive) {
                _state.value = VoiceSessionState.IDLE
                return@launch
            }

            _state.value = VoiceSessionState.LISTENING
            _micRmsLevel.value = 0.14f

            val recording = recorder.recordWithVad(
                continuousWaitMode = continuousEnabled,
                onSpeechStarted = {
                    if (sessionActive) _state.value = VoiceSessionState.RECORDING_SPEECH
                },
                onRmsLevel = { rms ->
                    _micRmsLevel.value = rms
                }
            )

            _micRmsLevel.value = 0f

            if (!isActive || !sessionActive) {
                _state.value = VoiceSessionState.IDLE
                return@launch
            }

            if (!recording.speechDetected || recording.wavBytes.isEmpty()) {
                if (continuousEnabled && sessionActive) {
                    launchListeningCycle(withEchoGuard = false)
                } else {
                    sessionActive = false
                    _state.value = VoiceSessionState.IDLE
                }
                return@launch
            }

            _state.value = VoiceSessionState.THINKING

            // Delegate to ViewModel (GeminiBrainClient + Movie Clip / Streaming TTS)
            // When playback completes, resume LISTENING automatically after 300ms echo-guard!
            onProcessCapturedWav(recording.wavBytes) {
                if (sessionActive && continuousEnabled) {
                    launchListeningCycle(withEchoGuard = true)
                } else {
                    sessionActive = false
                    _state.value = VoiceSessionState.IDLE
                }
            }
        }
    }

    fun markSpeakingOrPlaying() {
        if (sessionActive) {
            _state.value = VoiceSessionState.SPEAKING_OR_PLAYING
        }
    }

    companion object {
        private const val ECHO_GUARD_MS = 300L
    }
}
