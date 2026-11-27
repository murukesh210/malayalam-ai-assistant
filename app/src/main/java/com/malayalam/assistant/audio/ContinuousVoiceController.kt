package com.malayalam.assistant.audio

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class VoiceSessionState {
    IDLE,
    LISTENING,
    RECORDING_SPEECH,
    THINKING,
    SPEAKING_OR_PLAYING
}

/**
 * Hardened Gemini-Live Style Continuous Hands-Free Conversation Controller:
 *  - State Machine: `IDLE -> LISTENING -> RECORDING_SPEECH -> THINKING -> SPEAKING_OR_PLAYING -> (250ms echo guard) -> LISTENING`.
 *  - 400 ms pre-roll ring buffer inside `SmartVoiceRecorder` so the first Malayalam syllable is never clipped.
 *  - Clean `AudioRecord` stop+release BEFORE `THINKING` / playback to prevent budget-phone audio HAL conflicts.
 *  - 15-second `withTimeoutOrNull(15_000L)` watchdog around BOTH `THINKING` and `SPEAKING_OR_PLAYING`
 *    inside `try / finally` so the orb NEVER hangs in limbo — always recovers cleanly back to `LISTENING`!
 */
class ContinuousVoiceController(
    private val scope: CoroutineScope,
    private val audioPlayer: DialogueAudioPlayer,
    private val onTimeoutOrError: (String) -> Unit = {},
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
     *  - `SPEAKING_OR_PLAYING` -> interrupts playback immediately and resumes `LISTENING`
     *  - `RECORDING_SPEECH` -> finalizes speech immediately and sends to Gemini
     *  - `LISTENING` -> ends continuous session cleanly
     *  - `THINKING` -> cancels stalled request and restarts `LISTENING`
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
                // Allow manual tap to recover immediately if user doesn't want to wait
                audioPlayer.stop()
                if (sessionActive && continuousEnabled) {
                    launchListeningCycle(withEchoGuard = false)
                } else {
                    stopSession()
                }
            }
        }
    }

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

            // AudioRecord is completely stopped & released inside recordWithVad's finally block before returning!
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

            // Brief 40ms HAL settle after AudioRecord.release() before starting network/playback
            delay(40L)
            _state.value = VoiceSessionState.THINKING

            val playbackDone = CompletableDeferred<Unit>()
            var timedOutOrFailed = false

            try {
                // Watchdog #1: THINKING (Gemini Brain request) must finish within 15s
                val brainResult = withTimeoutOrNull(WATCHDOG_TIMEOUT_MS) {
                    onProcessCapturedWav(recording.wavBytes) {
                        playbackDone.complete(Unit)
                    }
                    true
                }

                if (brainResult == null) {
                    timedOutOrFailed = true
                    audioPlayer.stop()
                    onTimeoutOrError("നെറ്റ്വർക്ക് സ്ലോ ആണ്, വീണ്ടും ശ്രമിക്കാം")
                    delay(900L)
                } else if (!playbackDone.isCompleted && _state.value == VoiceSessionState.SPEAKING_OR_PLAYING) {
                    // Watchdog #2: SPEAKING_OR_PLAYING must not hang indefinitely if a callback is missed
                    val playWait = withTimeoutOrNull(WATCHDOG_TIMEOUT_MS) {
                        playbackDone.await()
                        true
                    }
                    if (playWait == null) {
                        audioPlayer.stop()
                    }
                }
            } catch (_: Exception) {
                timedOutOrFailed = true
                audioPlayer.stop()
                onTimeoutOrError("കണക്ഷൻ തടസ്സം — വീണ്ടും ശ്രമിക്കാം")
                delay(700L)
            } finally {
                if (isActive && sessionActive && continuousEnabled) {
                    launchListeningCycle(withEchoGuard = !timedOutOrFailed)
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
        private const val ECHO_GUARD_MS = 250L
        private const val WATCHDOG_TIMEOUT_MS = 15_000L
    }
}
