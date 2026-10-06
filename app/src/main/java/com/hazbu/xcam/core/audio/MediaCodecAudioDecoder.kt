package com.hazbu.xcam.core.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.core.net.toUri
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.min

/**
 * Hardware/framework-based audio decoder that extracts PCM 16-bit audio from the video source,
 * providing streaming audio samples for AudioRecord replacement (aligned with CamSwap).
 */
class MediaCodecAudioDecoder(
    private val context: Context,
    private val mediaPath: String,
    private val logAction: (String) -> Unit
) {
    @Volatile
    private var isRunning = false
    private var decodeThread: Thread? = null

    private val pcmQueue = ConcurrentLinkedQueue<ByteArray>()
    private var currentPcmChunk: ByteArray? = null
    private var currentPcmOffset = 0

    @Volatile
    var sampleRate = 44100
        private set

    @Volatile
    var channelCount = 2
        private set

    fun start() {
        if (isRunning) return
        isRunning = true
        decodeThread = Thread({ decodeLoop() }, "xCam-AudioDecoder").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        isRunning = false
        decodeThread?.interrupt()
        try {
            decodeThread?.join(500)
        } catch (_: InterruptedException) {}
        decodeThread = null
        pcmQueue.clear()
        currentPcmChunk = null
        currentPcmOffset = 0
    }

    private fun decodeLoop() {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null

        try {
            extractor = MediaExtractor().apply {
                setDataSource(context, mediaPath.toUri(), null)
            }

            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }

            if (audioTrackIndex == -1 || audioFormat == null) {
                logAction("[AUDIO-DECODER] No audio track found in $mediaPath")
                return
            }

            extractor.selectTrack(audioTrackIndex)
            sampleRate = audioFormat.runCatching { getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(44100)
            channelCount = audioFormat.runCatching { getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(2)

            val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: "audio/mp4a-latm"
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(audioFormat, null, null, 0)
                start()
            }

            val bufferInfo = MediaCodec.BufferInfo()
            val timeoutUs = 10_000L

            while (isRunning) {
                if (pcmQueue.size > 50) {
                    Thread.sleep(20)
                    continue
                }

                val inIndex = codec.dequeueInputBuffer(timeoutUs)
                if (inIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inIndex)
                    if (inputBuffer != null) {
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                        } else {
                            codec.queueInputBuffer(
                                inIndex,
                                0,
                                sampleSize,
                                extractor.sampleTime,
                                0
                            )
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                if (outIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        val chunk = ByteArray(bufferInfo.size)
                        outputBuffer.get(chunk)
                        pcmQueue.add(chunk)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Throwable) {
            logAction("[AUDIO-DECODER] Error: ${e.message}")
        } finally {
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            try { extractor?.release() } catch (_: Throwable) {}
        }
    }

    /**
     * Reads decoded PCM audio bytes into target buffer.
     * Returns true if replaced, or false if queue is empty.
     */
    fun readBytes(target: ByteArray, offset: Int, length: Int): Boolean {
        var written = 0
        while (written < length) {
            if (currentPcmChunk == null || currentPcmOffset >= (currentPcmChunk?.size ?: 0)) {
                currentPcmChunk = pcmQueue.poll()
                currentPcmOffset = 0
                if (currentPcmChunk == null) break
            }

            val chunk = currentPcmChunk ?: break
            val available = chunk.size - currentPcmOffset
            val toCopy = min(length - written, available)

            System.arraycopy(chunk, currentPcmOffset, target, offset + written, toCopy)
            currentPcmOffset += toCopy
            written += toCopy
        }

        if (written > 0) {
            if (written < length) {
                target.fill(0, offset + written, offset + length)
            }
            return true
        }
        return false
    }

    /**
     * Reads decoded PCM audio as 16-bit shorts into target buffer.
     */
    fun readShorts(target: ShortArray, offset: Int, length: Int): Boolean {
        val byteLen = length * 2
        val tempBytes = ByteArray(byteLen)
        if (!readBytes(tempBytes, 0, byteLen)) return false

        for (i in 0 until length) {
            val low = tempBytes[i * 2].toInt() and 0xFF
            val high = tempBytes[i * 2 + 1].toInt()
            target[offset + i] = ((high shl 8) or low).toShort()
        }
        return true
    }

    /**
     * Reads decoded PCM audio directly into ByteBuffer.
     */
    fun readByteBuffer(target: ByteBuffer, length: Int): Boolean {
        val tempBytes = ByteArray(length)
        if (!readBytes(tempBytes, 0, length)) return false
        val currentPos = target.position()
        target.put(tempBytes, 0, length.coerceAtMost(target.remaining()))
        target.position(currentPos)
        return true
    }
}
