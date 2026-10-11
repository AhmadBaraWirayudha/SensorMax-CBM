package com.research.sensormax

import java.util.ArrayDeque
import kotlin.math.max

/**
 * SensorMax-native implementation of the high-value experiment buffer semantics.
 *
 * The buffer is deliberately non-destructive when an analysis input is marked
 * keep=true. Consumers receive immutable snapshots instead of owning the
 * underlying mutable acquisition store.
 */
class SignalBuffer<T>(private val capacity: Int) {
    init {
        require(capacity > 0) { "capacity must be > 0" }
    }

    private val values = ArrayDeque<T>(capacity)

    @Synchronized
    fun append(value: T) {
        values.addLast(value)
        while (values.size > capacity) values.removeFirst()
    }

    @Synchronized
    fun appendAll(items: Iterable<T>) {
        for (item in items) {
            values.addLast(item)
            while (values.size > capacity) values.removeFirst()
        }
    }

    @Synchronized
    fun snapshot(): List<T> = values.toList()

    @Synchronized
    fun size(): Int = values.size

    @Synchronized
    fun latestOrNull(): T? = values.lastOrNull()

    @Synchronized
    fun clear() {
        values.clear()
    }
}

enum class AnalysisInputPolicy {
    KEEP,
    CONSUME
}

/** Collection-detached view handed to an analysis consumer; element immutability is the caller's responsibility. */
data class AnalysisInput<T>(
    val values: List<T>,
    val policy: AnalysisInputPolicy
) {
    val keep: Boolean
        get() = policy == AnalysisInputPolicy.KEEP
}

/**
 * Models experiment's requireFill semantics without coupling it to SensorMax's
 * concrete FFT implementation.
 *
 * Opening and starting grant exactly one first analysis pass even when the
 * required buffer is empty. Stopping grants no exemption.
 */
class AnalysisGate(
    private val requireFillThreshold: Int = 0
) {
    private var firstRunEligible = true

    init {
        require(requireFillThreshold >= 0) { "requireFillThreshold must be >= 0" }
    }

    @Synchronized
    fun onOpened() {
        firstRunEligible = true
    }

    @Synchronized
    fun onStarted() {
        firstRunEligible = true
    }

    @Synchronized
    fun onStopped() {
        firstRunEligible = false
    }

    @Synchronized
    fun shouldRun(filledSize: Int): Boolean {
        require(filledSize >= 0) { "filledSize must be >= 0" }
        val allowed = if (firstRunEligible) {
            firstRunEligible = false
            true
        } else {
            filledSize >= requireFillThreshold
        }
        return allowed
    }
}

/**
 * Maps the device's monotonic SensorEvent timestamp domain to wall-clock time
 * without deriving elapsed time from wall-clock deltas.
 */
class ExperimentClock {
    private var monotonicAnchorNs: Long? = null
    private var wallClockAnchorMs: Long? = null

    @Synchronized
    fun markStart(monotonicNs: Long, wallClockMs: Long) {
        monotonicAnchorNs = monotonicNs
        wallClockAnchorMs = wallClockMs
    }

    @Synchronized
    fun markPause(monotonicNs: Long, wallClockMs: Long): ClockEvent {
        return ClockEvent(
            monotonicNs = monotonicNs,
            wallClockMs = wallClockMs,
            experimentElapsedMs = elapsedMs(monotonicNs)
        )
    }

    @Synchronized
    fun elapsedMs(monotonicNs: Long): Long {
        val anchor = monotonicAnchorNs ?: return 0L
        return max(0L, (monotonicNs - anchor) / 1_000_000L)
    }

    @Synchronized
    fun wallClockMsFor(monotonicNs: Long): Long? {
        val mono = monotonicAnchorNs ?: return null
        val wall = wallClockAnchorMs ?: return null
        return wall + ((monotonicNs - mono) / 1_000_000L)
    }
}

data class ClockEvent(
    val monotonicNs: Long,
    val wallClockMs: Long,
    val experimentElapsedMs: Long
)
