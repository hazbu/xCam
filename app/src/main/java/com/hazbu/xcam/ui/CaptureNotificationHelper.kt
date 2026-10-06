package com.hazbu.xcam.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import com.hazbu.xcam.R
import com.hazbu.xcam.data.Constants.CAPTURE_NOTIFICATION_CHANNEL_ID
import com.hazbu.xcam.data.Constants.CAPTURE_NOTIFICATION_ID

object CaptureNotificationHelper {

    fun showCaptureNotification(
        context: Context,
        packageName: String,
        route: String,
        width: Int,
        height: Int,
        thumbnailBytes: ByteArray?
    ) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CAPTURE_NOTIFICATION_CHANNEL_ID,
                "xCam Capture Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Heads-up alert when virtual photo is injected"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 150, 100, 150)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val pm = context.packageManager
        val appName = try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            packageName.substringAfterLast('.')
        }

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            context,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val thumbnailBitmap = if (thumbnailBytes != null && thumbnailBytes.isNotEmpty()) {
            try {
                BitmapFactory.decodeByteArray(thumbnailBytes, 0, thumbnailBytes.size)
            } catch (_: Throwable) {
                null
            }
        } else null

        val resolutionText = if (width > 0 && height > 0) "${width}x${height}" else "Auto"
        val bigSummary = "App: $appName ($packageName)\nPipeline: $route\nSize: $resolutionText"

        val builder = NotificationCompat.Builder(context, CAPTURE_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("xCam: $appName")
            .setContentText("Pipeline: $route")
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigSummary))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(openPendingIntent)

        if (thumbnailBitmap != null) {
            builder.setLargeIcon(thumbnailBitmap)
        }

        notificationManager.notify(CAPTURE_NOTIFICATION_ID, builder.build())
    }
}
