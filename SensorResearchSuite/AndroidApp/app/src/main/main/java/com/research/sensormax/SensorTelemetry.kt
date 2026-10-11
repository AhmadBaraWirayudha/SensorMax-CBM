package com.research.sensormax

import android.hardware.Sensor
import java.util.Locale
import kotlin.math.sqrt

data class SensorTelemetrySample(
    val type: Int,
    val name: String,
    val vendor: String,
    val timestampMs: Long,
    val values: FloatArray,
    val sourceKind: AcquisitionSourceKind = AcquisitionSourceKind.ANDROID_SENSOR,
    val sourceId: String = "android-sensors",
    val channelId: String = "",
    val unit: String = ""
) {
    fun copySnapshot(): SensorTelemetrySample = copy(values = values.copyOf())
}

object SensorTelemetryFormatter {
    private val vectorTypes = setOf(
        Sensor.TYPE_ACCELEROMETER,
        Sensor.TYPE_LINEAR_ACCELERATION,
        Sensor.TYPE_GRAVITY,
        Sensor.TYPE_GYROSCOPE,
        Sensor.TYPE_GYROSCOPE_UNCALIBRATED,
        Sensor.TYPE_MAGNETIC_FIELD,
        Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED,
        Sensor.TYPE_ROTATION_VECTOR,
        Sensor.TYPE_GAME_ROTATION_VECTOR,
        Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR
    )
    fun label(type: Int): String = when (type) {
        Sensor.TYPE_ACCELEROMETER -> "Accelerometer"
        Sensor.TYPE_LINEAR_ACCELERATION -> "Linear acceleration"
        Sensor.TYPE_GRAVITY -> "Gravity"
        Sensor.TYPE_GYROSCOPE -> "Gyroscope"
        Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> "Gyroscope uncalibrated"
        Sensor.TYPE_MAGNETIC_FIELD -> "Magnetic field"
        Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> "Magnetic field uncalibrated"
        Sensor.TYPE_PRESSURE -> "Pressure"
        Sensor.TYPE_LIGHT -> "Light"
        Sensor.TYPE_PROXIMITY -> "Proximity"
        Sensor.TYPE_AMBIENT_TEMPERATURE -> "Ambient temperature"
        Sensor.TYPE_RELATIVE_HUMIDITY -> "Relative humidity"
        Sensor.TYPE_ROTATION_VECTOR -> "Rotation vector"
        Sensor.TYPE_GAME_ROTATION_VECTOR -> "Game rotation vector"
        Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> "Geomagnetic rotation vector"
        Sensor.TYPE_STEP_COUNTER -> "Step counter"
        Sensor.TYPE_STEP_DETECTOR -> "Step detector"
        else -> "Sensor type $type"
    }

    fun unit(type: Int): String = when (type) {
        Sensor.TYPE_MAGNETIC_FIELD,
        Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> "µT"
        Sensor.TYPE_LIGHT -> "lx"
        Sensor.TYPE_PRESSURE -> "hPa"
        Sensor.TYPE_PROXIMITY -> "cm"
        Sensor.TYPE_AMBIENT_TEMPERATURE -> "°C"
        Sensor.TYPE_RELATIVE_HUMIDITY -> "%"
        Sensor.TYPE_STEP_COUNTER,
        Sensor.TYPE_STEP_DETECTOR -> "steps"
        Sensor.TYPE_GYROSCOPE,
        Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> "rad/s"
        Sensor.TYPE_ACCELEROMETER,
        Sensor.TYPE_LINEAR_ACCELERATION,
        Sensor.TYPE_GRAVITY -> "m/s²"
        else -> ""
    }

    fun summary(type: Int, values: FloatArray): String {
        if (values.isEmpty()) return "--"
        val unit = unit(type)
        return when {
            values.size >= 3 && type in vectorTypes -> {
                val xyz = "X ${formatFinite(values[0], 3)} · Y ${formatFinite(values[1], 3)} · Z ${formatFinite(values[2], 3)}"
                val magnitude = if (values.take(3).all { it.isFinite() }) {
                    val value = sqrt(
                        (values[0] * values[0] + values[1] * values[1] + values[2] * values[2]).toDouble()
                    ).toFloat()
                    formatFinite(value, 3)
                } else {
                    "—"
                }
                if (unit.isBlank()) "$xyz · |v| $magnitude" else "$xyz $unit · |v| $magnitude $unit"
            }
            else -> {
                val valuesText = values.take(6).joinToString(" · ") { formatFinite(it, 4) }
                if (unit.isBlank()) valuesText else "$valuesText $unit"
            }
        }
    }

    private fun formatFinite(value: Float, decimals: Int): String =
        if (value.isFinite()) String.format(Locale.US, "%.${decimals}f", value) else "—"
}
