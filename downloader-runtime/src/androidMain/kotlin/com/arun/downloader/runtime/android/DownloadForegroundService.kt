package com.arun.downloader.runtime.android

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.arun.downloader.core.FileDownloader
import com.arun.downloader.core.model.DownloadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class DownloadForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var notificationManager: DownloadNotificationManager
    private var observationJob: Job? = null
    private var isForegroundStarted = false

    override fun onCreate() {
        super.onCreate()
        notificationManager = DownloadNotificationManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val initialNotification = notificationManager.buildProgressNotification(
            title = "File Downloader Service",
            downloadedBytes = 0L,
            totalBytes = null,
            progressPercent = null,
            downloadId = "foreground_service"
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }
        isForegroundStarted = true

        observeDownloader()

        return START_STICKY
    }

    private fun observeDownloader() {
        val downloader = downloaderInstance ?: return
        observationJob?.cancel()

        observationJob = serviceScope.launch {
            downloader.observeAll().collect { states ->
                val activeDownloads = states.filter {
                    it is DownloadState.Downloading ||
                            it is DownloadState.Connecting ||
                            it is DownloadState.Queued ||
                            it is DownloadState.Paused
                }

                if (activeDownloads.isEmpty()) {
                    if (isForegroundStarted) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        isForegroundStarted = false
                    }
                    return@collect
                }

                val primary = activeDownloads.firstOrNull { it.id.raw == lastInteractedId }
                    ?: activeDownloads.firstOrNull { it is DownloadState.Downloading }
                    ?: activeDownloads.firstOrNull { it is DownloadState.Connecting }
                    ?: activeDownloads.firstOrNull { it is DownloadState.Queued }
                    ?: activeDownloads.first()

                val notification = when (primary) {
                    is DownloadState.Downloading -> {
                        val percent = primary.totalBytes?.let { total ->
                            if (total > 0L) (primary.downloadedBytes.toFloat() / total) * 100f else null
                        }
                        notificationManager.buildProgressNotification(
                            title = "Downloading: ${primary.id.raw}",
                            downloadedBytes = primary.downloadedBytes,
                            totalBytes = primary.totalBytes,
                            progressPercent = percent,
                            downloadId = primary.id.raw,
                            isPaused = false
                        )
                    }
                    is DownloadState.Paused -> {
                        val percent = primary.totalBytes?.let { total ->
                            if (total > 0L) (primary.downloadedBytes.toFloat() / total) * 100f else null
                        }
                        notificationManager.buildProgressNotification(
                            title = "Paused: ${primary.id.raw}",
                            downloadedBytes = primary.downloadedBytes,
                            totalBytes = primary.totalBytes,
                            progressPercent = percent,
                            downloadId = primary.id.raw,
                            isPaused = true
                        )
                    }
                    is DownloadState.Connecting -> {
                        notificationManager.buildProgressNotification(
                            title = "Connecting: ${primary.id.raw}",
                            downloadedBytes = 0L,
                            totalBytes = null,
                            progressPercent = null,
                            downloadId = primary.id.raw,
                            isPaused = false
                        )
                    }
                    else -> {
                        notificationManager.buildProgressNotification(
                            title = "Queued: ${primary.id.raw}",
                            downloadedBytes = 0L,
                            totalBytes = null,
                            progressPercent = null,
                            downloadId = primary.id.raw,
                            isPaused = false
                        )
                    }
                }

                if (!isForegroundStarted) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        ServiceCompat.startForeground(
                            this@DownloadForegroundService,
                            NOTIFICATION_ID,
                            notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                        )
                    } else {
                        startForeground(NOTIFICATION_ID, notification)
                    }
                    isForegroundStarted = true
                } else {
                    notificationManager.notify(NOTIFICATION_ID, notification)
                }
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIFICATION_ID = 9999

        @Volatile
        var downloaderInstance: FileDownloader? = null

        @Volatile
        var lastInteractedId: String? = null

        fun start(context: Context, downloader: FileDownloader) {
            downloaderInstance = downloader
            val intent = Intent(context, DownloadForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, DownloadForegroundService::class.java)
            context.stopService(intent)
        }
    }
}