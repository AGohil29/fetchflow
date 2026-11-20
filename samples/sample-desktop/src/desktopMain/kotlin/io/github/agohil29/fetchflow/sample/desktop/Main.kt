package io.github.agohil29.fetchflow.sample.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.agohil29.fetchflow.sample.common.DownloadScreen
import io.github.agohil29.fetchflow.sample.common.PlatformDownloaderFactory

fun main() = application {
    val factory = remember { PlatformDownloaderFactory() }
    val paths = remember { factory.resolvePaths() }
    val downloader = remember { factory.createDownloader(paths) }

    val windowState = rememberWindowState(width = 960.dp, height = 720.dp)

    Window(
        onCloseRequest = ::exitApplication,
        title = "FetchFlow — Desktop Sample (Compose Multiplatform)",
        state = windowState
    ) {
        DownloadScreen(
            downloader = downloader,
            paths = paths
        )
    }
}