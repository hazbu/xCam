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

        val ySize = width * height
        val cw = (width + 1) / 2
        val ch = (height + 1) / 2
        val uvSize = cw * ch

        val yBytes = ByteArray(ySize)
        val uBytes = ByteArray(uvSize)
        val vBytes = ByteArray(uvSize)

        // Y plane
        val yPlane = planes[0]
        val yBuf = yPlane.buffer.duplicate()
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val yPos = yBuf.position()

        if (yPixelStride == 1 && yRowStride == width) {
            yBuf.get(yBytes, 0, minOf(yBytes.size, yBuf.remaining()))
        } else {
            for (row in 0 until height) {
                val rowStart = yPos + row * yRowStride
                val dstBase = row * width
                for (col in 0 until width) {
                    yBytes[dstBase + col] = yBuf.get(rowStart + col * yPixelStride)
                }
            }
        }

        // U and V planes
        val uPlane = planes[1]
        val vPlane = planes[2]
        val uBuf = uPlane.buffer.duplicate()
        val vBuf = vPlane.buffer.duplicate()
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride
        val uPos = uBuf.position()
        val vPos = vBuf.position()

        if (uPixelStride == 1 && vPixelStride == 1 && uRowStride == cw && vRowStride == cw) {
            uBuf.get(uBytes, 0, minOf(uBytes.size, uBuf.remaining()))
            vBuf.get(vBytes, 0, minOf(vBytes.size, vBuf.remaining()))
        } else {
            for (row in 0 until ch) {
                val uRowStart = uPos + row * uRowStride
                val vRowStart = vPos + row * vRowStride
                val dstBase = row * cw
                for (col in 0 until cw) {
                    uBytes[dstBase + col] = uBuf.get(uRowStart + col * uPixelStride)
                    vBytes[dstBase + col] = vBuf.get(vRowStart + col * vPixelStride)
                }
            }
        }

        return YuvFrame(width, height, yBytes, uBytes, vBytes, System.nanoTime())
    }
}
