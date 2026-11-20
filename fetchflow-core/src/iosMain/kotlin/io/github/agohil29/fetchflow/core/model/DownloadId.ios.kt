package io.github.agohil29.fetchflow.core.model

import platform.posix.CLOCK_MONOTONIC_RAW
import platform.posix.clock_gettime_nsec_np

internal actual fun getMonotonicTimestampNs(): Long {
    return clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW.toUInt()).toLong()
}