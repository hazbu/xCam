package com.hazbu.xcam.core.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix as AndroidMatrix
import android.media.MediaMetadataRetriever
import androidx.core.net.toUri
import java.io.ByteArrayOutputStream
import androidx.core.graphics.createBitmap

object XCamCapture {

    fun createJpeg(
        context: Context,
        path: String,
        targetW: Int,
        targetH: Int,
        rotation: Int,
        mirrored: Boolean,
        timeMs: Int = 1000,
        maxSizeBytes: Int = Int.MAX_VALUE,
        printLog: (String) -> Unit,
    ): ByteArray? {
        printLog("Capture Process: Starting for $path (Time: $timeMs ms, MaxSize: $maxSizeBytes)")
        
        var retriever: MediaMetadataRetriever? = null
        return try {
            var rawBitmap: Bitmap? = null
            
            if (path.lowercase().endsWith(".mp4")) {
                retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, path.toUri())

                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                val loopedTimeMs = if (durationMs > 0L) timeMs.toLong() % durationMs else timeMs.toLong()
                
                val targetUs = loopedTimeMs * 1000L
                rawBitmap = retriever.getFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_CLOSEST)

                if (rawBitmap == null) {
                    rawBitmap = retriever.getFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }

                if (rawBitmap == null) {
                    rawBitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_NEXT_SYNC)
                }
            } else {
                context.contentResolver.openInputStream(path.toUri())?.use { BitmapFactory.decodeStream(it) }?.let {
                    rawBitmap = it
                }
            }

            if (rawBitmap == null) return null

            // Transformasi Frame (Rotation & Mirroring)
            val sourceW = rawBitmap.width
            val sourceH = rawBitmap.height
            val rotatedSourceW = if (rotation % 180 != 0) sourceH else sourceW
            val rotatedSourceH = if (rotation % 180 != 0) sourceW else sourceH

            val scale = Math.min(targetW.toFloat() / rotatedSourceW, targetH.toFloat() / rotatedSourceH)
            val matrix = AndroidMatrix().apply {
                postScale(scale, scale)
                if (rotation != 0) postRotate(rotation.toFloat())
                if (mirrored) postScale(-1f, 1f)
            }

            val transformedSource = Bitmap.createBitmap(rawBitmap, 0, 0, sourceW, sourceH, matrix, true)
            
            val finalBitmap = createBitmap(targetW, targetH)
            android.graphics.Canvas(finalBitmap).apply {
                drawColor(android.graphics.Color.BLACK)
                val left = (targetW - transformedSource.width) / 2f
                val top = (targetH - transformedSource.height) / 2f
                drawBitmap(transformedSource, left, top, null)
            }

            // Adaptive Compression: Loop until it fits the buffer
            var result: ByteArray
            var quality = 100
            do {
                val out = ByteArrayOutputStream()
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                result = out.toByteArray()
                if (result.size <= maxSizeBytes) break
                
                quality -= 5
                printLog("Capture Process: Target size exceeded (${result.size} > $maxSizeBytes). Retrying with quality $quality")
            } while (quality > 5)

            printLog("Capture Process: SUCCESS (${result.size} bytes, quality $quality)")

            if (rawBitmap != transformedSource) rawBitmap.recycle()
            transformedSource.recycle()
            finalBitmap.recycle()
            
            result
        } catch (e: Exception) {
            printLog("Capture Process: ERROR - ${e.message}")
            null
        } finally {
            try { retriever?.release() } catch (_: Exception) {}
        }
    }
}
