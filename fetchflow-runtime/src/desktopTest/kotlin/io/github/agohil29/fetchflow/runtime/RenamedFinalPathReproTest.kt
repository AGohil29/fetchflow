package io.github.agohil29.fetchflow.runtime

import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.agohil29.fetchflow.core.model.ConflictStrategy
import io.github.agohil29.fetchflow.core.model.DownloadId
import io.github.agohil29.fetchflow.core.model.DownloadRequest
import io.github.agohil29.fetchflow.core.model.DownloadState
import io.github.agohil29.fetchflow.core.model.DownloaderConfig
import io.github.agohil29.fetchflow.core.model.NetworkRequest
import io.github.agohil29.fetchflow.core.model.NetworkResponse
import io.github.agohil29.fetchflow.core.repository.NetworkTransport
import io.github.agohil29.fetchflow.filesystem.OkioDownloadFileSystem
import io.github.agohil29.fetchflow.storage.db.DownloadDatabase
import io.github.agohil29.fetchflow.storage.db.DownloadRecordEntity
import io.github.agohil29.fetchflow.storage.repository.SqlDelightDownloadRepository
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Reproduces: with ConflictStrategy.RENAME_NEW_INCREMENTAL the download is committed to
 * `name (1).ext`, but the record only stores the requested `fileName`. Consequently:
 *  - delete(id) removes destinationDirectory/<name> — the user's pre-existing file — and
 *    leaves the actual downloaded `name (1).ext` orphaned.
 *  - Completed.absolutePath rebuilt from the record (observeAll / after restart) is wrong.
 */
class RenamedFinalPathReproTest {

    private val stagingDir = "/staging".toPath()
    private val downloadDir = "/downloads".toPath()
    private val userFile = downloadDir / "report.pdf"
    private val renamedFile = downloadDir / "report (1).pdf"

    private val userBytes = "USER-OWNED CONTENT".encodeToByteArray()
    private val payload = "DOWNLOADED PAYLOAD".encodeToByteArray()

    private lateinit var fakeFs: FakeFileSystem
    private lateinit var repository: SqlDelightDownloadRepository
    private val scopes = mutableListOf<CoroutineScope>()

    private val network = object : NetworkTransport {
        override suspend fun execute(request: NetworkRequest) = NetworkResponse(
            statusCode = 200,
            headers = mapOf("Content-Length" to payload.size.toString()),
            bodyChannel = ByteReadChannel(payload),
            contentLength = payload.size.toLong(),
            isPartialContent = false,
            etag = null,
            lastModified = null
        )
        override fun close() {}
    }

    @BeforeTest
    fun setup() {
        fakeFs = FakeFileSystem().apply {
            createDirectories(stagingDir)
            createDirectories(downloadDir)
            write(userFile) { write(userBytes) } // user's own pre-existing file
        }
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DownloadDatabase.Schema.create(driver)
        repository = SqlDelightDownloadRepository(
            database = DownloadDatabase(
                driver = driver,
                DownloadRecordEntityAdapter = DownloadRecordEntity.Adapter(
                    priorityAdapter = EnumColumnAdapter(),
                    conflict_strategyAdapter = EnumColumnAdapter()
                )
            )
        )
    }

    @AfterTest
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    private fun newDownloader(): DefaultFileDownloader {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        return DefaultFileDownloader(
            config = DownloaderConfig(maxConcurrentDownloads = 1, stagingDirectory = stagingDir),
            repository = repository,
            network = network,
            fileSystem = OkioDownloadFileSystem(fakeFs),
            scope = scope
        )
    }

    private suspend fun awaitCompleted(id: DownloadId) = withTimeout(5_000.milliseconds) {
        while (repository.getById(id)?.state != "COMPLETED") delay(10.milliseconds)
    }

    private suspend fun enqueueAndComplete(downloader: DefaultFileDownloader): DownloadId {
        val id = downloader.enqueue(
            DownloadRequest(
                url = "https://mock.com/report.pdf",
                destinationDirectory = downloadDir,
                fileName = "report.pdf",
                conflictStrategy = ConflictStrategy.RENAME_NEW_INCREMENTAL
            )
        )
        awaitCompleted(id)
        // Sanity: the download really was committed to the renamed path.
        assertTrue(fakeFs.exists(renamedFile), "download should be saved as $renamedFile")
        assertEquals(payload.decodeToString(), fakeFs.read(renamedFile) { readUtf8() })
        return id
    }

    @Test
    fun deleteMustNotRemoveUsersOwnFile() = runBlocking {
        val downloader = newDownloader()
        val id = enqueueAndComplete(downloader)

        downloader.delete(id, deleteFiles = true)

        println("after delete: userFile exists=${fakeFs.exists(userFile)}, renamedFile exists=${fakeFs.exists(renamedFile)}")
        assertTrue(fakeFs.exists(userFile), "BUG: delete() removed the user's own file $userFile")
        assertTrue(!fakeFs.exists(renamedFile), "BUG: delete() left the actual download $renamedFile orphaned")
    }

    @Test
    fun completedPathInObserveAllMatchesActualFile() = runBlocking {
        val downloader = newDownloader()
        val id = enqueueAndComplete(downloader)

        val state = downloader.observeAll().first().single { it.id == id }
        assertIs<DownloadState.Completed>(state)
        println("observeAll Completed.absolutePath=${state.absolutePath}")
        assertEquals(renamedFile.toString(), state.absolutePath)
    }

    @Test
    fun completedPathAfterRestartMatchesActualFile() = runBlocking {
        val id = enqueueAndComplete(newDownloader())

        // Simulate process restart: fresh downloader over the same persisted DB.
        val restarted = newDownloader()
        val state = withTimeout(5_000.milliseconds) {
            restarted.observe(id).first { it is DownloadState.Completed }
        }
        assertIs<DownloadState.Completed>(state)
        println("after restart Completed.absolutePath=${state.absolutePath}")
        assertEquals(renamedFile.toString(), state.absolutePath)
    }
}

