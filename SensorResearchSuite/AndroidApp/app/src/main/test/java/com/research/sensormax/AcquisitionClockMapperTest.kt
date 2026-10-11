package com.research.sensormax

import org.junit.Assert.assertEquals
import org.junit.Test

class AcquisitionClockMapperTest {
    @Test
    fun derivesWallClockFromMonotonicAnchor() {
        val mapper = AcquisitionClockMapper()
        assertEquals(1_000L, mapper.resolve(10_000_000L, 1_000L))
        assertEquals(1_025L, mapper.resolve(35_000_000L, 9_999L))
    }

    @Test
    fun resetCreatesNewAnchor() {
        val mapper = AcquisitionClockMapper()
        mapper.resolve(10_000_000L, 1_000L)
        mapper.reset()
        assertEquals(5_000L, mapper.resolve(20_000_000L, 5_000L))
    }
}
