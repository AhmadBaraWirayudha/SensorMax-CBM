package com.research.sensormax

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraPhotometricMathTest {
    @Test
    fun lumaStatisticsNormalizeAndCountClipping() {
        val stats = CameraPhotometricMath.fromLumaValues(
            intArrayOf(0, 16, 128, 239, 255),
            underexposedAt = 16,
            overexposedAt = 239
        )

        assertEquals(5, stats.sampleCount)
        assertEquals((638f / 5f / 255f).toDouble(), stats.meanLuma.toDouble(), 1e-6)
        assertEquals(0.4, stats.underexposedFraction.toDouble(), 1e-6)
        assertEquals(0.4, stats.overexposedFraction.toDouble(), 1e-6)
    }
}
