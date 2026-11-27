package com.malayalam.assistant.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

data class WavRecordingResult(
    val wavBytes: ByteArray,
    val speechDetected: Boolean
)

/**
 * Gemini-Live Style Continuous Voice Activity Detection (VAD) Recorder (`AudioRecord` `VOICE_RECOGNITION`):
 *  - In `continuousWaitMode = true`, NEVER times out while silent — stays quietly in `LISTENING`
 *    and periodically recalibrates ambient `noiseFloor` every `1500 ms` of quiet so room noise never false-triggers.
 *  - Requires `rms > max(noiseFloor * 2.3, 680.0)` sustained for `>= 180 ms` before triggering `onSpeechStarted()`.
 *  - Auto-stops after **`850 ms`** of continuous silence once speech lasted `>= 350 ms` (or manual stop),
 *    immediately releasing `AudioRecord` so the mic never hears its own TTS/movie clip!
 *  - Encodes captured speech as compact **8 kHz mono 16-bit WAV** (`50%` smaller upload payload).
 */
class SmartVoiceRecorder {

    private val stopRequested = AtomicBoolean(false)
    private val isRecordingFlag = AtomicBoolean(false)

    val isRecording: Boolean
        get() = isRecordingFlag.get()

    fun stopRecordingNow() {
        stopRequested.set(true)
    }

    @SuppressLint("MissingPermission")
    suspend fun recordWithVad(
        continuousWaitMode: Boolean = true,
        onSpeechStarted: () -> Unit = {},
        onRmsLevel: (Float) -> Unit
    ): WavRecordingResult = withContext(Dispatchers.IO) {
        stopRequested.set(false)
        val minBuf = AudioRecord.getMinBufferSize(
            CAPTURE_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(3200)

        val recorder = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                CAPTURE_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2
            )
        }.getOrNull() ?: return@withContext WavRecordingResult(ByteArray(0), false)

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { recorder.release() }
            return@withContext WavRecordingResult(ByteArray(0), false)
        }

        val frameSamples = 320 // 20 ms at 16 kHz
        val shortBuf = ShortArray(frameSamples)
        val pcm8kOut = ByteArrayOutputStream(32 * 1024)
        // 400 ms pre-roll ring buffer (20 frames of 20 ms) so opening Malayalam syllables are NEVER clipped
        val preRollFrames = ArrayDeque<ByteArray>(PRE_ROLL_FRAMES + 2)

        var speechDetected = false
        var initialCalibrationSum = 0.0
        var initialCalibrationCount = 0
        var noiseFloor = 300.0

        var quietRecalSum = 0.0
        var quietRecalCount = 0
        var consecutiveLoudMs = 0L
        var speechDurationMs = 0L
        var silenceAfterSpeechMs = 0L

        isRecordingFlag.set(true)
        val startMs = System.currentTimeMillis()
        var speechStartMs = 0L

        try {
            recorder.startRecording()

            while (isActive && !stopRequested.get()) {
                val read = recorder.read(shortBuf, 0, frameSamples)
                if (read <= 0) continue

                val nowMs = System.currentTimeMillis()
                val elapsedMs = nowMs - startMs
                val rms = computeRms(shortBuf, read)
                val normalizedWave = ((rms - 120.0) / 3600.0).toFloat().coerceIn(0.05f, 1.0f)
                onRmsLevel(normalizedWave)

                val frame8kBytes = downsample16kTo8kLittleEndian(shortBuf, read)

                // Phase 1: Initial 350 ms ambient calibration
                if (elapsedMs <= CALIBRATION_MS) {
                    initialCalibrationSum += rms
                    initialCalibrationCount++
                    noiseFloor = (initialCalibrationSum / initialCalibrationCount.coerceAtLeast(1))
                    pushPreRoll(preRollFrames, frame8kBytes)
                    continue
                }

                val vadThreshold = maxOf(noiseFloor * 2.3, 680.0)

                if (!speechDetected) {
                    pushPreRoll(preRollFrames, frame8kBytes)
                    if (rms > vadThreshold) {
                        consecutiveLoudMs += FRAME_MS
                        quietRecalSum = 0.0
                        quietRecalCount = 0
                        if (consecutiveLoudMs >= MIN_SPEECH_ONSET_MS) {
                            speechDetected = true
                            speechStartMs = nowMs
                            speechDurationMs = consecutiveLoudMs
                            silenceAfterSpeechMs = 0L
                            for (pre in preRollFrames) pcm8kOut.write(pre)
                            preRollFrames.clear()
                            onSpeechStarted()
                        }
                    } else {
                        consecutiveLoudMs = 0L
                        // Periodically recalibrate ambient noise floor during long silence
                        quietRecalSum += rms
                        quietRecalCount++
                        if (quietRecalCount >= QUIET_RECALIBRATION_FRAMES) {
                            val windowAvg = quietRecalSum / quietRecalCount
                            noiseFloor = (noiseFloor * 0.72 + windowAvg * 0.28).coerceIn(120.0, 2200.0)
                            quietRecalSum = 0.0
                            quietRecalCount = 0
                        }
                        if (!continuousWaitMode && elapsedMs >= SINGLE_TURN_WAIT_TIMEOUT_MS) {
                            break
                        }
                    }
                } else {
                    pcm8kOut.write(frame8kBytes)
                    speechDurationMs += FRAME_MS
                    if (rms > vadThreshold * 0.76) {
                        silenceAfterSpeechMs = 0L
                    } else {
                        silenceAfterSpeechMs += FRAME_MS
                        if (silenceAfterSpeechMs >= SILENCE_AUTO_STOP_MS && speechDurationMs >= MIN_SPEECH_DURATION_MS) {
                            break
                        }
                    }
                    if (nowMs - speechStartMs >= MAX_SPEECH_DURATION_MS) {
                        break
                    }
                }
            }
        } catch (_: Exception) {
            // Finalize cleanly
        } finally {
            isRecordingFlag.set(false)
            onRmsLevel(0f)
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }

        val rawPcm8k = pcm8kOut.toByteArray()
        if (!speechDetected || rawPcm8k.size < 2400) {
            WavRecordingResult(ByteArray(0), false)
        } else {
            WavRecordingResult(wrapPcm16AsWav(rawPcm8k, UPLOAD_SAMPLE_RATE, 1), true)
        }
    }

    private fun pushPreRoll(queue: ArrayDeque<ByteArray>, frame: ByteArray) {
        if (queue.size >= PRE_ROLL_FRAMES) queue.removeFirst()
        queue.addLast(frame)
    }

    private fun computeRms(samples: ShortArray, count: Int): Double {
        var sumSq = 0.0
        for (i in 0 until count) {
            val s = samples[i].toDouble()
            sumSq += s * s
        }
        return sqrt(sumSq / count.coerceAtLeast(1))
    }

    private fun downsample16kTo8kLittleEndian(samples: ShortArray, count: Int): ByteArray {
        val outSamples = count / 2
        val out = ByteArray(outSamples * 2)
        for (i in 0 until outSamples) {
            val avg = (samples[i * 2].toInt() + samples[i * 2 + 1].toInt()) shr 1
            out[i * 2] = (avg and 0xFF).toByte()
            out[i * 2 + 1] = ((avg shr 8) and 0xFF).toByte()
        }
        return out
    }

    companion object {
        const val CAPTURE_SAMPLE_RATE = 16_000
        const val UPLOAD_SAMPLE_RATE = 8_000
        private const val FRAME_MS = 20L
        private const val PRE_ROLL_FRAMES = 20 // 20 * 20 ms = 400 ms pre-roll ring buffer
        private const val CALIBRATION_MS = 350L
        private const val MIN_SPEECH_ONSET_MS = 180L
        private const val MIN_SPEECH_DURATION_MS = 350L
        private const val QUIET_RECALIBRATION_FRAMES = 75 // 1500 ms
        private const val SINGLE_TURN_WAIT_TIMEOUT_MS = 8_000L
        private const val SILENCE_AUTO_STOP_MS = 850L
        private const val MAX_SPEECH_DURATION_MS = 20_000L

        fun wrapPcm16AsWav(pcmBytes: ByteArray, sampleRate: Int, channels: Int): ByteArray {
            val byteRate = sampleRate * channels * 2
            val blockAlign = (channels * 2).toShort()
            val totalDataLen = pcmBytes.size + 36
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt(totalDataLen)
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)
                putShort(1)
                putShort(channels.toShort())
                putInt(sampleRate)
                putInt(byteRate)
                putShort(blockAlign)
                putShort(16)
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(pcmBytes.size)
            }.array()
            val wav = ByteArray(44 + pcmBytes.size)
            System.arraycopy(header, 0, wav, 0, 44)
            System.arraycopy(pcmBytes, 0, wav, 44, pcmBytes.size)
            return wav
        }
    }
}
