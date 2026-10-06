package com.hazbu.xcam.core.capture

import android.graphics.BitmapFactory
import android.media.Image
import androidx.core.graphics.scale

/**
 * Handles conversion of JPEG/Bitmap data into YUV_420_888 format
 * for injection into camera streams.
 */
class YuvFrameProcessor {
    private var cachedSource: ByteArray? = null
    private var cachedYuv: YuvData? = null

    private class YuvData(
        val width: Int,
        val height: Int,
        val y: ByteArray,
        val u: ByteArray,
        val v: ByteArray,
    )

    fun injectYuvFrame(image: Image, frame: MediaCodecYuvDecoder.YuvFrame) {
        val planes = image.planes
        if (planes.size < 3) return

        val w = image.width.coerceAtMost(frame.width)
        val h = image.height.coerceAtMost(frame.height)
        val chromaW = ((image.width + 1) / 2).coerceAtMost((frame.width + 1) / 2)
        val chromaH = ((image.height + 1) / 2).coerceAtMost((frame.height + 1) / 2)

        writePlane(planes[0], frame.yPlane, w, h)
        writePlane(planes[1], frame.uPlane, chromaW, chromaH)
        writePlane(planes[2], frame.vPlane, chromaW, chromaH)
    }

    fun injectToImage(image: Image, jpeg: ByteArray) {
        val width = image.width
        val height = image.height
        
        val yuv = if ((cachedSource === jpeg) && (cachedYuv?.width == width) && (cachedYuv?.height == height)) {
            cachedYuv!!
        } else {
            process(jpeg, width, height).also {
                cachedSource = jpeg
                cachedYuv = it
            }
        }

        val planes = image.planes
        if (planes.size < 3) return

        writePlane(planes[0], yuv.y, width, height)
        writePlane(planes[1], yuv.u, (width + 1) / 2, (height + 1) / 2)
        writePlane(planes[2], yuv.v, (width + 1) / 2, (height + 1) / 2)
    }

    private fun process(jpeg: ByteArray, width: Int, height: Int): YuvData {
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) 
            ?: throw IllegalStateException("Failed to decode frame")
            
        val bitmap = if (decoded.width == width && decoded.height == height) decoded 
            else decoded.scale(width, height)
            
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val y = ByteArray(width * height)
        val u = ByteArray(((width + 1) / 2) * ((height + 1) / 2))
        val v = ByteArray(((width + 1) / 2) * ((height + 1) / 2))

        for (row in 0 until height) {
            for (col in 0 until width) {
                val color = pixels[row * width + col]
                val r = (color shr 16) and 0xff
                val g = (color shr 8) and 0xff
                val b = color and 0xff
                y[row * width + col] = ((77 * r + 150 * g + 29 * b) shr 8).toByte()
            }
        }

        val chromaW = (width + 1) / 2
        val chromaH = (height + 1) / 2
        for (row in 0 until chromaH) {
            for (col in 0 until chromaW) {
                val x = (col * 2).coerceAtMost(width - 1)
                val yPos = (row * 2).coerceAtMost(height - 1)
                val color = pixels[yPos * width + x]
                val r = (color shr 16) and 0xff
                val g = (color shr 8) and 0xff
                val b = color and 0xff
                val idx = row * chromaW + col
                u[idx] = (((-43 * r - 85 * g + 128 * b) shr 8) + 128).toByte()
                v[idx] = (((128 * r - 107 * g - 21 * b) shr 8) + 128).toByte()
            }
        }

        if (bitmap != decoded) bitmap.recycle()
        decoded.recycle()
        
        return YuvData(width, height, y, u, v)
    }

    private fun writePlane(plane: Image.Plane, values: ByteArray, width: Int, height: Int) {
        val buffer = plane.buffer ?: return
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val start = buffer.position()

        for (row in 0 until height) {
            val dstRow = start + row * rowStride
            val srcRow = row * width
            for (col in 0 until width) {
                buffer.put(dstRow + col * pixelStride, values[srcRow + col])
            }
        }
    }
}
