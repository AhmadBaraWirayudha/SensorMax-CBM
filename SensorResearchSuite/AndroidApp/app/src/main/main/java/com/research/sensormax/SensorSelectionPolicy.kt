package com.research.sensormax

import android.hardware.Sensor

/**
 * Deterministic default choice for vibration/CBM acquisition.
 * The full Android sensor inventory remains available for manual/experimental use.
 *
 * Ranking order among candidates: usable sampling interval (fastest first),
 * best numeric resolution (smallest increment first), then range as headroom.
 * Range is not ranked first because an unnecessarily large range can sacrifice
 * low-amplitude resolution important to condition monitoring.
 */
object SensorSelectionPolicy {
    data class Candidate<T>(
        val value: T,
        val type: Int,
        val maxRange: Float,
        val resolution: Float,
        val minDelayUs: Int,
        val name: String,
        val vendor: String
    )

    fun rank(sensor: Sensor): Int = when (sensor.type) {
        Sensor.TYPE_ACCELEROMETER -> 0
        Sensor.TYPE_LINEAR_ACCELERATION -> 1
        else -> Int.MAX_VALUE
    }

    fun recommended(sensors: List<Sensor>): Set<Sensor> {
        val candidates = sensors.map { sensor ->
            Candidate(
                value = sensor,
                type = sensor.type,
                maxRange = sensor.maximumRange,
                resolution = sensor.resolution,
                minDelayUs = sensor.minDelay,
                name = sensor.name,
                vendor = sensor.vendor
            )
        }
        return recommendedCandidate(candidates)?.let { setOf(it.value) } ?: emptySet()
    }

    /** Exposed independently from Android Sensor objects so ranking has deterministic JVM tests. */
    fun <T> recommendedCandidate(candidates: List<Candidate<T>>): Candidate<T>? {
        val rawAccelerometers = candidates.filter { it.type == Sensor.TYPE_ACCELEROMETER }
        val compatible = if (rawAccelerometers.isNotEmpty()) {
            rawAccelerometers
        } else {
            candidates.filter { it.type == Sensor.TYPE_LINEAR_ACCELERATION }
        }
        return compatible.maxWithOrNull(
            compareBy<Candidate<T>> { it.minDelayUs > 0 }
                .thenBy { if (it.minDelayUs > 0) -it.minDelayUs.toLong() else Long.MIN_VALUE }
                .thenBy { if (it.resolution > 0f) 1.0 / it.resolution else 0.0 }
                .thenBy { it.maxRange }
                .thenBy { it.name }
                .thenBy { it.vendor }
        )
    }

    fun sort(sensors: List<Sensor>): List<Sensor> =
        sensors.sortedWith(
            compareBy<Sensor> { rank(it) }
                .thenBy { if (it.minDelay > 0) it.minDelay else Int.MAX_VALUE }
                .thenBy { if (it.resolution > 0f) it.resolution else Float.POSITIVE_INFINITY }
                .thenByDescending { it.maximumRange }
                .thenBy { it.name }
                .thenBy { it.vendor }
        )
}
