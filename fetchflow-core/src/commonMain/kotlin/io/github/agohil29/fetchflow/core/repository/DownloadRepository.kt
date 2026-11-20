package io.github.agohil29.fetchflow.core.repository

import io.github.agohil29.fetchflow.core.model.DownloadError
import io.github.agohil29.fetchflow.core.model.DownloadId
import io.github.agohil29.fetchflow.core.model.DownloadRecord
import io.github.agohil29.fetchflow.core.model.DownloadRequest
import kotlinx.coroutines.flow.Flow
import okio.Path

interface DownloadRepository {
    suspend fun insertOrUpdate(
        id: DownloadId,
        request: DownloadRequest,
        initialState: String = "QUEUED",
        createdAtEpochMs: Long,
        updatedAtEpochMs: Long
    )

    suspend fun getById(id: DownloadId): DownloadRecord?

    suspend fun getByGroup(groupName: String): List<DownloadRecord>

    fun observeById(id: DownloadId): Flow<DownloadRecord?>

    fun observeAll(): Flow<List<DownloadRecord>>

    suspend fun updateState(id: DownloadId, state: String, timestampEpochMs: Long)

    suspend fun updateConnectionHeaders(
        id: DownloadId,
        totalBytes: Long?,
        etag: String?,
        lastModified: String?,
        timestampEpochMs: Long
    )

    suspend fun updateProgress(id: DownloadId, downloadedBytes: Long, timestampEpochMs: Long)

    suspend fun markCompleted(id: DownloadId, finalPath: Path, timestampEpochMs: Long)

    suspend fun markFailed(id: DownloadId, error: DownloadError, timestampEpochMs: Long)

    suspend fun reconcileStaleStates(timestampEpochMs: Long)

    suspend fun delete(id: DownloadId)

    suspend fun clear()
}