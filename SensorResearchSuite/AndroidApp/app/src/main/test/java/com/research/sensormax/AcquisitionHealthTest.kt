package com.research.sensormax

import org.junit.Assert.assertEquals
import org.junit.Test

class AcquisitionHealthTest {
    @Test
    fun classifiesHealthyAcquisition() {
        val snapshot = AcquisitionHealthSnapshot(
            sessionId = "S",
            running = true,
            elapsedMs = 1000L,
            activeSensorCount = 1,
            requestedIntervalMs = 20L,
            diskLoggingEnabled = false,
            diskWriteHealthy = true,
            vibrationRawEvents = 50L,
            vibrationAcceptedEvents = 50L,
            vibrationThrottledEvents = 0L,
            effectiveVibrationRateHz = 50.0,
            lastVibrationGapMs = 20.0,
            maxVibrationGapMs = 20.0,
            networkConnected = true,
            queuedRawPackets = 0,
            queuedAnalysisPackets = 0,
            droppedRawPackets = 0L,
            droppedAnalysisPackets = 0L,
            reconnectAttempt = 0
        )
        assertEquals("HEALTHY", snapshot.acquisitionIntegrity)
    }

    @Test
    fun distinguishesNetworkDegradationFromAcquisitionDegradation() {
        val network = AcquisitionHealthSnapshot(
            sessionId = "S",
            running = true,
            elapsedMs = 1_000L,
            activeSensorCount = 1,
            requestedIntervalMs = 20L,
            diskLoggingEnabled = false,
            diskWriteHealthy = true,
            vibrationRawEvents = 10L,
            vibrationAcceptedEvents = 10L,
            vibrationThrottledEvents = 0L,
            effectiveVibrationRateHz = 50.0,
            lastVibrationGapMs = 20.0,
            maxVibrationGapMs = 20.0,
            networkConnected = false,
            queuedRawPackets = 100,
            queuedAnalysisPackets = 1,
            droppedRawPackets = 2L,
            droppedAnalysisPackets = 0L,
            reconnectAttempt = 2
        )
        assertEquals("NETWORK_DEGRADED", network.acquisitionIntegrity)

        val acquisition = network.copy(
            droppedRawPackets = 0L,
            maxVibrationGapMs = 100.0
        )
        assertEquals("DEGRADED", acquisition.acquisitionIntegrity)
    }
    @Test
    fun flagsStorageFailureSeparatelyFromSensorDegradation() {
        val snapshot = AcquisitionHealthSnapshot(
            sessionId = "S",
            running = true,
            elapsedMs = 1000L,
            activeSensorCount = 1,
            requestedIntervalMs = 20L,
            diskLoggingEnabled = true,
            diskWriteHealthy = false,
            vibrationRawEvents = 50L,
            vibrationAcceptedEvents = 50L,
            vibrationThrottledEvents = 0L,
            effectiveVibrationRateHz = 50.0,
            lastVibrationGapMs = 20.0,
            maxVibrationGapMs = 20.0,
            networkConnected = true,
            queuedRawPackets = 0,
            queuedAnalysisPackets = 0,
            droppedRawPackets = 0L,
            droppedAnalysisPackets = 0L,
            reconnectAttempt = 0
        )
        assertEquals("STORAGE_DEGRADED", snapshot.acquisitionIntegrity)
    }

}
