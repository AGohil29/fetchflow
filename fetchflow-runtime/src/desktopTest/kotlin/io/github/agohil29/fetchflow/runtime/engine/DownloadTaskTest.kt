package io.github.agohil29.fetchflow.runtime.engine

import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import io.github.agohil29.fetchflow.core.engine.DownloadTask
import io.github.agohil29.fetchflow.core.model.ChecksumValidation
import io.github.agohil29.fetchflow.core.model.ConflictStrategy
import io.github.agohil29.fetchflow.core.model.DownloadError
import io.github.agohil29.fetchflow.core.model.DownloadId
import io.github.agohil29.fetchflow.core.model.DownloadPriority
import io.github.agohil29.fetchflow.core.model.DownloadRequest
import io.github.agohil29.fetchflow.core.model.DownloadState
import io.github.agohil29.fetchflow.core.model.NetworkRequest
import io.github.agohil29.fetchflow.core.model.NetworkResponse
import io.github.agohil29.fetchflow.core.repository.DownloadFileSystem
import io.github.agohil29.fetchflow.core.repository.NetworkTransport
import io.github.agohil29.fetchflow.core.statemachine.DownloadStateMachine
import io.github.agohil29.fetchflow.filesystem.OkioDownloadFileSystem
import io.github.agohil29.fetchflow.storage.db.DownloadDatabase
import io.github.agohil29.fetchflow.storage.db.DownloadRecordEntity
import io.github.agohil29.fetchflow.storage.repository.SqlDelightDownloadRepository
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.IOException
import okio.Path
import okio.Sink
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.BeforeTest
import kotlin.test.Test

class DownloadTaskTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeFileSystem: FakeFileSystem
    private lateinit var downloadFileSystem: OkioDownloadFileSystem
    private lateinit var repository: SqlDelightDownloadRepository

    private val baseStagingDir = "/staging".toPath()
    private val baseDestinationDir = "/downloads".toPath()

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
        repository = SqlDelightDownloadRepository(database, testDispatcher)

        fakeFileSystem.createDirectories(baseStagingDir)
        fakeFileSystem.createDirectories(baseDestinationDir)
    }

    private fun createMockTransport(
        content: ByteArray,
        statusCode: Int = 200,
        contentLength: Long? = content.size.toLong()
    ): NetworkTransport {
        return object : NetworkTransport {
            override suspend fun execute(request: NetworkRequest): NetworkResponse {
                val channel = ByteChannel(autoFlush = true)
                channel.writeFully(content)
                channel.close()

                return NetworkResponse(
                    statusCode = statusCode,
                    headers = mapOf("Content-Length" to (contentLength?.toString() ?: "")),
                    bodyChannel = channel,
                    contentLength = contentLength,
                    isPartialContent = false,
                    etag = "\"test-etag\"",
                    lastModified = null
                )
            }

            override fun close() {}
        }
    }

    @Test
    fun successfulDownloadExecutesTwoPhaseCommitAndMarksCompleted() = runTest(testDispatcher) {
        val id = DownloadId("dl_engine_01")
        val sampleBytes = "Hello KMP File Downloader Engine".encodeToByteArray()
        val request = DownloadRequest(
            url = "https://mock.com/hello.txt",
            destinationDirectory = baseDestinationDir,
            fileName = "hello.txt",
            priority = DownloadPriority.HIGH,
            conflictStrategy = ConflictStrategy.OVERWRITE
        )

        repository.insertOrUpdate(id, request, "QUEUED", 1000L, 1000L)
        val stateMachine = DownloadStateMachine(id)
        val transport = createMockTransport(sampleBytes)

        val task = DownloadTask(
            id = id,
            request = request,
            stateMachine = stateMachine,
            repository = repository,
            network = transport,
            fileSystem = downloadFileSystem,
            stagingDirectory = baseStagingDir,
            ioDispatcher = testDispatcher
        )

        task.state.test {
            awaitItem().shouldBeInstanceOf<DownloadState.Idle>()

            val job = launch { task.execute() }
            testDispatcher.scheduler.advanceUntilIdle()

            val completed = expectMostRecentItem()
            completed.shouldBeInstanceOf<DownloadState.Completed>()
            completed.totalBytes shouldBe sampleBytes.size.toLong()
            completed.absolutePath shouldBe (baseDestinationDir / "hello.txt").toString()

            // Confirm destination file exists & temporary part file is cleaned up
            fakeFileSystem.exists(baseDestinationDir / "hello.txt") shouldBe true
            fakeFileSystem.exists(baseStagingDir / ".downloader_staging" / "${id.raw}.part") shouldBe false

            // Confirm database records state as COMPLETED
            val record = repository.getById(id)
            record?.state shouldBe "COMPLETED"

            job.join()
        }
    }

    @Test
    fun checksumMismatchDeletesOrFailsGracefully() = runTest(testDispatcher) {
        val id = DownloadId("dl_checksum_fail")
        val sampleBytes = "Payload that will fail hash check".encodeToByteArray()
        val request = DownloadRequest(
            url = "https://mock.com/file.bin",
            destinationDirectory = baseDestinationDir,
            fileName = "file.bin",
            priority = DownloadPriority.NORMAL,
            expectedChecksum = ChecksumValidation(
                algorithm = ChecksumValidation.Algorithm.SHA256,
                expectedHash = "0000000000000000000000000000000000000000000000000000000000000000"
            )
        )

        val stateMachine = DownloadStateMachine(id)
        val transport = createMockTransport(sampleBytes)

        val task = DownloadTask(
            id = id,
            request = request,
            stateMachine = stateMachine,
            repository = repository,
            network = transport,
            fileSystem = downloadFileSystem,
            stagingDirectory = baseStagingDir,
            ioDispatcher = testDispatcher
        )

        task.state.test {
            awaitItem().shouldBeInstanceOf<DownloadState.Idle>()

            launch { task.execute() }
            testDispatcher.scheduler.advanceUntilIdle()

            val failed = expectMostRecentItem()
            failed.shouldBeInstanceOf<DownloadState.Failed>()
            failed.error.shouldBeInstanceOf<DownloadError.NonRetryable.ChecksumMismatch>()

            fakeFileSystem.exists(baseDestinationDir / "file.bin") shouldBe false
        }
    }

    @Test
    fun cancellationPreservesPartialFileAndEntersPausedState() = runTest(testDispatcher) {
        val id = DownloadId("dl_cancel_test")
        val endlessChannel = ByteChannel(autoFlush = true)

        val mockNetwork = object : NetworkTransport {
            override suspend fun execute(request: NetworkRequest): NetworkResponse = NetworkResponse(
                statusCode = 200,
                headers = emptyMap(),
                bodyChannel = endlessChannel,
                contentLength = 1_000_000L,
                isPartialContent = false,
                etag = null,
                lastModified = null
            )
            override fun close() {}
        }

        val request = DownloadRequest(
            url = "https://mock.com/endless.bin",
            destinationDirectory = baseDestinationDir,
            fileName = "endless.bin",
            priority = DownloadPriority.LOW
        )
        repository.insertOrUpdate(id, request, "DOWNLOADING", 1000L, 1000L)

        val stateMachine = DownloadStateMachine(id)

        val task = DownloadTask(
            id = id,
            request = request,
            stateMachine = stateMachine,
            repository = repository,
            network = mockNetwork,
            fileSystem = downloadFileSystem,
            stagingDirectory = baseStagingDir,
            ioDispatcher = testDispatcher
        )

        val executionJob = launch { task.execute() }

        // Write initial data into channel
        endlessChannel.writeFully(ByteArray(16384) { 1 })
        testDispatcher.scheduler.advanceUntilIdle()

        // Cancel execution mid-stream
        executionJob.cancel()
        testDispatcher.scheduler.advanceUntilIdle()

        task.state.value.shouldBeInstanceOf<DownloadState.Paused>()
        repository.getById(id)?.state shouldBe "PAUSED"
        endlessChannel.isClosedForWrite shouldBe true
    }

    @Test
    fun fileOpenFailureReleasesResponseBeforeBodyIsRead() = runTest(testDispatcher) {
        val id = DownloadId("dl_open_failure")
        val channel = ByteChannel(autoFlush = true)
        var released = false
        val network = object : NetworkTransport {
            override suspend fun execute(request: NetworkRequest) = NetworkResponse(
                statusCode = 200,
                headers = emptyMap(),
                bodyChannel = channel,
                contentLength = 100L,
                isPartialContent = false,
                etag = null,
                lastModified = null,
                releaseResponse = { released = true }
            )
            override fun close() {}
        }
        val failingFileSystem = object : DownloadFileSystem by downloadFileSystem {
            override fun sink(path: Path, append: Boolean): Sink = throw IOException("Disk unavailable")
        }
        val request = DownloadRequest(
            url = "https://mock.com/file.bin",
            destinationDirectory = baseDestinationDir,
            fileName = "file.bin"
        )
        repository.insertOrUpdate(id, request, "QUEUED", 1000L, 1000L)
        val task = DownloadTask(
            id = id,
            request = request,
            stateMachine = DownloadStateMachine(id),
            repository = repository,
            network = network,
            fileSystem = failingFileSystem,
            stagingDirectory = baseStagingDir,
            ioDispatcher = testDispatcher
        )
        task.execute()
        task.state.value.shouldBeInstanceOf<DownloadState.Failed>()
        repository.getById(id)?.state shouldBe "FAILED"
        released shouldBe true
        channel.isClosedForWrite shouldBe true
        fakeFileSystem.exists(baseDestinationDir / "file.bin") shouldBe false
    }
}