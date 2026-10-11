package com.research.sensormax

import org.junit.Assert.assertEquals
import org.junit.Test

class Depth16MathTest {
    @Test
    fun decodesDepthAndConfidenceBits() {
        val sample = (2500 and 0x1FFF) or ((4 and 0x7) shl 13)
        val decoded = Depth16Math.decodeSample(sample)

        assertEquals(2500, decoded.depthMm)
        assertEquals(3f / 7f, decoded.confidence, 1e-6f)
        assertEquals(4, decoded.confidenceCode)
    }

    @Test
    fun aggregatesOnlyUsableDepthSamples() {
        val validHighConfidence = (1000 and 0x1FFF) or (0 shl 13)
        val validLowerConfidence = (3000 and 0x1FFF) or ((2 and 0x7) shl 13)
        val zeroDepth = 0
        val zeroConfidence = (2000 and 0x1FFF) or ((1 and 0x7) shl 13)

        val stats = Depth16Math.fromSamples(
            intArrayOf(validHighConfidence, validLowerConfidence, zeroDepth, zeroConfidence)
        )

        assertEquals(4, stats.sampleCount)
        assertEquals(2, stats.validCount)
        assertEquals(2000f, stats.meanDepthMm, 1e-6f)
        assertEquals(1000, stats.minDepthMm)
        assertEquals(3000, stats.maxDepthMm)
        assertEquals(0.5f, stats.validFraction, 1e-6f)
        assertEquals((1f + 1f / 7f) / 2f, stats.meanConfidence, 1e-6f)
    }
}
