package com.research.sensormax

import kotlin.math.PI
import kotlin.math.exp

class LiveWaveformFilter(
    private val cutoffHz: Float = 10f
) {
    private val stage = FloatArray(3)

    private var initialized = false
    private var lastTimestampMs = 0L

    fun reset() {
        initialized = false
        lastTimestampMs = 0L
        stage.fill(0f)
    }

    fun process(
        timestampMs: Long,
        x: Float,
        y: Float,
        z: Float
    ): FloatArray {

        if (!initialized) {
            stage[0] = x
            stage[1] = y
            stage[2] = z

            lastTimestampMs = timestampMs
            initialized = true

            return floatArrayOf(x, y, z)
        }

        val dtSec =
            ((timestampMs - lastTimestampMs)
                .coerceIn(1L, 200L)) / 1000f

        lastTimestampMs = timestampMs

        val alpha =
            exp(
                (-2.0 * PI * cutoffHz * dtSec).toFloat()
            )

        val input = floatArrayOf(x, y, z)

        for (i in 0..2) {
            stage[i] =
                alpha * stage[i] +
                        (1f - alpha) * input[i]
        }

        return stage.copyOf()
    }
}
