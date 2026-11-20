package io.github.agohil29.fetchflow.core.time

import io.github.agohil29.fetchflow.core.engine.getSystemClockEpochMs

/**
 * Wall-clock time source shared by the engine and runtime modules.
 *
 * Returns milliseconds since the Unix epoch. Do not use a monotonic `TimeSource` here:
 * monotonic marks only measure elapsed durations and cannot produce epoch timestamps.
 */
object EpochClock {
    fun nowEpochMs(): Long = getSystemClockEpochMs()
}

