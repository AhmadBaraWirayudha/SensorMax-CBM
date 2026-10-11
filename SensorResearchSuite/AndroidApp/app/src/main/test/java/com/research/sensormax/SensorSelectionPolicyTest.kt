package com.research.sensormax

import android.hardware.Sensor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SensorSelectionPolicyTest {
    private fun candidate(
        id: String,
        type: Int = Sensor.TYPE_ACCELEROMETER,
        delayUs: Int,
        resolution: Float,
        range: Float
    ) = SensorSelectionPolicy.Candidate(
        value = id, type = type, maxRange = range, resolution = resolution,
        minDelayUs = delayUs, name = id, vendor = "test"
    )

    @Test fun prefers_fastest_supported_sampling_interval_first() {
        val slowerButHigherRange = candidate("slow", delayUs = 5000, resolution = 0.001f, range = 200f)
        val faster = candidate("fast", delayUs = 1000, resolution = 0.01f, range = 40f)
        assertEquals("fast", SensorSelectionPolicy.recommendedCandidate(listOf(slowerButHigherRange, faster))?.value)
    }

    @Test fun lower_numeric_resolution_wins_when_sampling_is_equal() {
        val coarse = candidate("coarse", delayUs = 1000, resolution = 0.01f, range = 40f)
        val fine = candidate("fine", delayUs = 1000, resolution = 0.001f, range = 40f)
        assertEquals("fine", SensorSelectionPolicy.recommendedCandidate(listOf(coarse, fine))?.value)
    }

    @Test fun raw_accelerometer_is_preferred_over_linear_acceleration_fallback() {
        val raw = candidate("raw", type = Sensor.TYPE_ACCELEROMETER, delayUs = 1000, resolution = 0.01f, range = 40f)
        val linear = candidate("linear", type = Sensor.TYPE_LINEAR_ACCELERATION, delayUs = 100, resolution = 0.0001f, range = 100f)
        assertEquals("raw", SensorSelectionPolicy.recommendedCandidate(listOf(linear, raw))?.value)
    }

    @Test fun no_compatible_accelerometer_returns_no_default() {
        val gyroscope = candidate("gyro", type = Sensor.TYPE_GYROSCOPE, delayUs = 1000, resolution = 0.01f, range = 20f)
        assertNull(SensorSelectionPolicy.recommendedCandidate(listOf(gyroscope)))
    }
}
