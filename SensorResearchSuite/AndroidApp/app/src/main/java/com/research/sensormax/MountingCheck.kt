package com.research.sensormax

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Mounting/orientation pre-check (design-debt Section 17): "Use
 * accelerometer + gravity/rotation information to support a
 * pre-measurement check."
 *
 * Runs on a short burst (a couple of seconds) of raw accelerometer
 * samples -- gravity is the dominant DC component when the phone is held
 * roughly still, so no separate TYPE_GRAVITY sensor registration is
 * needed. This is a one-off pre-flight check, not part of the continuous
 * FFT pipeline in VibrationAnalyzer.
 *
 * "Orientation stability" is the average angular deviation of each
 * sample's vector from the burst's own mean direction -- how much the
 * phone rocked or drifted during the check, not an absolute compass
 * heading. The movement-bucket cutoffs (2 deg / 6 deg) are a plain
 * default written for this app, not a cited standard -- same spirit as
 * the CV cutoffs in SensorEngine's repeatability check.
 */

data class MountingCheckResult(
    val orientationStabilityDeg: Float,
    val movementDuringSetup: String,   // "LOW" / "MEDIUM" / "HIGH"
    val ready: Boolean,
    /** Which axis is most aligned with gravity right now -- i.e. which one is "down". */
    val dominantAxis: Axis,
    val sampleCount: Int
)

object MountingCheck {

    fun evaluate(xs: FloatArray, ys: FloatArray, zs: FloatArray): MountingCheckResult? {
        val n = xs.size
        if (n < 2 || ys.size != n || zs.size != n) {
            return null
        }

        var mx = 0.0
        var my = 0.0
        var mz = 0.0
        for (i in 0 until n) {
            mx += xs[i]
            my += ys[i]
            mz += zs[i]
        }
        mx /= n
        my /= n
        mz /= n

        val meanMag = sqrt(mx * mx + my * my + mz * mz)
        if (meanMag < 1e-6) {
            // No usable gravity signal (e.g. free-fall or a broken sensor feed) -- can't judge stability.
            return MountingCheckResult(0f, "HIGH", false, Axis.Z, n)
        }

        var angleSumDeg = 0.0
        var counted = 0
        for (i in 0 until n) {
            val vx = xs[i].toDouble()
            val vy = ys[i].toDouble()
            val vz = zs[i].toDouble()
            val vMag = sqrt(vx * vx + vy * vy + vz * vz)
            if (vMag < 1e-6) continue

            var cosTheta = (vx * mx + vy * my + vz * mz) / (vMag * meanMag)
            cosTheta = cosTheta.coerceIn(-1.0, 1.0)
            angleSumDeg += Math.toDegrees(acos(cosTheta))
            counted++
        }

        val avgAngleDeg = if (counted > 0) (angleSumDeg / counted).toFloat() else 0f

        val movement = when {
            avgAngleDeg <= 2f -> "LOW"
            avgAngleDeg <= 6f -> "MEDIUM"
            else -> "HIGH"
        }
        val ready = avgAngleDeg <= 2f

        val dominantAxis = when {
            abs(mx) >= abs(my) && abs(mx) >= abs(mz) -> Axis.X
            abs(my) >= abs(mz) -> Axis.Y
            else -> Axis.Z
        }

        return MountingCheckResult(
            orientationStabilityDeg = avgAngleDeg,
            movementDuringSetup = movement,
            ready = ready,
            dominantAxis = dominantAxis,
            sampleCount = n
        )
    }
}
