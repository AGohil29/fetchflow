package io.github.agohil29.fetchflow.runtime.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

class DownloadNotificationManager(
    private val context: Context
) {
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "File Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active file downloads progress and status"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun buildProgressNotification(
        title: String,
        downloadedBytes: Long,
        totalBytes: Long?,
        progressPercent: Float?,
        downloadId: String,
        isPaused: Boolean = false
    ): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setSmallIcon(if (isPaused) android.R.drawable.stat_sys_warning else android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (totalBytes != null && progressPercent != null) {
            builder.setProgress(100, progressPercent.toInt(), false)
            builder.setContentText("${formatBytes(downloadedBytes)} / ${formatBytes(totalBytes)} (${progressPercent.toInt()}%)")
        } else {
            builder.setProgress(0, 0, true)
            builder.setContentText("Downloaded ${formatBytes(downloadedBytes)}")
        }

        if (isPaused) {
            val resumeIntent = Intent(context, DownloadActionReceiver::class.java).apply {
                action = ACTION_RESUME
                putExtra(EXTRA_DOWNLOAD_ID, downloadId)
            }
            val resumePendingIntent = PendingIntent.getBroadcast(
                context,
                downloadId.hashCode() xor 0x3333,
                resumeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_media_play, "Resume", resumePendingIntent)
        } else {
            val pauseIntent = Intent(context, DownloadActionReceiver::class.java).apply {
                action = ACTION_PAUSE
                putExtra(EXTRA_DOWNLOAD_ID, downloadId)
            }
            val pausePendingIntent = PendingIntent.getBroadcast(
                context,
                downloadId.hashCode(),
                pauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_media_pause, "Pause", pausePendingIntent)
        }

        val cancelIntent = Intent(context, DownloadActionReceiver::class.java).apply {
            action = ACTION_CANCEL
            putExtra(EXTRA_DOWNLOAD_ID, downloadId)
        }
        val cancelPendingIntent = PendingIntent.getBroadcast(
            context,
            downloadId.hashCode() xor 0x5555,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPendingIntent)

        return builder.build()
    }

    fun buildCompletionNotification(title: String, filePath: String): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Download Complete")
            .setContentText(title)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .build()
    }

    fun buildFailureNotification(title: String, errorMessage: String): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Download Failed")
            .setContentText("$title: $errorMessage")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .build()
    }

    fun notify(notificationId: Int, notification: Notification) {
        notificationManager.notify(notificationId, notification)
    }

    fun cancel(notificationId: Int) {
        notificationManager.cancel(notificationId)
    }

    private fun formatBytes(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> "%.2f GB".format(gb)
            mb >= 1.0 -> "%.2f MB".format(mb)
            kb >= 1.0 -> "%.2f KB".format(kb)
            else -> "$bytes B"
        }
    }

    companion object {
        const val CHANNEL_ID = "fetchflow_downloads"
        const val ACTION_PAUSE = "io.github.agohil29.fetchflow.action.PAUSE"
        const val ACTION_RESUME = "io.github.agohil29.fetchflow.action.RESUME"
        const val ACTION_CANCEL = "io.github.agohil29.fetchflow.action.CANCEL"
        const val EXTRA_DOWNLOAD_ID = "extra_download_id"
    }
}