package com.arun.downloader.sample.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.arun.downloader.sample.common.DownloadScreen
import com.arun.downloader.sample.common.PlatformDownloaderFactory
import com.arun.downloader.runtime.android.DownloadForegroundService
import com.arun.downloader.runtime.android.DownloadWorkManagerWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf

class MainActivity : ComponentActivity() {
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Permission result handled */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        enableEdgeToEdge()

        setContent {
            val factory = remember { PlatformDownloaderFactory(applicationContext) }
            val paths = remember { factory.resolvePaths() }
            val downloader = remember {
                factory.createDownloader(paths).also { instance ->
                    DownloadForegroundService.start(applicationContext, instance)
                }
            }
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colors.background
                ) {
                    DownloadScreen(
                        downloader = downloader,
                        paths = paths,
                        onEnqueueBackground = { url, fileName ->
                            val workRequest = OneTimeWorkRequestBuilder<DownloadWorkManagerWorker>()
                                .setInputData(
                                    workDataOf(
                                        DownloadWorkManagerWorker.KEY_URL to url,
                                        DownloadWorkManagerWorker.KEY_FILE_NAME to fileName
                                    )
                                )
                                .build()
                            WorkManager.getInstance(applicationContext).enqueue(workRequest)
                        }
                    )
                }
            }
        }
    }
}