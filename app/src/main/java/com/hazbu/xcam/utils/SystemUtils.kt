package com.hazbu.xcam.utils

import android.annotation.SuppressLint
import android.app.Application
import android.os.Build
import android.view.Surface
import java.io.File

object SystemUtils {

    fun getProcessNameStrict(): String {
        return if (Build.VERSION.SDK_INT < 31) {
            try {
                File("/proc/self/cmdline").readText().trim { it <= ' ' }
            } catch (_: Exception) {
                Application.getProcessName()
            }
        } else {
            Application.getProcessName()
        }
    }

    fun getCurrentApplication(): Application? {
        return try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentAppMethod = activityThreadClass.getMethod("currentApplication")
            currentAppMethod.invoke(null) as? Application
        } catch (_: Throwable) {
            null
        }
    }

    @SuppressLint("DiscouragedPrivateApi")
    fun getSurfaceId(surface: Surface?): Long {
        if (surface == null) return -1L
        return try {
            val field = Surface::class.java.getDeclaredField("mNativeObject")
            field.isAccessible = true
            field.getLong(surface)
        } catch (_: Throwable) {
            surface.hashCode().toLong()
        }
    }
}
