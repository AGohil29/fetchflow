package io.github.agohil29.fetchflow.runtime.ios

import io.github.agohil29.fetchflow.core.FileDownloader
import io.github.agohil29.fetchflow.core.model.DownloadId
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationPresentationOptionBanner
import platform.UserNotifications.UNNotificationPresentationOptionSound
import platform.UserNotifications.UNNotificationResponse
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNUserNotificationCenterDelegateProtocol
import platform.darwin.NSObject

/**
 * Delegate for [UNUserNotificationCenter] handling iOS notification action button taps
 * (Pause, Resume, Cancel) and foreground notification presentation options.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosNotificationActionDelegate(
    var downloader: FileDownloader? = null
) : NSObject(), UNUserNotificationCenterDelegateProtocol {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun userNotificationCenter(
        center: UNUserNotificationCenter,
        didReceiveNotificationResponse: UNNotificationResponse,
        withCompletionHandler: () -> Unit
    ) {
        val actionIdentifier = didReceiveNotificationResponse.actionIdentifier
        val userInfo = didReceiveNotificationResponse.notification.request.content.userInfo
        val downloadIdRaw = userInfo["downloadId"] as? String

        if (downloadIdRaw != null && downloader != null) {
            val downloadId = DownloadId(downloadIdRaw)
            val currentDownloader = downloader!!

            scope.launch {
                when (actionIdentifier) {
                    IosDownloadNotificationManager.ACTION_PAUSE -> {
                        currentDownloader.pause(downloadId)
                    }
                    IosDownloadNotificationManager.ACTION_RESUME -> {
                        currentDownloader.resume(downloadId)
                    }
                    IosDownloadNotificationManager.ACTION_CANCEL -> {
                        currentDownloader.cancel(downloadId)
                    }
                }
            }
        }

        withCompletionHandler()
    }

    override fun userNotificationCenter(
        center: UNUserNotificationCenter,
        willPresentNotification: UNNotification,
        withCompletionHandler: (ULong) -> Unit
    ) {
        // Display banner and play sound even when the app is in the foreground
        val options = UNNotificationPresentationOptionBanner or UNNotificationPresentationOptionSound
        withCompletionHandler(options)
    }
}
