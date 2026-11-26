package com.malayalam.assistant.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

data class DecodedPcmClip(
    val samples16kMono: FloatArray,
    val sampleRate: Int = TARGET_SAMPLE_RATE,
    val originalDurationMs: Long
)

/**
 * Pure Android `MediaExtractor` + `MediaCodec` Audio Decoder (ZERO FFmpeg):
 *  - Decodes `.mp3`, `.wav`, `.ogg`, `.m4a` directly from a SAF `Uri`
 *  - Downmixes stereo/multi-channel to mono on the fly
 *  - Resamples to `16,000 Hz` mono `FloatArray` (`[-1.0f, 1.0f]`)
 *  - Strictly caps analysis window to the first `15 seconds` (`240,000` floats = `< 960 KB`)
 *    and trims leading/trailing silence so 4 GB RAM phones never spike heap usage.
 */
object NativeAudioDecoder {

    const val TARGET_SAMPLE_RATE = 16_000
    const val MAX_ANALYSIS_SECONDS = 15
    private const val MAX_SOURCE_DURATION_US = MAX_ANALYSIS_SECONDS * 1_000_000L
    private const val SILENCE_THRESHOLD = 0.012f

    suspend fun decodeFirst15sMono16k(
        context: Context,
        audioUri: Uri
    ): DecodedPcmClip? = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            context.contentResolver.openFileDescriptor(audioUri, "r")?.use { pfd ->
                extractor.setDataSource(pfd.fileDescriptor)
            } ?: return@withContext null

            var audioTrackIndex = -1
            var trackFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    trackFormat = fmt
                    break
                }
            }
            if (audioTrackIndex < 0 || trackFormat == null) return@withContext null

            extractor.selectTrack(audioTrackIndex)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: return@withContext null
            val durationUs = if (trackFormat.containsKey(MediaFormat.KEY_DURATION)) {
                trackFormat.getLong(MediaFormat.KEY_DURATION)
            } else 0L
            var srcSampleRate = if (trackFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else 44_100
            var channelCount = if (trackFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            } else 1

            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(trackFormat, null, null, 0)
                start()
            }

            // Collect mono samples at source rate up to 15s, resampling in chunks to keep RAM tiny
            val maxOutSamples = TARGET_SAMPLE_RATE * MAX_ANALYSIS_SECONDS
            val out16k = FloatArray(maxOutSamples)
            var outWriteIdx = 0

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone && outWriteIdx < maxOutSamples) {
                if (!inputDone) {
                    val inBufIndex = codec.dequeueInputBuffer(10_000L)
                    if (inBufIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inBufIndex)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        val sampleTimeUs = extractor.sampleTime
                        if (sampleSize < 0 || sampleTimeUs > MAX_SOURCE_DURATION_US) {
                            codec.queueInputBuffer(
                                inBufIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inBufIndex, 0, sampleSize, sampleTimeUs, 0)
                            extractor.advance()
                        }
                    }
                }

                val outBufIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                when {
                    outBufIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFmt = codec.outputFormat
                        if (newFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            srcSampleRate = newFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (newFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channelCount = newFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        }
                    }
                    outBufIndex >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outBufIndex)
                        if (outBuf != null && bufferInfo.size > 0) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            val monoChunk = pcm16BufferToMonoFloats(outBuf, bufferInfo.size, channelCount)
                            val resampled = resampleLinear(monoChunk, srcSampleRate, TARGET_SAMPLE_RATE)
                            val toCopy = minOf(resampled.size, maxOutSamples - outWriteIdx)
                            System.arraycopy(resampled, 0, out16k, outWriteIdx, toCopy)
                            outWriteIdx += toCopy
                        }
                        codec.releaseOutputBuffer(outBufIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            val exact = out16k.copyOf(outWriteIdx)
            val trimmed = trimSilence(exact)
            DecodedPcmClip(
                samples16kMono = trimmed,
                sampleRate = TARGET_SAMPLE_RATE,
                originalDurationMs = if (durationUs > 0L) durationUs / 1000L else (outWriteIdx * 1000L / TARGET_SAMPLE_RATE)
            )
        } catch (_: Exception) {
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun pcm16BufferToMonoFloats(buf: ByteBuffer, byteCount: Int, channels: Int): FloatArray {
        val shortBuf = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val totalShorts = minOf(shortBuf.remaining(), byteCount / 2)
        val frames = totalShorts / channels
        val mono = FloatArray(frames)
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) {
                sum += shortBuf.get(f * channels + c) / 32768.0f
            }
            mono[f] = (sum / channels).coerceIn(-1f, 1f)
        }
        return mono
    }

    private fun resampleLinear(input: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        if (input.isEmpty() || srcRate == dstRate) return input
        val ratio = srcRate.toDouble() / dstRate.toDouble()
        val outLen = (input.size / ratio).toInt().coerceAtLeast(1)
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val srcPos = i * ratio
            val idx0 = srcPos.toInt().coerceIn(0, input.lastIndex)
            val idx1 = (idx0 + 1).coerceIn(0, input.lastIndex)
            val frac = (srcPos - idx0).toFloat()
            out[i] = input[idx0] * (1f - frac) + input[idx1] * frac
        }
        return out
    }

    private fun trimSilence(samples: FloatArray): FloatArray {
        if (samples.isEmpty()) return samples
        var start = 0
        while (start < samples.size && abs(samples[start]) < SILENCE_THRESHOLD) start++
        var end = samples.lastIndex
        while (end > start && abs(samples[end]) < SILENCE_THRESHOLD) end--
        val pad = TARGET_SAMPLE_RATE / 10 // keep 100ms natural breath padding
        val safeStart = (start - pad).coerceAtLeast(0)
        val safeEnd = (end + pad).coerceAtMost(samples.lastIndex)
        return if (safeEnd > safeStart) samples.copyOfRange(safeStart, safeEnd + 1) else samples
    }
}
