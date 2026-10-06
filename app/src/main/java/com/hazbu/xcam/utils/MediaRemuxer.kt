package com.hazbu.xcam.utils

import android.content.Context
import android.media.MediaCodec
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
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()

        try {
            val uri = sourcePath.toUri()
            videoExtractor.setDataSource(context, uri, null)
            audioExtractor.setDataSource(context, uri, null)

            var videoTrackIndex = -1
            var audioTrackIndex = -1
            var maxBufferSize = 1048576

            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/") && videoTrackIndex == -1) {
                    videoTrackIndex = i
                    if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        val bufSize = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                        if (bufSize > maxBufferSize) maxBufferSize = bufSize
                    }
                } else if (mime.startsWith("audio/") && audioTrackIndex == -1) {
                    audioTrackIndex = i
                }
            }

            if (videoTrackIndex == -1) {
                logAction?.invoke("[REMUX] No video track found in source")
                return false
            }

            muxer = MediaMuxer(tempOutputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val videoMuxerTrack = muxer.addTrack(videoExtractor.getTrackFormat(videoTrackIndex))
            var audioMuxerTrack = -1
            if (audioTrackIndex != -1) {
                audioMuxerTrack = muxer.addTrack(audioExtractor.getTrackFormat(audioTrackIndex))
            }
            muxer.start()

            val startUs = maxOf(0L, startMs * 1000L)
            val endUs = startUs + (durationMs * 1000L)

            videoExtractor.selectTrack(videoTrackIndex)
            videoExtractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            var actualStartUs = videoExtractor.sampleTime
            if (actualStartUs < 0) actualStartUs = startUs

            if (audioTrackIndex != -1) {
                audioExtractor.selectTrack(audioTrackIndex)
                audioExtractor.seekTo(actualStartUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }

            val buffer = ByteBuffer.allocateDirect(maxBufferSize)
            val bufferInfo = MediaCodec.BufferInfo()

            while (true) {
                bufferInfo.offset = 0
                bufferInfo.size = videoExtractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) break

                val sampleTime = videoExtractor.sampleTime
                if (sampleTime > endUs) break

                val pts = sampleTime - actualStartUs
                if (pts >= 0) {
                    bufferInfo.presentationTimeUs = pts
                    bufferInfo.flags = videoExtractor.sampleFlags
                    muxer.writeSampleData(videoMuxerTrack, buffer, bufferInfo)
                }
                if (!videoExtractor.advance()) break
            }

            if (audioTrackIndex != -1 && audioMuxerTrack != -1) {
                while (true) {
                    bufferInfo.offset = 0
                    bufferInfo.size = audioExtractor.readSampleData(buffer, 0)
                    if (bufferInfo.size < 0) break

                    val sampleTime = audioExtractor.sampleTime
                    if (sampleTime > endUs) break

                    val pts = sampleTime - actualStartUs
                    if (pts >= 0) {
                        bufferInfo.presentationTimeUs = pts
                        bufferInfo.flags = audioExtractor.sampleFlags
                        muxer.writeSampleData(audioMuxerTrack, buffer, bufferInfo)
                    }
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
                logAction?.invoke("[REMUX] Trimmed video successfully: ${targetFile.length()} bytes (Start: ${actualStartUs / 1000} ms, Duration: $durationMs ms)")
                return true
            }
        } catch (t: Throwable) {
            logAction?.invoke("[REMUX] Trimming failed: ${t.message}")
        } finally {
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
