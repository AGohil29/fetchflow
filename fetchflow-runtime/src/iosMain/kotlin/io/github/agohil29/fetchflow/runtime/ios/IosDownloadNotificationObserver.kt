package io.github.agohil29.fetchflow.runtime.ios

import io.github.agohil29.fetchflow.core.FileDownloader
import io.github.agohil29.fetchflow.core.model.DownloadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * Observes active downloads on a [FileDownloader] and automatically updates local iOS notifications
 * using [IosDownloadNotificationManager].
 */
class IosDownloadNotificationObserver(
    private val downloader: FileDownloader,
    private val notificationManager: IosDownloadNotificationManager = IosDownloadNotificationManager()
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    /**
     * Starts observing downloads and updating notifications.
     */
    fun start() {
        if (job != null) return
        job = scope.launch {
            downloader.observeAll()
                .catch { /* ignore observation errors */ }
                .collect { states ->
                    states.forEach { state ->
                        updateNotificationForState(state)
                    }
                }
        }
    }

    /**
     * Stops observing downloads.
     */
    fun stop() {
        job?.cancel()
        job = null
    }

    private fun updateNotificationForState(state: DownloadState) {
        val downloadId = state.id.raw
        val title = downloadId

        when (state) {
            is DownloadState.Downloading -> {
                val percent = state.progressPercentage

                notificationManager.showProgressNotification(
                    title = "Downloading $title",
                    downloadedBytes = state.downloadedBytes,
                    totalBytes = state.totalBytes,
                    progressPercent = percent,
                    downloadId = downloadId,
                    isPaused = false
                )
            }
            is DownloadState.Connecting -> {
                notificationManager.showProgressNotification(
                    title = "Connecting $title",
                    downloadedBytes = 0L,
                    totalBytes = null,
                    progressPercent = null,
                    downloadId = downloadId,
                    isPaused = false
                )
            }
            is DownloadState.Paused -> {
                val percent = state.totalBytes?.let { total ->
                    if (total > 0L) (state.downloadedBytes.toFloat() / total.toFloat()) * 100f else null
                }

                notificationManager.showProgressNotification(
                    title = "Paused $title",
                    downloadedBytes = state.downloadedBytes,
                    totalBytes = state.totalBytes,
                    progressPercent = percent,
                    downloadId = downloadId,
                    isPaused = true
                )
            }
            is DownloadState.Completed -> {
                notificationManager.showCompletionNotification(
                    title = title,
                    downloadId = downloadId
                )
            }
            is DownloadState.Failed -> {
                val errorMsg = state.error.message
                notificationManager.showFailureNotification(
                    title = title,
                    errorMessage = errorMsg,
                    downloadId = downloadId
                )
            }
            is DownloadState.Cancelled -> {
                notificationManager.cancelNotification(downloadId = downloadId)
            }
            is DownloadState.Queued, is DownloadState.Idle, is DownloadState.Validating -> {
                // No notification needed for idle/queued states
            }
        }
    }
}
