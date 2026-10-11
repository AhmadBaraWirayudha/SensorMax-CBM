package com.research.sensormax

object CameraAcquisitionQa {
    fun run(): List<String> {
        val checks = mutableListOf<String>()
        CameraAcquisitionProfile().let {
            check(it.width >= 32)
            check(it.height >= 32)
            check(it.sampleEveryPixels >= 1)
            checks += "CAMERA_PROFILE_PASS"
        }

        val stats = CameraPhotometricMath.fromLumaValues(
            intArrayOf(0, 16, 128, 239, 255),
            underexposedAt = 16,
            overexposedAt = 239
        )
        check(stats.sampleCount == 5)
        check(kotlin.math.abs(stats.meanLuma - (638f / 5f / 255f)) < 1e-6f)
        check(kotlin.math.abs(stats.underexposedFraction - 0.4f) < 1e-6f)
        check(kotlin.math.abs(stats.overexposedFraction - 0.4f) < 1e-6f)
        checks += "S25_CAMERA_PHOTOMETRIC_QA_PASS"
        return checks
    }
}
