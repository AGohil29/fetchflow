package io.github.agohil29.fetchflow.runtime.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.agohil29.fetchflow.core.model.DownloadId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val downloadIdRaw = intent.getStringExtra(DownloadNotificationManager.EXTRA_DOWNLOAD_ID) ?: return
        val downloadId = DownloadId(downloadIdRaw)
        DownloadForegroundService.lastInteractedId = downloadIdRaw

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                when (intent.action) {
                    DownloadNotificationManager.ACTION_PAUSE -> {
                        DownloadForegroundService.downloaderInstance?.pause(downloadId)
                    }
                    DownloadNotificationManager.ACTION_RESUME -> {
                        DownloadForegroundService.downloaderInstance?.resume(downloadId)
                    }
                    DownloadNotificationManager.ACTION_CANCEL -> {
                        DownloadForegroundService.downloaderInstance?.cancel(downloadId)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}