package com.arun.downloader.runtime.ios

import com.arun.downloader.core.FileDownloader
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.UserNotifications.UNUserNotificationCenter

/**
 * Public helper consumed by iOS application delegates:
 *
 * In Swift AppDelegate:
 * ```swift
 * func application(
 *     _ application: UIApplication,
 *     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
 * ) -> Bool {
 *     IosAppDelegateHelper.shared.setupNotifications(downloader: downloader)
 *     return true
 * }
 *
 * func application(
 *     _ application: UIApplication,
 *     handleEventsForBackgroundURLSession identifier: String,
 *     completionHandler: @escaping () -> Void
 * ) {
 *     IosAppDelegateHelper.shared.handleEventsForBackgroundURLSession(
 *         identifier: identifier,
 *         completionHandler: completionHandler
 *     )
 * }
 * ```
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
object IosAppDelegateHelper {
    private var bridge: IosBackgroundSessionBridge? = null
    private var notificationObserver: IosDownloadNotificationObserver? = null
    val notificationActionDelegate: IosNotificationActionDelegate = IosNotificationActionDelegate()

    // The OS may call the app delegate before the downloader (and its bridge) is created.
    private val pendingHandlers = mutableMapOf<String, () -> Unit>()

    fun attachBridge(activeBridge: IosBackgroundSessionBridge) {
        bridge = activeBridge
        pendingHandlers.forEach { (identifier, handler) ->
            activeBridge.registerBackgroundCompletionHandler(identifier, handler)
        }
        pendingHandlers.clear()
    }

    fun handleEventsForBackgroundURLSession(identifier: String, completionHandler: () -> Unit) {
        val activeBridge = bridge
        if (activeBridge != null) {
            activeBridge.registerBackgroundCompletionHandler(identifier, completionHandler)
        } else {
            // Keep the handler until the bridge is attached; it runs once all events are processed.
            pendingHandlers[identifier] = completionHandler
        }
    }

    /**
     * Initializes local download notifications on iOS.
     * Sets the notification center delegate, requests user permissions, and starts observing download states.
     */
    fun setupNotifications(
        downloader: FileDownloader,
        requestPermission: Boolean = true
    ): IosDownloadNotificationObserver {
        notificationActionDelegate.downloader = downloader

        val manager = IosDownloadNotificationManager()
        if (requestPermission) {
            manager.requestAuthorization()
        }

        UNUserNotificationCenter.currentNotificationCenter().delegate = notificationActionDelegate

        notificationObserver?.stop()
        val observer = IosDownloadNotificationObserver(
            downloader = downloader,
            notificationManager = manager
        )
        observer.start()
        notificationObserver = observer

        return observer
    }
}
