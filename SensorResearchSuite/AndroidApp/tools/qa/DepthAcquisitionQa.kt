package com.research.sensormax

object DepthAcquisitionQa {
    fun run(): List<String> {
        val checks = mutableListOf<String>()
        DepthAcquisitionProfile().let {
            check(it.width >= 2)
            check(it.height >= 2)
            check(it.sampleEveryPixels >= 1)
            checks += "DEPTH_PROFILE_PASS"
        }

        val validHighConfidence = (1000 and 0x1FFF)
        val validLowerConfidence = (3000 and 0x1FFF) or ((2 and 0x7) shl 13)
        val stats = Depth16Math.fromSamples(
            intArrayOf(validHighConfidence, validLowerConfidence, 0, (2000 and 0x1FFF) or (1 shl 13))
        )
        check(stats.sampleCount == 4)
        check(stats.validCount == 2)
        check(kotlin.math.abs(stats.meanDepthMm - 2000f) < 1e-6f)
        check(stats.minDepthMm == 1000)
        check(stats.maxDepthMm == 3000)
        check(kotlin.math.abs(stats.validFraction - 0.5f) < 1e-6f)
        checks += "S25_DEPTH16_QA_PASS"
        return checks
    }
}
