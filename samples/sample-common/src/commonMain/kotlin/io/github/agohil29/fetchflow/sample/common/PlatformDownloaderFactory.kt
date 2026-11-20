package io.github.agohil29.fetchflow.sample.common

import io.github.agohil29.fetchflow.core.FileDownloader
import okio.Path

data class PlatformPaths(
    val downloadDirectory: Path,
    val stagingDirectory: Path
)

expect class PlatformDownloaderFactory {
    fun resolvePaths(): PlatformPaths
    fun createDownloader(paths: PlatformPaths): FileDownloader
}