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
 * Gemini-Style Voice Activity Detection (VAD) Recorder using Android `AudioRecord`
 * (`16000 Hz`, mono, 16-bit PCM, `AudioSource.VOICE_RECOGNITION`):
 *  - Calibrates ambient noise floor during the first `350 ms`.
 *  - Waits patiently up to `8000 ms` for the user to start speaking (NEVER cuts off early!).
 *  - Keeps a `300 ms` pre-roll buffer so the opening syllable is never clipped.
 *  - Once `rms > max(noiseFloor * 2.2, 650.0)`, sets `speechDetected = true` and drives the orb waveform.
 *  - Automatically stops after `1500 ms` of natural silence (or `20s` max, or manual orb tap),
 *    returning a ready-to-send 16 kHz mono WAV byte array in memory.
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
        onRmsLevel: (Float) -> Unit
    ): WavRecordingResult = withContext(Dispatchers.IO) {
        stopRequested.set(false)
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(3200)

        val recorder = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
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
        val pcmOut = ByteArrayOutputStream(64 * 1024)
        // Keep ~300 ms (15 frames of 20 ms) pre-roll before VAD triggers
        val preRollFrames = ArrayDeque<ByteArray>(16)

        var speechDetected = false
        var noiseFloorSum = 0.0
        var noiseFloorCount = 0
        var noiseFloor = 300.0
        var silenceAfterSpeechMs = 0L

        isRecordingFlag.set(true)
        val startMs = System.currentTimeMillis()

        try {
            recorder.startRecording()

            while (isActive && !stopRequested.get()) {
                val read = recorder.read(shortBuf, 0, frameSamples)
                if (read <= 0) continue

                val elapsedMs = System.currentTimeMillis() - startMs
                val rms = computeRms(shortBuf, read)
                val normalizedWave = ((rms - 120.0) / 3600.0).toFloat().coerceIn(0.05f, 1.0f)
                onRmsLevel(normalizedWave)

                val frameBytes = shortsToLittleEndianBytes(shortBuf, read)

                // Phase 1: Ambient noise calibration during first 350 ms
                if (elapsedMs <= CALIBRATION_MS) {
                    noiseFloorSum += rms
                    noiseFloorCount++
                    noiseFloor = (noiseFloorSum / noiseFloorCount.coerceAtLeast(1))
                    pushPreRoll(preRollFrames, frameBytes)
                    continue
                }

                val vadThreshold = maxOf(noiseFloor * 2.2, 650.0)

                if (!speechDetected) {
                    pushPreRoll(preRollFrames, frameBytes)
                    if (rms > vadThreshold) {
                        speechDetected = true
                        silenceAfterSpeechMs = 0L
                        // Flush pre-roll so opening syllable is preserved
                        for (pre in preRollFrames) pcmOut.write(pre)
                        preRollFrames.clear()
                    } else if (elapsedMs >= WAIT_FOR_SPEECH_TIMEOUT_MS) {
                        // 8 seconds elapsed with no speech -> exit cleanly
                        break
                    }
                } else {
                    pcmOut.write(frameBytes)
                    if (rms > vadThreshold * 0.78) {
                        silenceAfterSpeechMs = 0L
                    } else {
                        silenceAfterSpeechMs += FRAME_MS
                        if (silenceAfterSpeechMs >= SILENCE_AUTO_STOP_MS) {
                            break
                        }
                    }
                    if (elapsedMs >= MAX_RECORDING_MS) {
                        break
                    }
                }
            }
        } catch (_: Exception) {
            // Gracefully finalize whatever audio was captured
        } finally {
            isRecordingFlag.set(false)
            onRmsLevel(0f)
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }

        val rawPcm = pcmOut.toByteArray()
        if (!speechDetected || rawPcm.size < 3200) {
            WavRecordingResult(ByteArray(0), false)
        } else {
            WavRecordingResult(wrapPcm16AsWav(rawPcm, SAMPLE_RATE, 1), true)
        }
    }

    private fun pushPreRoll(queue: ArrayDeque<ByteArray>, frame: ByteArray) {
        if (queue.size >= 15) queue.removeFirst()
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

    private fun shortsToLittleEndianBytes(samples: ShortArray, count: Int): ByteArray {
        val out = ByteArray(count * 2)
        for (i in 0 until count) {
            val v = samples[i].toInt()
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val FRAME_MS = 20L
        private const val CALIBRATION_MS = 350L
        private const val WAIT_FOR_SPEECH_TIMEOUT_MS = 8_000L
        private const val SILENCE_AUTO_STOP_MS = 1_500L
        private const val MAX_RECORDING_MS = 20_000L

        fun wrapPcm16AsWav(pcmBytes: ByteArray, sampleRate: Int, channels: Int): ByteArray {
            val byteRate = sampleRate * channels * 2
            val blockAlign = (channels * 2).toShort()
            val totalDataLen = pcmBytes.size + 36
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt(totalDataLen)
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16) // PCM chunk size
                putShort(1) // PCM format
                putShort(channels.toShort())
                putInt(sampleRate)
                putInt(byteRate)
                putShort(blockAlign)
                putShort(16) // Bits per sample
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
