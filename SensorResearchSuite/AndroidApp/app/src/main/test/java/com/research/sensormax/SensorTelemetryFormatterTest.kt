package com.research.sensormax

import android.hardware.Sensor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorTelemetryFormatterTest {
    @Test
    fun scalarSummaryDoesNotInventZeroChannels() {
        val summary = SensorTelemetryFormatter.summary(
            Sensor.TYPE_PRESSURE,
            floatArrayOf(1013.25f)
        )

        assertTrue(summary.contains("1013.2500"))
        assertFalse(summary.contains("0.0000"))
    }

    @Test
    fun nonFiniteVectorChannelIsUnavailableAndMagnitudeIsNotCalculated() {
        val summary = SensorTelemetryFormatter.summary(
            Sensor.TYPE_ACCELEROMETER,
            floatArrayOf(1.0f, Float.NaN, 3.0f)
        )

        assertTrue(summary.contains("X 1.000"))
        assertTrue(summary.contains("Y —"))
        assertTrue(summary.contains("Z 3.000"))
        assertTrue(summary.contains("|v| —"))
    }

    @Test
    fun numericZeroRemainsAValidReading() {
        val summary = SensorTelemetryFormatter.summary(
            Sensor.TYPE_PRESSURE,
            floatArrayOf(0.0f)
        )
        assertTrue(summary.contains("0.0000"))
    }
}
