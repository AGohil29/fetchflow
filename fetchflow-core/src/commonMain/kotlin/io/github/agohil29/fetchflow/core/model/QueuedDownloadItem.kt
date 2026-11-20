package io.github.agohil29.fetchflow.core.model

data class QueuedDownloadItem(
    val id: DownloadId,
    val priority: DownloadPriority,
    val createdAtEpochMs: Long
)
