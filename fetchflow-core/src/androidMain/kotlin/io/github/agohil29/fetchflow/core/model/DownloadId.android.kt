package io.github.agohil29.fetchflow.core.model

import android.os.SystemClock

internal actual fun getMonotonicTimestampNs(): Long = SystemClock.elapsedRealtimeNanos()