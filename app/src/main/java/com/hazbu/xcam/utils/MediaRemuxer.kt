package com.hazbu.xcam.utils

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.core.net.toUri
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

object MediaRemuxer {

    @JvmOverloads
    fun trimVideo(
        context: Context,
        sourcePath: String,
        targetFile: File,
        startMs: Long,
        durationMs: Long,
        logAction: ((String) -> Unit)? = null
    ): Boolean {
        if (durationMs <= 0) return false

        val parentDir = targetFile.parentFile ?: context.cacheDir
        val tempOutputFile = File(parentDir, "xcam_trim_" + System.currentTimeMillis() + ".mp4")

        var muxer: MediaMuxer? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()

        try {
            val uri = sourcePath.toUri()
            videoExtractor.setDataSource(context, uri, null)
            audioExtractor.setDataSource(context, uri, null)

            var videoTrackIndex = -1
            var audioTrackIndex = -1
            var srcVideoFormat: MediaFormat? = null
            var srcAudioFormat: MediaFormat? = null

            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/") && videoTrackIndex == -1) {
                    videoTrackIndex = i
                    srcVideoFormat = format
                } else if (mime.startsWith("audio/") && audioTrackIndex == -1) {
                    audioTrackIndex = i
                    srcAudioFormat = format
                }
            }

            if (videoTrackIndex == -1 || srcVideoFormat == null) {
                logAction?.invoke("[TRANSCODE] No video track found")
                return false
            }

            val width = srcVideoFormat.getInteger(MediaFormat.KEY_WIDTH)
            val height = srcVideoFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val mime = srcVideoFormat.getString(MediaFormat.KEY_MIME) ?: "video/avc"
            val frameRateRaw = srcVideoFormat.runCatching { getInteger(MediaFormat.KEY_FRAME_RATE) }.getOrNull() ?: 30
            val frameRate = if (frameRateRaw > 0) frameRateRaw else 30
            val bitRateRaw = srcVideoFormat.runCatching { getInteger(MediaFormat.KEY_BIT_RATE) }.getOrNull() ?: 8_000_000
            val bitRate = if (bitRateRaw > 0) bitRateRaw else 8_000_000

            val encoderFormat = MediaFormat.createVideoFormat("video/avc", width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            val enc = MediaCodec.createEncoderByType("video/avc")
            encoder = enc
            enc.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = enc.createInputSurface()
            enc.start()

            val dec = MediaCodec.createDecoderByType(mime)
            decoder = dec
            dec.configure(srcVideoFormat, inputSurface, null, 0)
            dec.start()

            muxer = MediaMuxer(tempOutputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val rotation = try {
                if (srcVideoFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                    srcVideoFormat.getInteger(MediaFormat.KEY_ROTATION)
                } else 0
            } catch (_: Throwable) { 0 }
            if (rotation != 0) {
                muxer.setOrientationHint(rotation)
            }

            var muxerVideoTrack = -1
            var muxerAudioTrack = -1
            var muxerStarted = false

            val startUs = maxOf(0L, startMs * 1000L)
            val endUs = startUs + (durationMs * 1000L)

            videoExtractor.selectTrack(videoTrackIndex)
            videoExtractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val decBufferInfo = MediaCodec.BufferInfo()
            val encBufferInfo = MediaCodec.BufferInfo()
            var decoderInputEos = false
            var encoderOutputEos = false
            var eosSignaled = false
            var firstEncoderPtsUs = -1L
            val timeoutUs = 5000L
            val loopStartTimeMs = System.currentTimeMillis()

            while (!encoderOutputEos) {
                if (System.currentTimeMillis() - loopStartTimeMs > 6000) {
                    logAction?.invoke("[TRANSCODE] Loop timed out")
                    break
                }

                if (!decoderInputEos) {
                    val inIndex = dec.dequeueInputBuffer(timeoutUs)
                    if (inIndex >= 0) {
                        val inBuf = dec.getInputBuffer(inIndex)
                        if (inBuf != null) {
                            val sampleSize = videoExtractor.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                dec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                decoderInputEos = true
                            } else {
                                dec.queueInputBuffer(inIndex, 0, sampleSize, videoExtractor.sampleTime, 0)
                                videoExtractor.advance()
                            }
                        }
                    }
                }

                val outIndex = dec.dequeueOutputBuffer(decBufferInfo, timeoutUs)
                if (outIndex >= 0) {
                    val presentationUs = decBufferInfo.presentationTimeUs
                    if ((decBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        if (!eosSignaled) {
                            eosSignaled = true
                            enc.signalEndOfInputStream()
                        }
                        dec.releaseOutputBuffer(outIndex, false)
                    } else if (presentationUs in startUs..endUs) {
                        dec.releaseOutputBuffer(outIndex, presentationUs * 1000L)
                    } else {
                        if (presentationUs > endUs && !eosSignaled) {
                            eosSignaled = true
                            enc.signalEndOfInputStream()
                            decoderInputEos = true
                        }
                        dec.releaseOutputBuffer(outIndex, false)
                    }
                }

                while (true) {
                    val encOutIndex = enc.dequeueOutputBuffer(encBufferInfo, timeoutUs)
                    if (encOutIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        muxerVideoTrack = muxer.addTrack(enc.outputFormat)
                        if (srcAudioFormat != null && audioTrackIndex != -1) {
                            muxerAudioTrack = muxer.addTrack(srcAudioFormat)
                        }
                        muxer.start()
                        muxerStarted = true
                    } else if (encOutIndex >= 0) {
                        if ((encBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            encoderOutputEos = true
                            enc.releaseOutputBuffer(encOutIndex, false)
                            break
                        }
                        val encBuf = enc.getOutputBuffer(encOutIndex)
                        if (encBuf != null && muxerStarted && encBufferInfo.size > 0) {
                            if ((encBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                if (firstEncoderPtsUs < 0) {
                                    firstEncoderPtsUs = encBufferInfo.presentationTimeUs
                                }
                                val rebasedPts = maxOf(0L, encBufferInfo.presentationTimeUs - firstEncoderPtsUs)
                                encBufferInfo.presentationTimeUs = rebasedPts
                                muxer.writeSampleData(muxerVideoTrack, encBuf, encBufferInfo)
                            }
                        }
                        enc.releaseOutputBuffer(encOutIndex, false)
                    } else {
                        break
                    }
                }
            }

            if (muxerStarted && muxerAudioTrack != -1 && audioTrackIndex != -1) {
                audioExtractor.selectTrack(audioTrackIndex)
                audioExtractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                while (audioExtractor.sampleTime in 0 until startUs) {
                    if (!audioExtractor.advance()) break
                }
                val audioBuf = ByteBuffer.allocateDirect(1048576)
                val audioBufInfo = MediaCodec.BufferInfo()
                var firstAudioPtsUs = -1L
                while (true) {
                    audioBufInfo.offset = 0
                    audioBufInfo.size = audioExtractor.readSampleData(audioBuf, 0)
                    if (audioBufInfo.size < 0) break
                    val sampleTime = audioExtractor.sampleTime
                    if (sampleTime > endUs) break
                    if (firstAudioPtsUs < 0) {
                        firstAudioPtsUs = sampleTime
                    }
                    val pts = maxOf(0L, sampleTime - firstAudioPtsUs)
                    audioBufInfo.presentationTimeUs = pts
                    audioBufInfo.flags = audioExtractor.sampleFlags
                    muxer.writeSampleData(muxerAudioTrack, audioBuf, audioBufInfo)
                    if (!audioExtractor.advance()) break
                }
            }

            muxer.stop()
            muxer.release()
            muxer = null

            if (tempOutputFile.exists() && tempOutputFile.length() > 0) {
                if (targetFile.exists()) {
                    targetFile.delete()
                }
                val renamed = tempOutputFile.renameTo(targetFile)
                if (!renamed) {
                    tempOutputFile.inputStream().use { input ->
                        FileOutputStream(targetFile, false).use { output ->
                            input.copyTo(output)
                        }
                    }
                    tempOutputFile.delete()
                }
                logAction?.invoke("[TRANSCODE] Finished: ${targetFile.length()} bytes (Start: $startMs ms, Duration: $durationMs ms)")
                return true
            }
        } catch (t: Throwable) {
            logAction?.invoke("[TRANSCODE] Failed: ${t.message}")
        } finally {
            try { decoder?.stop() } catch (_: Throwable) {}
            try { decoder?.release() } catch (_: Throwable) {}
            try { encoder?.stop() } catch (_: Throwable) {}
            try { encoder?.release() } catch (_: Throwable) {}
            try { muxer?.stop() } catch (_: Throwable) {}
            try { muxer?.release() } catch (_: Throwable) {}
            try { videoExtractor.release() } catch (_: Throwable) {}
            try { audioExtractor.release() } catch (_: Throwable) {}
            if (tempOutputFile.exists()) {
                try { tempOutputFile.delete() } catch (_: Throwable) {}
            }
        }
        return false
    }
}
