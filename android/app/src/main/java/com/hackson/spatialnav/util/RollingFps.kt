package com.hackson.spatialnav.util

import java.util.ArrayDeque

/**
 * Frame rate over a short trailing window.
 *
 * A lifetime average hides exactly what we care about: the phone throttling after a few
 * minutes of camera plus depth. Only timestamps inside [windowNanos] are kept, so the value
 * follows thermal degradation instead of averaging it away.
 *
 * Not thread-safe; call from a single frame-producing thread.
 */
class RollingFps(private val windowNanos: Long = DEFAULT_WINDOW_NANOS) {

    private val timestamps = ArrayDeque<Long>()

    var totalFrames: Long = 0L
        private set

    fun record(nowNanos: Long = System.nanoTime()) {
        totalFrames++
        timestamps.addLast(nowNanos)
        val cutoff = nowNanos - windowNanos
        while (timestamps.isNotEmpty() && timestamps.first() < cutoff) {
            timestamps.removeFirst()
        }
    }

    /** Frames per second over the window, or 0 until at least two frames are in it. */
    fun fps(): Double {
        if (timestamps.size < 2) return 0.0
        val span = timestamps.last() - timestamps.first()
        if (span <= 0L) return 0.0
        return (timestamps.size - 1) * 1_000_000_000.0 / span
    }

    private companion object {
        const val DEFAULT_WINDOW_NANOS = 3_000_000_000L
    }
}
