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

        // 1. Setup notification channel (Android 8.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CAPTURE_NOTIFICATION_CHANNEL_ID,
                "xCam Capture Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifikasi Heads-up saat foto virtual diinjeksi"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 150, 100, 150)
            }
            notificationManager.createNotificationChannel(channel)
        }

        // 2. Resolve friendly App Name
        val pm = context.packageManager
        val appName = try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            packageName.substringAfterLast('.')
        }

        // 3. Dismiss Action PendingIntent
        val dismissIntent = Intent(context, NotificationDismissReceiver::class.java).apply {
            putExtra("notification_id", CAPTURE_NOTIFICATION_ID)
        }
        val dismissPendingIntent = PendingIntent.getBroadcast(
            context,
            CAPTURE_NOTIFICATION_ID,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 4. Open Activity PendingIntent
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            context,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 5. Decode thumbnail if available
        val thumbnailBitmap = if (thumbnailBytes != null && thumbnailBytes.isNotEmpty()) {
            try {
                BitmapFactory.decodeByteArray(thumbnailBytes, 0, thumbnailBytes.size)
            } catch (_: Throwable) {
                null
            }
        } else null

        val resolutionText = if (width > 0 && height > 0) "${width}x${height}" else "Auto"
        val bigSummary = "Aplikasi: $appName ($packageName)\nPipeline: $route\nUkuran: $resolutionText"

        val builder = NotificationCompat.Builder(context, CAPTURE_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("📸 Foto Terinjeksi: $appName")
            .setContentText("Pipeline: $route")
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigSummary))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "✕ Tutup", dismissPendingIntent)
            .addAction(android.R.drawable.ic_menu_info_details, "Buka xCam", openPendingIntent)

        if (thumbnailBitmap != null) {
            builder.setLargeIcon(thumbnailBitmap)
        }

        notificationManager.notify(CAPTURE_NOTIFICATION_ID, builder.build())
    }
}
