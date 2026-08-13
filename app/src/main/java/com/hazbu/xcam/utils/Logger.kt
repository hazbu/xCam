package com.hazbu.xcam.utils

import android.util.Log
import io.github.libxposed.api.XposedInterface

object Logger {
    private const val TAG = "xCam"
    private const val VERSION = "v22.14-master"

    enum class Level(val xPriority: Int) {
        DEBUG(XposedInterface.PRIORITY_DEFAULT),
        INFO(XposedInterface.PRIORITY_DEFAULT),
        WARN(XposedInterface.PRIORITY_DEFAULT),
        ERROR(XposedInterface.PRIORITY_DEFAULT)
    }

    fun d(xi: XposedInterface?, msg: String) = printLog(xi, msg, Level.DEBUG)
    fun i(xi: XposedInterface?, msg: String) = printLog(xi, msg, Level.INFO)
    fun e(xi: XposedInterface?, msg: String, tr: Throwable? = null) = printLog(xi, msg, Level.ERROR, tr)

    fun printLog(xi: XposedInterface?, msg: String, level: Level = Level.ERROR, tr: Throwable? = null) {
        val fullMsg = "[$VERSION] $msg"
        
        if (xi != null) {
            val finalMsg = if (tr != null) "$fullMsg\n${Log.getStackTraceString(tr)}" else fullMsg
            xi.log(level.xPriority, TAG, finalMsg)
        } else {
            when (level) {
                Level.DEBUG -> Log.d(TAG, fullMsg)
                Level.INFO -> Log.i(TAG, fullMsg)
                Level.WARN -> Log.w(TAG, fullMsg, tr)
                Level.ERROR -> Log.e(TAG, fullMsg, tr)
            }
        }
    }
}
