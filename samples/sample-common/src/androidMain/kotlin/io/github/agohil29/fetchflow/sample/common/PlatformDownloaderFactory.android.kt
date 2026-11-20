package io.github.agohil29.fetchflow.sample.common

import android.content.Context
import android.os.Environment
import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.github.agohil29.fetchflow.core.FileDownloader
import io.github.agohil29.fetchflow.core.model.DownloaderConfig
import io.github.agohil29.fetchflow.filesystem.OkioDownloadFileSystem
import io.github.agohil29.fetchflow.network.KtorNetworkTransport
import io.github.agohil29.fetchflow.runtime.DefaultFileDownloader
import io.github.agohil29.fetchflow.storage.db.DownloadDatabase
import io.github.agohil29.fetchflow.storage.db.DownloadRecordEntity
import io.github.agohil29.fetchflow.storage.repository.SqlDelightDownloadRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.Dispatchers
import okio.Path.Companion.toOkioPath

actual class PlatformDownloaderFactory(
    private val context: Context
) {
    actual fun resolvePaths(): PlatformPaths {
        val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            ?.toOkioPath() ?: context.filesDir.toOkioPath()
        val staging = context.cacheDir.toOkioPath()

        return PlatformPaths(
            downloadDirectory = publicDownloads,
            stagingDirectory = staging
        )
    }

    actual fun createDownloader(paths: PlatformPaths): FileDownloader {
        val driver = AndroidSqliteDriver(
            schema = DownloadDatabase.Schema,
            context = context,
            name = "downloader_sample.db"
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

        val httpClient = HttpClient(OkHttp) {
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

        return DefaultFileDownloader(
            config = config,
            repository = repository,
            network = KtorNetworkTransport(httpClient),
            fileSystem = OkioDownloadFileSystem()
        )
    }
}