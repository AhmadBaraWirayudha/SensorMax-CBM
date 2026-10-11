package com.research.sensormax

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhyphoxBufferModelTest {

    @Test
    fun boundedBufferKeepsNewestValues() {
        val buffer = SignalBuffer<Int>(3)
        buffer.appendAll(listOf(1, 2, 3, 4))
        assertEquals(listOf(2, 3, 4), buffer.snapshot())
    }

    @Test
    fun firstRunAndRestartAreExemptButStopIsNot() {
        val gate = AnalysisGate(5)
        assertTrue(gate.shouldRun(0))
        assertFalse(gate.shouldRun(0))
        assertFalse(gate.shouldRun(4))
        assertTrue(gate.shouldRun(5))

        gate.onStarted()
        assertTrue(gate.shouldRun(0))

        gate.onStopped()
        assertFalse(gate.shouldRun(0))
    }

    @Test
    fun experimentClockMapsMonotonicSensorTimeToWallClock() {
        val clock = ExperimentClock()
        clock.markStart(1_000_000_000L, 1_700_000_000_000L)
        assertEquals(250L, clock.elapsedMs(1_250_000_000L))
        assertEquals(1_700_000_000_250L, clock.wallClockMsFor(1_250_000_000L))
    }
}
