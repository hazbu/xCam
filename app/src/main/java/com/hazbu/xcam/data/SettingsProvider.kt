package com.hazbu.xcam.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.hazbu.xcam.data.Constants.AUTHORITY
import com.hazbu.xcam.data.Constants.KEY_IS_ENABLED
import com.hazbu.xcam.data.Constants.KEY_IS_MIRRORED
import com.hazbu.xcam.data.Constants.KEY_ROTATION_ANGLE
import com.hazbu.xcam.data.Constants.KEY_MEDIA_PATH
import com.hazbu.xcam.data.Constants.PREFS_NAME
import android.os.Bundle
import android.os.ParcelFileDescriptor
import java.io.File

class SettingsProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        when (method) {
            com.hazbu.xcam.data.Constants.METHOD_RECORD_NODE -> {
                val packageName = arg ?: "Unknown"
                val route = extras?.getString("route") ?: ""
                prefs.edit()
                    .putString(com.hazbu.xcam.data.Constants.KEY_LATEST_PIPELINE_APP, packageName)
                    .putString(com.hazbu.xcam.data.Constants.KEY_LATEST_PIPELINE_ROUTE, route)
                    .putLong(com.hazbu.xcam.data.Constants.KEY_LATEST_PIPELINE_TIME, System.currentTimeMillis())
                    .apply()
            }
            com.hazbu.xcam.data.Constants.METHOD_NOTIFY_CAPTURE -> {
                val packageName = arg ?: "Unknown"
                val route = extras?.getString("route") ?: "Standard"
                val width = extras?.getInt("width") ?: 0
                val height = extras?.getInt("height") ?: 0
                val thumbnail = extras?.getByteArray("thumbnail")

                prefs.edit()
                    .putString(com.hazbu.xcam.data.Constants.KEY_LATEST_PIPELINE_APP, packageName)
                    .putString(com.hazbu.xcam.data.Constants.KEY_LATEST_PIPELINE_ROUTE, route)
                    .putLong(com.hazbu.xcam.data.Constants.KEY_LATEST_PIPELINE_TIME, System.currentTimeMillis())
                    .apply()

                val isNotifEnabled = prefs.getBoolean(com.hazbu.xcam.data.Constants.KEY_ENABLE_CAPTURE_NOTIF, true)
                if (isNotifEnabled) {
                    com.hazbu.xcam.ui.CaptureNotificationHelper.showCaptureNotification(
                        ctx, packageName, route, width, height, thumbnail
                    )
                }
            }
        }
        return Bundle()
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val path = prefs?.getString(KEY_MEDIA_PATH, "") ?: ""
        val fileName = if (path.isNotEmpty()) File(path).name else "media"
        
        val cursor = MatrixCursor(arrayOf(KEY_MEDIA_PATH, KEY_IS_ENABLED, KEY_IS_MIRRORED, KEY_ROTATION_ANGLE))
        val videoUri = "content://$AUTHORITY/$fileName"
        
        cursor.addRow(arrayOf(
            videoUri,
            "1",
            if (prefs?.getBoolean(KEY_IS_MIRRORED, false) == true) "1" else "0",
            (prefs?.getInt(KEY_ROTATION_ANGLE, 0) ?: 0).toString()
        ))
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val context = context ?: return null
        val fileName = uri.lastPathSegment ?: return null
        val file = File(context.filesDir, fileName)
        
        if (file.exists()) {
            return try {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            } catch (_: Exception) {
                null
            }
        }

        val fallbackFile = context.filesDir.listFiles { _, name -> 
            name.startsWith("virtual.") 
        }?.firstOrNull()
        
        return try {
            fallbackFile?.let { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }
        } catch (_: Exception) {
            null
        }
    }

    override fun getType(uri: Uri): String {
        val extension = uri.path?.substringAfterLast('.', "")?.lowercase() ?: ""
        val mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        
        return when {
            mimeType?.startsWith("image/") == true -> mimeType
            mimeType?.startsWith("video/") == true -> mimeType
            else -> "video/mp4"
        }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
