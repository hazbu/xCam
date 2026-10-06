package com.hazbu.xcam.core.capture

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.hazbu.xcam.data.Constants.DEFAULT_CAPTURE_HEIGHT
import com.hazbu.xcam.data.Constants.DEFAULT_CAPTURE_WIDTH
import com.hazbu.xcam.data.Constants.STREAM_FRAME_INTERVAL_MS
import java.util.concurrent.Executors

/**
 * Manages frame extraction for still captures and video streaming.
 */
class CaptureManager(
    private val contextProvider: () -> Context?,
    private val refreshSettingsAction: (Context) -> Unit,
    private val logAction: (String) -> Unit,
) {
    private val uiHandler = Handler(Looper.getMainLooper())
    private val streamExecutor = Executors.newSingleThreadExecutor()
    private val streamFrameIntervalMs = STREAM_FRAME_INTERVAL_MS

    @Volatile var isCapturing = false
        private set

    @Volatile private var cachedCaptureFrame: ByteArray? = null
    @Volatile private var cachedStreamFrame: ByteArray? = null
    
    private var lastCapturePulseTime = 0L
    private var captureTimeMs = 0
    
    private var streamKey: String? = null
    private var streamStartedAtMs = 0L
    private var lastStreamRequestAtMs = 0L
    @Volatile private var streamExtractionRunning = false

    private fun log(msg: String) = logAction("[CAPTURE] $msg")

    fun reset() {
        uiHandler.removeCallbacksAndMessages(null)
        isCapturing = false
        cachedCaptureFrame = null
        cachedStreamFrame = null
        streamKey = null
        streamExtractionRunning = false
        log("[*] Manager state RESET")
    }

    fun triggerCaptureState(currentPositionProvider: () -> Int) {
        val now = System.currentTimeMillis()
        if ((now - lastCapturePulseTime) < 2000) return
        lastCapturePulseTime = now

        captureTimeMs = try { currentPositionProvider() } catch (_: Throwable) { 0 }
        log("[*] Pulse detected! Target Frame Time: $captureTimeMs ms")

        isCapturing = true
        cachedCaptureFrame = null

        contextProvider()?.let { refreshSettingsAction(it) }
        uiHandler.removeCallbacksAndMessages(null)
        uiHandler.postDelayed({
            isCapturing = false
            uiHandler.postDelayed({ cachedCaptureFrame = null }, 5000)
        }, 3000)
    }

    private fun performExtraction(
        context: Context,
        path: String,
        width: Int,
        height: Int,
        rotation: Int,
        mirrored: Boolean,
        timeMs: Int,
        maxSizeBytes: Int,
        setIgnoringHooks: (Boolean) -> Unit,
        liveFrame: MediaCodecYuvDecoder.YuvFrame? = null
    ): ByteArray? {
        return try {
            setIgnoringHooks(true)
            val finalW = if (width > 0) width else liveFrame?.width ?: DEFAULT_CAPTURE_WIDTH
            val finalH = if (height > 0) height else liveFrame?.height ?: DEFAULT_CAPTURE_HEIGHT
            XCamCapture.createJpeg(
                context, path, 
                finalW, 
                finalH, 
                rotation, mirrored, timeMs, maxSizeBytes, logAction,
                liveFrame
            )
        } catch (e: Throwable) {
            log("Extraction failed: ${e.message}")
            null
        } finally {
            setIgnoringHooks(false)
        }
    }

    @JvmOverloads
    fun handleCapture(
        path: String?,
        width: Int,
        height: Int,
        rotationAngle: Int,
        isMirrored: Boolean,
        maxSizeBytes: Int,
        isIgnoringHooks: () -> Boolean,
        setIgnoringHooks: (Boolean) -> Unit,
        liveFrame: MediaCodecYuvDecoder.YuvFrame? = null
    ): ByteArray? {
        if (isIgnoringHooks() || path == null) return null
        val context = contextProvider() ?: return null

        return synchronized(this) {
            cachedCaptureFrame ?: performExtraction(
                context, path, width, height, rotationAngle, isMirrored, captureTimeMs, maxSizeBytes, setIgnoringHooks, liveFrame
            ).also {
                cachedCaptureFrame = it
                if (it != null) log("[+] Capture extraction successful (liveFrame: ${liveFrame != null})")
            }
        }
    }

    fun handleStreamFrame(
        path: String?,
        width: Int,
        height: Int,
        rotationAngle: Int,
        isMirrored: Boolean,
        isIgnoringHooks: () -> Boolean,
        setIgnoringHooks: (Boolean) -> Unit
    ): ByteArray? {
        val context = contextProvider() ?: return null
        val actualPath = path ?: return null
        
        if (!actualPath.lowercase().endsWith(".mp4")) {
            return handleCapture(path, width, height, rotationAngle, isMirrored, Int.MAX_VALUE, isIgnoringHooks, setIgnoringHooks)
        }

        val now = SystemClock.elapsedRealtime()
        val key = "$actualPath|$width|$height|$rotationAngle|$isMirrored"
        
        synchronized(this) {
            if (streamKey != key) {
                streamKey = key
                cachedStreamFrame = null
                streamStartedAtMs = now
                lastStreamRequestAtMs = 0L
                streamExtractionRunning = false
            }

            if (!streamExtractionRunning && now - lastStreamRequestAtMs >= streamFrameIntervalMs) {
                streamExtractionRunning = true
                lastStreamRequestAtMs = now
                val frameTimeMs = (now - streamStartedAtMs).toInt().coerceAtLeast(0)
                
                streamExecutor.execute {
                    performExtraction(context, actualPath, width, height, rotationAngle, isMirrored, frameTimeMs, Int.MAX_VALUE, setIgnoringHooks)?.let {
                        cachedStreamFrame = it
                    }
                    synchronized(this) { streamExtractionRunning = false }
                }
            }
        }
        return cachedStreamFrame
    }
}
