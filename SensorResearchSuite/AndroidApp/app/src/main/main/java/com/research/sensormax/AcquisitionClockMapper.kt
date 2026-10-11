package com.research.sensormax

/**
 * Maps a monotonic acquisition clock onto epoch wall time without trusting a
 * per-sample wall-clock read that can jump when the system clock changes.
 */
class AcquisitionClockMapper {
    private var anchorMonotonicNs: Long? = null
    private var anchorWallClockMs: Long? = null

    @Synchronized
    fun reset() {
        anchorMonotonicNs = null
        anchorWallClockMs = null
    }

    @Synchronized
    fun resolve(monotonicNs: Long, fallbackWallClockMs: Long): Long {
        val anchorNs = anchorMonotonicNs
        val anchorWallMs = anchorWallClockMs
        if (anchorNs == null || anchorWallMs == null) {
            anchorMonotonicNs = monotonicNs
            anchorWallClockMs = fallbackWallClockMs
            return fallbackWallClockMs
        }
        return anchorWallMs + ((monotonicNs - anchorNs) / 1_000_000L)
    }
}
