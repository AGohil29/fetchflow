# iOS Local Download Notifications Integration

`downloader-runtime` provides built-in support for displaying local notifications on iOS during file downloads, featuring progress updates, interactive notification action buttons (**Pause**, **Resume**, **Cancel**), and completion/failure alert banners with sound.

---

## 1. Capabilities & Framework Overview

iOS notifications use Apple's `UserNotifications` framework (`UNUserNotificationCenter`).

| Concern                | Implementation                                                                                                                                                       |
|:-----------------------|:---------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Permissions**        | `IosDownloadNotificationManager.requestAuthorization()` requests local alert, sound, and badge permissions.                                                          |
| **Progress Display**   | Formatted text (`1.20 MB / 5.00 MB (24%)` or `Downloaded 1.20 MB`) updated dynamically using `UNNotificationRequest`.                                                |
| **Action Buttons**     | Registers `UNNotificationCategory` for downloading/paused states with `ACTION_PAUSE`, `ACTION_RESUME`, and `ACTION_CANCEL`.                                          |
| **Action Handling**    | `IosNotificationActionDelegate` (`UNUserNotificationCenterDelegateProtocol`) intercepts user taps and invokes `downloader.pause(id)`, `resume(id)`, or `cancel(id)`. |
| **Foreground Display** | Displays notification banners and plays sounds even while the application is in the foreground.                                                                      |

---

## 2. Integration in Swift Application Delegate

In your iOS application's Swift entry point or AppDelegate:

```swift
import SwiftUI
import DownloaderRuntime

class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // Option 1: Attach notifications using IosAppDelegateHelper
        let downloader = PlatformDownloaderFactory().createDownloader(paths: paths)
        IosAppDelegateHelper.shared.setupNotifications(downloader: downloader)
        return true
    }

    func application(
        _ application: UIApplication,
        handleEventsForBackgroundURLSession identifier: String,
        completionHandler: @escaping () -> Void
    ) {
        // Forward background session events to KMP bridge
        IosAppDelegateHelper.shared.handleEventsForBackgroundURLSession(
            identifier: identifier,
            completionHandler: completionHandler
        )
    }
}
```

---

## 3. Kotlin/Native Direct Setup

If creating the downloader from Kotlin:

```kotlin
val downloader = DefaultFileDownloader(...)

// Initialize notifications and start observing download flows
val observer = IosAppDelegateHelper.setupNotifications(
    downloader = downloader,
    requestPermission = true
)
```

---

## 4. Components Reference

- **`IosDownloadNotificationManager`**: Wraps `UNUserNotificationCenter` for posting progress, completion, failure, and category registrations.
- **`IosNotificationActionDelegate`**: Implements `UNUserNotificationCenterDelegateProtocol` for handling notification button taps and foreground banner presentation.
- **`IosDownloadNotificationObserver`**: Reactive coroutine observer collecting `downloader.observeAll()` and updating iOS notification alerts.
