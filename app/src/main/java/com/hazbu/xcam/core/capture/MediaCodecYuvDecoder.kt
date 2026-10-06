package com.hazbu.xcam.core.capture

import android.content.Context
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.core.net.toUri
import java.nio.ByteBuffer

/**
 * Lightweight hardware MediaCodec-based video decoder that outputs raw YUV_420_888 frames
 * directly, avoiding CPU-heavy Bitmap and JPEG conversion loops.
 * Aligned with CamSwap's high-speed YUV pump architecture.
 */
class MediaCodecYuvDecoder(
    private val context: Context,
    private val mediaPath: String,
    private val logAction: (String) -> Unit
) {
    class YuvFrame(
        val width: Int,
        val height: Int,
        val yPlane: ByteArray,
        val uPlane: ByteArray,
        val vPlane: ByteArray,
        val timestampNs: Long
    )

    @Volatile
    private var isRunning = false
    private var decodeThread: Thread? = null

    @Volatile
    var latestFrame: YuvFrame? = null
        private set

    @Volatile
    var videoWidth = 0
        private set

    @Volatile
    var videoHeight = 0
        private set

    fun start() {
        if (isRunning) return
        isRunning = true
        decodeThread = Thread({ decodeLoop() }, "xCam-YuvDecoder").apply {
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
        latestFrame = null
    }

    private fun decodeLoop() {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null

        try {
            extractor = MediaExtractor().apply {
                setDataSource(context, mediaPath.toUri(), null)
            }

            var videoTrackIndex = -1
            var videoFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i
                    videoFormat = format
                    break
                }
            }

            if (videoTrackIndex == -1 || videoFormat == null) {
                logAction("[YUV-DECODER] No video track found in $mediaPath")
                return
            }

            extractor.selectTrack(videoTrackIndex)
            videoWidth = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
            videoHeight = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)

            val mime = videoFormat.getString(MediaFormat.KEY_MIME) ?: "video/avc"
            videoFormat.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )

            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(videoFormat, null, null, 0)
                start()
            }

            val bufferInfo = MediaCodec.BufferInfo()
            val timeoutUs = 10_000L
            var isInputEos = false

            val frameDurationMs = 1000L / (videoFormat.runCatching { getInteger(MediaFormat.KEY_FRAME_RATE) }.getOrNull() ?: 30).coerceAtLeast(15)

            while (isRunning) {
                val loopStartMs = System.currentTimeMillis()

                if (!isInputEos) {
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
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                if (outIndex >= 0) {
                    val image: Image? = codec.getOutputImage(outIndex)
                    if (image != null) {
                        val frame = extractYuvFrame(image)
                        latestFrame = frame
                        image.close()
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                }

                val elapsedMs = System.currentTimeMillis() - loopStartMs
                val sleepMs = frameDurationMs - elapsedMs
                if (sleepMs > 0) {
                    Thread.sleep(sleepMs)
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Throwable) {
            logAction("[YUV-DECODER] Decoder error: ${e.message}")
        } finally {
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            try { extractor?.release() } catch (_: Throwable) {}
        }
    }

    private fun extractYuvFrame(image: Image): YuvFrame {
        val width = image.width
        val height = image.height
        val planes = image.planes

        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        val ySize = width * height
        val uvSize = ((width + 1) / 2) * ((height + 1) / 2)

        val yBytes = ByteArray(ySize)
        val uBytes = ByteArray(uvSize)
        val vBytes = ByteArray(uvSize)

        copyPlane(planes[0], yBytes, width, height)
        copyPlane(planes[1], uBytes, (width + 1) / 2, (height + 1) / 2)
        copyPlane(planes[2], vBytes, (width + 1) / 2, (height + 1) / 2)

        return YuvFrame(width, height, yBytes, uBytes, vBytes, System.nanoTime())
    }

    private fun copyPlane(plane: Image.Plane, outBytes: ByteArray, width: Int, height: Int) {
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        if (pixelStride == 1 && rowStride == width) {
            buffer.get(outBytes, 0, outBytes.size.coerceAtMost(buffer.remaining()))
            return
        }

        var outPos = 0
        val rowBuffer = ByteArray(rowStride)
        for (row in 0 until height) {
            val bytesToRead = rowStride.coerceAtMost(buffer.remaining())
            buffer.get(rowBuffer, 0, bytesToRead)
            for (col in 0 until width) {
                val inPos = col * pixelStride
                if (inPos < bytesToRead && outPos < outBytes.size) {
                    outBytes[outPos++] = rowBuffer[inPos]
                }
            }
        }
    }
}
