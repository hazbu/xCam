package com.hazbu.xcam.core.engine

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import androidx.media3.common.util.UnstableApi
import com.hazbu.xcam.core.settings.SettingsManager
import com.hazbu.xcam.core.surface.SurfaceManager
import com.hazbu.xcam.core.surface.SurfaceProvider

@UnstableApi
class XCamEngine(
    private val contextProvider: () -> Context?,
    private val settingsProvider: () -> SettingsManager,
    private val surfaceManager: SurfaceManager,
    private val mediaEngine: MediaEngine,
    private val surfaceProvider: SurfaceProvider,
    private val logAction: (String) -> Unit,
) {
    private val uiHandler = Handler(Looper.getMainLooper())
    
    private var lastST: SurfaceTexture? = null
    private var lastModernSurface: Surface? = null
    private var lastInjectedGen = -1
    private var lastInjectedSurfaceId = -1L

    private fun log(tag: String, msg: String) = logAction("[$tag] $msg")
    private fun logPipe(msg: String) = log("PIPELINE", msg)

    fun isPlaying() = mediaEngine.isPlaying
    fun getCurrentPosition() = mediaEngine.currentPosition

    fun stop() {
        uiHandler.removeCallbacksAndMessages(null)
        mediaEngine.stop()
        // We no longer release the dummy surface here to avoid native race conditions
        // and allow faster reopening of the camera.
        lastST = null
        lastModernSurface = null
        lastInjectedSurfaceId = -1L
        lastInjectedGen = -1
    }

    fun handleCamera1Preview(st: SurfaceTexture) {
        val surface = Surface(st)
        if (!surfaceManager.isPreviewSurface(surface)) {
            surface.release()
            return
        }

        if ((st == lastST) && mediaEngine.isPlaying && lastInjectedGen == surfaceManager.sessionGeneration) return
        lastST = st
        lastInjectedGen = surfaceManager.sessionGeneration

        val path = settingsProvider().mediaPath ?: return
        val context = contextProvider() ?: return
        val settings = settingsProvider()

        logPipe("Legacy Hook: Injecting to SurfaceTexture")
        mediaEngine.play(context, path, surface, "Legacy", settings.isMirrored, settings.rotationAngle)
    }

    fun handleModernPreview(surface: Surface) {
        val currentGen = surfaceManager.sessionGeneration
        if (surface == lastModernSurface && mediaEngine.isPlaying && lastInjectedGen == currentGen) return
        lastModernSurface = surface
        uiHandler.post { processInjection(surface) }
    }

    private fun processInjection(surface: Surface) {
        val context = contextProvider() ?: return
        val path = settingsProvider().mediaPath ?: return
        injectToSurface(surface, context, path)
    }

    fun injectToSurface(surface: Surface, context: Context, path: String) {
        synchronized(this) {
            if (!surface.isValid) return
            
            val id = com.hazbu.xcam.utils.SystemUtils.getSurfaceId(surface)
            val currentGen = surfaceManager.sessionGeneration
            val settings = settingsProvider()

            if (id == lastInjectedSurfaceId && mediaEngine.isPlaying && currentGen == lastInjectedGen) return

            logPipe("Injection: ID=$id Gen=$currentGen")
            mediaEngine.stop()
            
            lastInjectedGen = currentGen
            lastInjectedSurfaceId = id
            mediaEngine.play(context, path, surface, "Engine", settings.isMirrored, settings.rotationAngle)
        }
    }

    fun getDummySurface(): Surface = surfaceProvider.getDummySurface()
}
