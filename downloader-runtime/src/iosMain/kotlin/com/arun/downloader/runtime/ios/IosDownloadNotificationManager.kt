package com.arun.downloader.runtime.ios

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationAction
import platform.UserNotifications.UNNotificationActionOptionDestructive
import platform.UserNotifications.UNNotificationActionOptionNone
import platform.UserNotifications.UNNotificationCategory
import platform.UserNotifications.UNNotificationCategoryOptionNone
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter

/**
 * Manages iOS local notifications for download progress, pause/resume/cancel actions,
 * completion, and failure statuses using [UNUserNotificationCenter].
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosDownloadNotificationManager {
    private val notificationCenter: UNUserNotificationCenter =
        UNUserNotificationCenter.currentNotificationCenter()

    private val lastNotificationTimes = mutableMapOf<String, Long>()

    init {
        registerCategories()
    }

    /**
     * Requests authorization for local alerts, sounds, and badges from the user.
     */
    fun requestAuthorization(completion: ((Boolean, Throwable?) -> Unit)? = null) {
        val options = UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge
        notificationCenter.requestAuthorizationWithOptions(options) { granted, error ->
            completion?.invoke(granted, error?.let { Exception(it.localizedDescription) })
        }
    }

    private fun registerCategories() {
        val pauseAction = UNNotificationAction.actionWithIdentifier(
            identifier = ACTION_PAUSE,
            title = "Pause",
            options = UNNotificationActionOptionNone
        )
        val resumeAction = UNNotificationAction.actionWithIdentifier(
            identifier = ACTION_RESUME,
            title = "Resume",
            options = UNNotificationActionOptionNone
        )
        val cancelAction = UNNotificationAction.actionWithIdentifier(
            identifier = ACTION_CANCEL,
            title = "Cancel",
            options = UNNotificationActionOptionDestructive
        )

        val downloadingCategory = UNNotificationCategory.categoryWithIdentifier(
            identifier = CATEGORY_DOWNLOADING,
            actions = listOf(pauseAction, cancelAction),
            intentIdentifiers = emptyList<String>(),
            options = UNNotificationCategoryOptionNone
        )

        val pausedCategory = UNNotificationCategory.categoryWithIdentifier(
            identifier = CATEGORY_PAUSED,
            actions = listOf(resumeAction, cancelAction),
            intentIdentifiers = emptyList<String>(),
            options = UNNotificationCategoryOptionNone
        )

        notificationCenter.setNotificationCategories(setOf(downloadingCategory, pausedCategory))
    }

    /**
     * Posts or updates a local progress notification for the specified [downloadId].
     */
    fun showProgressNotification(
        title: String,
        downloadedBytes: Long,
        totalBytes: Long?,
        progressPercent: Float?,
        downloadId: String,
        isPaused: Boolean = false,
        throttleMs: Long = 1000L
    ) {
        val now = nowMs()
        val lastTime = lastNotificationTimes[downloadId] ?: 0L
        if (!isPaused && (now - lastTime < throttleMs)) {
            return
        }
        lastNotificationTimes[downloadId] = now

        val content = UNMutableNotificationContent().apply {
            setTitle(title)
            setCategoryIdentifier(if (isPaused) CATEGORY_PAUSED else CATEGORY_DOWNLOADING)
            val bodyText = if (totalBytes != null && progressPercent != null) {
                "${formatBytes(downloadedBytes)} / ${formatBytes(totalBytes)} (${progressPercent.toInt()}%)"
            } else {
                "Downloaded ${formatBytes(downloadedBytes)}"
            }
            setBody(bodyText)
            setUserInfo(mapOf("downloadId" to downloadId))
        }

        val request = UNNotificationRequest.requestWithIdentifier(
            identifier = notificationIdentifier(downloadId),
            content = content,
            trigger = null
        )

        notificationCenter.addNotificationRequest(request, withCompletionHandler = null)
    }

    /**
     * Posts a completion notification with default sound.
     */
    fun showCompletionNotification(title: String, downloadId: String) {
        lastNotificationTimes.remove(downloadId)
        val content = UNMutableNotificationContent().apply {
            setTitle("Download Complete")
            setBody(title)
            setSound(UNNotificationSound.defaultSound)
            setUserInfo(mapOf("downloadId" to downloadId))
        }

        val request = UNNotificationRequest.requestWithIdentifier(
            identifier = notificationIdentifier(downloadId),
            content = content,
            trigger = null
        )

        notificationCenter.addNotificationRequest(request, withCompletionHandler = null)
    }

    /**
     * Posts a failure notification with default sound and error message.
     */
    fun showFailureNotification(title: String, errorMessage: String, downloadId: String) {
        lastNotificationTimes.remove(downloadId)
        val content = UNMutableNotificationContent().apply {
            setTitle("Download Failed")
            setBody("$title: $errorMessage")
            setSound(UNNotificationSound.defaultSound)
            setUserInfo(mapOf("downloadId" to downloadId))
        }

        val request = UNNotificationRequest.requestWithIdentifier(
            identifier = notificationIdentifier(downloadId),
            content = content,
            trigger = null
        )

        notificationCenter.addNotificationRequest(request, withCompletionHandler = null)
    }

    /**
     * Cancels pending and delivered notification banners for the specified [downloadId].
     */
    fun cancelNotification(downloadId: String) {
        lastNotificationTimes.remove(downloadId)
        val identifier = notificationIdentifier(downloadId)
        notificationCenter.removeDeliveredNotificationsWithIdentifiers(listOf(identifier))
        notificationCenter.removePendingNotificationRequestsWithIdentifiers(listOf(identifier))
    }

    private fun notificationIdentifier(downloadId: String): String = "download_notif_$downloadId"

    private fun formatBytes(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> "${(gb * 100).toLong() / 100.0} GB"
            mb >= 1.0 -> "${(mb * 100).toLong() / 100.0} MB"
            kb >= 1.0 -> "${(kb * 100).toLong() / 100.0} KB"
            else -> "$bytes B"
        }
    }

    private fun nowMs(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

    companion object {
        const val CATEGORY_DOWNLOADING = "com.arun.downloader.category.DOWNLOADING"
        const val CATEGORY_PAUSED = "com.arun.downloader.category.PAUSED"

        const val ACTION_PAUSE = "com.arun.downloader.action.PAUSE"
        const val ACTION_RESUME = "com.arun.downloader.action.RESUME"
        const val ACTION_CANCEL = "com.arun.downloader.action.CANCEL"
    }
}
