package io.github.agohil29.fetchflow.runtime.benchmark

import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.agohil29.fetchflow.core.model.ConflictStrategy
import io.github.agohil29.fetchflow.core.model.DownloadId
import io.github.agohil29.fetchflow.core.model.DownloadPriority
import io.github.agohil29.fetchflow.core.model.DownloadRequest
import io.github.agohil29.fetchflow.core.model.DownloaderConfig
import io.github.agohil29.fetchflow.core.model.NetworkRequest
import io.github.agohil29.fetchflow.core.model.NetworkResponse
import io.github.agohil29.fetchflow.core.repository.NetworkTransport
import io.github.agohil29.fetchflow.core.scheduler.SchedulerQueue
import io.github.agohil29.fetchflow.filesystem.OkioDownloadFileSystem
import io.github.agohil29.fetchflow.runtime.DefaultFileDownloader
import io.github.agohil29.fetchflow.storage.db.DownloadDatabase
import io.github.agohil29.fetchflow.storage.db.DownloadRecordEntity
import io.github.agohil29.fetchflow.storage.repository.SqlDelightDownloadRepository
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.measureTime

class DownloaderPerformanceBenchmarkTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeFileSystem: FakeFileSystem
    private lateinit var downloadFileSystem: OkioDownloadFileSystem
    private lateinit var repository: SqlDelightDownloadRepository

    private val stagingDir = "/staging".toPath()
    private val downloadDir = "/downloads".toPath()

    @BeforeTest
    fun setup() {
        fakeFileSystem = FakeFileSystem()
        downloadFileSystem = OkioDownloadFileSystem(fakeFileSystem)

        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DownloadDatabase.Schema.create(driver)

        val database = DownloadDatabase(
            driver = driver,
            DownloadRecordEntityAdapter = DownloadRecordEntity.Adapter(
                priorityAdapter = EnumColumnAdapter(),
                conflict_strategyAdapter = EnumColumnAdapter()
            )
        )

        repository = SqlDelightDownloadRepository(
            database = database,
            ioDispatcher = testDispatcher
        )

        fakeFileSystem.createDirectories(stagingDir)
        fakeFileSystem.createDirectories(downloadDir)
    }

    @Test
    fun benchmarkStreamingThroughput10MbFile() = runTest(testDispatcher) {
        val targetSizeBytes = 10 * 1024 * 1024 // 10 MB payload
        val chunkBuffer = ByteArray(64 * 1024) { (it % 256).toByte() }

        val mockNetwork = object : NetworkTransport {
            override suspend fun execute(request: NetworkRequest): NetworkResponse {
                val channel = ByteChannel(autoFlush = false)
                // Stream 10 MB in 64KB blocks
                backgroundScope.launch {
                    var written = 0
                    while (written < targetSizeBytes) {
                        val toWrite = minOf(chunkBuffer.size, targetSizeBytes - written)
                        channel.writeFully(chunkBuffer, 0, toWrite)
                        written += toWrite
                    }
                    channel.close()
                }

                return NetworkResponse(
                    statusCode = 200,
                    headers = mapOf("Content-Length" to targetSizeBytes.toString()),
                    bodyChannel = channel,
                    contentLength = targetSizeBytes.toLong(),
                    isPartialContent = false,
                    etag = "\"bench-etag\"",
                    lastModified = null
                )
            }

            override fun close() {}
        }

        val config = DownloaderConfig(
            maxConcurrentDownloads = 1,
            stagingDirectory = stagingDir,
            progressUpdateIntervalMs = 1000L
        )
        val downloader = DefaultFileDownloader(config, repository, mockNetwork, downloadFileSystem)

        val request = DownloadRequest(
            url = "https://mock.com/10mb.bin",
            destinationDirectory = downloadDir,
            fileName = "10mb.bin",
            conflictStrategy = ConflictStrategy.OVERWRITE
        )

        val duration = measureTime {
            downloader.enqueue(request)
            testDispatcher.scheduler.advanceUntilIdle()
        }

        val megabytes = targetSizeBytes.toDouble() / (1024.0 * 1024.0)
        val seconds = duration.inWholeMilliseconds / 1000.0
        val throughputMbPerSec = if (seconds > 0) megabytes / seconds else megabytes

        println("BENCHMARK [Streaming Throughput]: ${megabytes}MB streamed in ${seconds}s (~${throughputMbPerSec} MB/s)")
        (throughputMbPerSec > 5.0).shouldBeTrue()
    }

    @Test
    fun benchmarkSchedulerQueueEnqueue1000Items() {
        val queue = SchedulerQueue()
        val duration = measureTime {
            repeat(1000) { i ->
                queue.enqueue(
                    id = DownloadId("dl_$i"),
                    priority = if (i % 3 == 0) DownloadPriority.HIGH else DownloadPriority.NORMAL,
                    createdAtEpochMs = i.toLong()
                )
            }
        }

        println("BENCHMARK [Scheduler Queue]: Enqueued 1,000 items in ${duration.inWholeMicroseconds} µs")
        queue.size() shouldBe 1000
    }
}