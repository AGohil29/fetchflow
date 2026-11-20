package io.github.agohil29.fetchflow.core.repository

import io.github.agohil29.fetchflow.core.model.NetworkRequest
import io.github.agohil29.fetchflow.core.model.NetworkResponse

interface NetworkTransport {
    /**
     * Returns a streaming response as soon as headers are available, without buffering the body.
     * The caller must invoke [NetworkResponse.close] in a finally block, even on cancellation
     * or when failing before the body is read.
     */
    suspend fun execute(request: NetworkRequest): NetworkResponse

    /** Releases connection pools and underlying HTTP client instances. */
    fun close()
}