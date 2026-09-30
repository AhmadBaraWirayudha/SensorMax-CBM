package com.research.sensormax

import kotlin.math.sqrt

/**
 * Calibration System (design-debt Section 33): "Calibration must be per
 * device/sensor configuration... Never use one phone's calibration as
 * universal calibration for another phone."
 *
 * This is a same-session, in-memory record only -- there is no
 * persistence layer yet (Section 12/26), so a calibration does not
 * survive an app restart. That also means the "never use one phone's
 * calibration for another" caution is structurally satisfied for free:
 * nothing here is ever exported or shared between devices.
 *
 * "Range test" and "Sampling test" in the doc's field list are reported
 * as the sensor's declared Sensor.maximumRange and the achieved sampling
 * rate during the calibration burst -- not an induced test that pushes
 * the sensor toward its physical limits, which isn't something this app
 * can safely prompt an operator to do.
 */

data class CalibrationBurstStats(
    val biasX: Float,
    val biasY: Float,
    val biasZ: Float,
    val noiseX: Float,
    val noiseY: Float,
    val noiseZ: Float,
    val achievedHz: Double,
    val sampleCount: Int
)

data class CalibrationRecord(
    val calId: String,
    val manufacturer: String,
    val model: String,
    val sensorName: String,
    val sensorVendor: String,
    val sensorVersion: Int,
    val mountingMethod: String,
    val dateEpochMs: Long,
    val operatorName: String,
    val biasX: Float,
    val biasY: Float,
    val biasZ: Float,
    val noiseX: Float,
    val noiseY: Float,
    val noiseZ: Float,
    val declaredRangeMs2: Float,
    val achievedSamplingHz: Double,
    val notes: String
) {
    /** RSS noise across the three axes -- the single number MeasurementQuality actually grades on. */
    fun noiseMagnitude(): Float =
        sqrt(noiseX * noiseX + noiseY * noiseY + noiseZ * noiseZ)
}

object CalibrationCheck {

    /** Given a raw burst (while the sensor is meant to be at rest), computes per-axis bias/noise and the achieved sample rate. */
    fun evaluate(xs: FloatArray, ys: FloatArray, zs: FloatArray, tsMs: LongArray): CalibrationBurstStats? {
        val n = xs.size
        if (n < 2 || ys.size != n || zs.size != n || tsMs.size != n) {
            return null
        }

        fun mean(a: FloatArray): Float {
            var s = 0.0
            for (v in a) s += v.toDouble()
            return (s / a.size).toFloat()
        }

        fun std(a: FloatArray, m: Float): Float {
            var s = 0.0
            for (v in a) {
                val d = v.toDouble() - m
                s += d * d
            }
            return sqrt(s / a.size).toFloat()
        }

        val bx = mean(xs)
        val by = mean(ys)
        val bz = mean(zs)

        val elapsedMs = (tsMs[n - 1] - tsMs[0]).toDouble()
        val achievedHz = if (elapsedMs > 0.0) (n - 1) * 1000.0 / elapsedMs else 0.0

        return CalibrationBurstStats(
            biasX = bx,
            biasY = by,
            biasZ = bz,
            noiseX = std(xs, bx),
            noiseY = std(ys, by),
            noiseZ = std(zs, bz),
            achievedHz = achievedHz,
            sampleCount = n
        )
    }
}
