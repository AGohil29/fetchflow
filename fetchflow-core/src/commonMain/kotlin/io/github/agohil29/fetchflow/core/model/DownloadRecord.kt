package io.github.agohil29.fetchflow.core.model

import io.github.agohil29.fetchflow.core.model.ConflictStrategy
import io.github.agohil29.fetchflow.core.model.DownloadId
import io.github.agohil29.fetchflow.core.model.DownloadPriority
import okio.Path

data class DownloadRecord(
    val id: DownloadId,
    val url: String,
    val destinationDirectory: Path,
    val fileName: String?,
    val state: String,
    val priority: DownloadPriority,
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val etag: String?,
    val lastModified: String?,
    val conflictStrategy: ConflictStrategy,
    val errorType: String?,
    val errorMessage: String?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val group: String? = null,
    val finalPath: Path? = null,
    val headers: Map<String, String> = emptyMap(),
    val expectedChecksum: ChecksumValidation? = null
)