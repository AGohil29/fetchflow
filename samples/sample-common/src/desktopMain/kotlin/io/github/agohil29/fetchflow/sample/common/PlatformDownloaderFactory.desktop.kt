package io.github.agohil29.fetchflow.sample.common

import io.github.agohil29.fetchflow.core.FileDownloader
import io.github.agohil29.fetchflow.core.model.DownloaderConfig
import io.github.agohil29.fetchflow.filesystem.OkioDownloadFileSystem
import io.github.agohil29.fetchflow.network.KtorNetworkTransport
import io.github.agohil29.fetchflow.runtime.DefaultFileDownloader
import io.github.agohil29.fetchflow.runtime.desktop.DesktopPathResolver
import io.github.agohil29.fetchflow.runtime.desktop.DesktopShutdownHook
import io.github.agohil29.fetchflow.storage.db.DatabaseDriverFactory
import io.github.agohil29.fetchflow.storage.db.createDownloadDatabase
import io.github.agohil29.fetchflow.storage.repository.SqlDelightDownloadRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.Dispatchers
import okio.Path.Companion.toPath
import java.io.File

actual class PlatformDownloaderFactory {
    actual fun resolvePaths(): PlatformPaths {
        val userHome = System.getProperty("user.home") ?: "."
        val downloads = File(userHome, "Downloads").apply { mkdirs() }
        val staging = DesktopPathResolver.getDefaultDownloadDirectory()
        return PlatformPaths(
            downloadDirectory = downloads.absolutePath.toPath(),
            stagingDirectory = staging
        )
    }

    actual fun createDownloader(paths: PlatformPaths): FileDownloader {
        val dbDir = DesktopPathResolver.getDefaultDatabaseDirectory().toFile().apply { mkdirs() }
        val dbFile = File(dbDir, "downloader_sample.db")

        // Use the library factory: it creates the schema for new files and upgrades existing
        // databases (e.g. adds final_path) instead of silently ignoring "already created" errors.
        val database = createDownloadDatabase(DatabaseDriverFactory(dbFile))

        val repository = SqlDelightDownloadRepository(
            database = database,
            ioDispatcher = Dispatchers.IO
        )

        val httpClient = HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = null
                connectTimeoutMillis = 30_000L
                socketTimeoutMillis = 30_000L
            }
        }

        val config = DownloaderConfig(
            maxConcurrentDownloads = 3,
            stagingDirectory = paths.stagingDirectory
        )

        val downloader = DefaultFileDownloader(
            config = config,
            repository = repository,
            network = KtorNetworkTransport(httpClient),
            fileSystem = OkioDownloadFileSystem()
        )

        DesktopShutdownHook.register {
            downloader.shutdown()
        }

        return downloader
    }
}