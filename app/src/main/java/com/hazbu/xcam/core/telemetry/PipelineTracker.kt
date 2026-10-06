package com.hazbu.xcam.core.telemetry

import android.content.Context
import android.os.Bundle
import androidx.core.net.toUri
import com.hazbu.xcam.data.Constants.AUTHORITY
import com.hazbu.xcam.data.Constants.METHOD_NOTIFY_CAPTURE
import com.hazbu.xcam.data.Constants.METHOD_RECORD_NODE
import java.util.Collections
import java.util.concurrent.Executors

/**
 * Tracks the hook pipeline nodes executed by the target application process
 * and reports telemetry / capture notifications to xCam's SettingsProvider.
 */
object PipelineTracker {
    private val activeNodes = Collections.synchronizedSet(LinkedHashSet<String>())
    private val executor = Executors.newSingleThreadExecutor()

    fun reset() {
        activeNodes.clear()
    }

    fun recordNode(context: Context?, node: String) {
        if (activeNodes.add(node)) {
            val ctx = context ?: return
            executor.execute {
                try {
                    val extras = Bundle().apply {
                        putString("node", node)
                        putString("route", getRouteSummary())
                    }
                    ctx.contentResolver.call(
                        "content://$AUTHORITY".toUri(),
                        METHOD_RECORD_NODE,
                        ctx.packageName,
                        extras
                    )
                } catch (_: Throwable) {}
            }
        }
    }

    fun getRouteSummary(): String {
        synchronized(activeNodes) {
            return if (activeNodes.isEmpty()) "Default" else activeNodes.joinToString(" ➔ ")
        }
    }

    fun reportCapture(context: Context?, width: Int, height: Int, thumbnail: ByteArray? = null) {
        val ctx = context ?: return
        val currentRoute = getRouteSummary()
        executor.execute {
            try {
                val extras = Bundle().apply {
                    putString("route", currentRoute)
                    putInt("width", width)
                    putInt("height", height)
                    if (thumbnail != null && thumbnail.size <= 256 * 1024) {
                        putByteArray("thumbnail", thumbnail)
                    }
                }
                ctx.contentResolver.call(
                    "content://$AUTHORITY".toUri(),
                    METHOD_NOTIFY_CAPTURE,
                    ctx.packageName,
                    extras
                )
            } catch (_: Throwable) {}
        }
    }
}
