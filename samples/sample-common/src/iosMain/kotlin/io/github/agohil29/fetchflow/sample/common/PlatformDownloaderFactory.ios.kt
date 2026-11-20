package io.github.agohil29.fetchflow.sample.common

import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import io.github.agohil29.fetchflow.core.FileDownloader
import io.github.agohil29.fetchflow.core.model.DownloaderConfig
import io.github.agohil29.fetchflow.filesystem.OkioDownloadFileSystem
import io.github.agohil29.fetchflow.network.KtorNetworkTransport
import io.github.agohil29.fetchflow.runtime.DefaultFileDownloader
import io.github.agohil29.fetchflow.runtime.ios.IosAppDelegateHelper
import io.github.agohil29.fetchflow.runtime.ios.IosBackgroundSessionBridge
import io.github.agohil29.fetchflow.storage.db.DownloadDatabase
import io.github.agohil29.fetchflow.storage.db.DownloadRecordEntity
import io.github.agohil29.fetchflow.storage.repository.SqlDelightDownloadRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import okio.Path.Companion.toPath
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

actual class PlatformDownloaderFactory {
    actual fun resolvePaths(): PlatformPaths {
        val documentsPath = NSSearchPathForDirectoriesInDomains(
            directory = NSDocumentDirectory,
            domainMask = NSUserDomainMask,
            expandTilde = true
        ).firstOrNull() as? String ?: NSFileManager.defaultManager.currentDirectoryPath

        val cachesPath = NSSearchPathForDirectoriesInDomains(
            directory = NSCachesDirectory,
            domainMask = NSUserDomainMask,
            expandTilde = true
        ).firstOrNull() as? String ?: documentsPath

        return PlatformPaths(
            downloadDirectory = documentsPath.toPath(),
            stagingDirectory = ("$cachesPath/staging").toPath()
        )
    }

    actual fun createDownloader(paths: PlatformPaths): FileDownloader {
        val driver = NativeSqliteDriver(
            schema = DownloadDatabase.Schema,
            name = "downloader_sample.db",
            maxReaderConnections = 1
        )

        val database = DownloadDatabase(
            driver = driver,
            DownloadRecordEntityAdapter = DownloadRecordEntity.Adapter(
                priorityAdapter = EnumColumnAdapter(),
                conflict_strategyAdapter = EnumColumnAdapter()
            )
        )

        val repository = SqlDelightDownloadRepository(
            database = database,
            ioDispatcher = Dispatchers.IO
        )

        val httpClient = HttpClient(Darwin) {
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

        IosAppDelegateHelper.setupNotifications(
            downloader = downloader,
            requestPermission = true
        )

        return downloader
    }
}