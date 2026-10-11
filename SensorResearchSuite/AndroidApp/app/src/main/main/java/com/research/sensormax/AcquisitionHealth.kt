package com.research.sensormax

/**
 * Immutable operational-health snapshot for one acquisition deployment.
 *
 * This is intentionally separate from engineering condition results: a clean
 * FFT result can still come from a degraded acquisition path, and operators
 * need both facts.
 */
data class AcquisitionHealthSnapshot(
    val sessionId: String,
    val running: Boolean,
    val elapsedMs: Long,
    val activeSensorCount: Int,
    val requestedIntervalMs: Long,
    val diskLoggingEnabled: Boolean,
    val diskWriteHealthy: Boolean,
    val vibrationRawEvents: Long,
    val vibrationAcceptedEvents: Long,
    val vibrationThrottledEvents: Long,
    val effectiveVibrationRateHz: Double,
    val lastVibrationGapMs: Double,
    val maxVibrationGapMs: Double,
    val networkConnected: Boolean,
    val queuedRawPackets: Int,
    val queuedAnalysisPackets: Int,
    val droppedRawPackets: Long,
    val droppedAnalysisPackets: Long,
    val reconnectAttempt: Int,
    val activeSourceCount: Int = 0,
    val sourceStates: Map<String, AcquisitionSourceState> = emptyMap()
) {
    val acquisitionIntegrity: String
        get() = when {
            !running -> "IDLE"
            activeSensorCount == 0 && activeSourceCount == 0 -> "NO_SENSOR"
            sourceStates.values.any { it == AcquisitionSourceState.ERROR } -> "DEGRADED"
            vibrationAcceptedEvents == 0L -> "WAITING_FOR_DATA"
            diskLoggingEnabled && !diskWriteHealthy -> "STORAGE_DEGRADED"
            maxVibrationGapMs > requestedIntervalMs * 3.0 -> "DEGRADED"
            droppedRawPackets > 0L || droppedAnalysisPackets > 0L -> "NETWORK_DEGRADED"
            else -> "HEALTHY"
        }
}

/** Lightweight transport-health view exported by StreamClient. */
data class NetworkHealthSnapshot(
    val streamingAllowed: Boolean,
    val connected: Boolean,
    val queuedRawPackets: Int,
    val queuedAnalysisPackets: Int,
    val droppedRawPackets: Long,
    val droppedAnalysisPackets: Long,
    val reconnectAttempt: Int
)
