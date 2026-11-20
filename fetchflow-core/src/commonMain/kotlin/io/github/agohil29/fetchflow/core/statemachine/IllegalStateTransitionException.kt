package io.github.agohil29.fetchflow.core.statemachine

import io.github.agohil29.fetchflow.core.model.DownloadId
import io.github.agohil29.fetchflow.core.model.DownloadState

class IllegalStateTransitionException(
    val id: DownloadId,
    val from: DownloadState,
    val event: DownloadStateEvent
) : IllegalStateException(
    "Illegal transition for download '${id.raw}': Cannot execute event '${event::class.simpleName}' from state '${from::class.simpleName}'."
)