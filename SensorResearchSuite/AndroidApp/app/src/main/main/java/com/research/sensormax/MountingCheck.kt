package com.research.sensormax

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Mounting/orientation pre-check.
 *
 * PURPOSE
 * -------
 * This is a PRE-FLIGHT measurement-quality gate.
 *
 * It does not attempt to identify a machine fault.
 * It answers a simpler question:
 *
 *     "Is the accelerometer physically settled enough to begin a
 *      vibration measurement?"
 *
 *
 * CURRENT INPUT
 * -------------
 * The evaluator receives only raw triaxial accelerometer samples:
 *
 *     X, Y, Z
 *
 * It therefore cannot directly observe:
 *
 *     - machine rotation
 *     - gyroscope angular velocity
 *     - Android TYPE_GRAVITY
 *     - absolute device attitude
 *
 * Instead, it estimates stability from the acceleration vector itself.
 *
 *
 * MEASUREMENTS
 * ------------
 * For each sample:
 *
 *     a = [x, y, z]
 *
 * We derive:
 *
 *     |a|                 acceleration magnitude
 *
 *     direction(a)        normalized acceleration vector
 *
 * Then compare individual samples against a robust reference vector.
 *
 *
 * WHY NOT JUST COMPARE WITH THE MEAN VECTOR?
 * ------------------------------------------
 * The old implementation uses the arithmetic mean acceleration vector as
 * the reference and calculates:
 *
 *     acos(dot(sample, mean) / (|sample| |mean|))
 *
 * That is reasonable, but has several weaknesses:
 *
 *     1. A large transient can move the mean direction.
 *     2. Small-angle acos() is numerically less attractive than atan2().
 *     3. It ignores acceleration-magnitude stability.
 *     4. It does not expose outliers.
 *     5. It does not expose drift between the first and second half.
 *
 *
 * THIS IMPLEMENTATION
 * -------------------
 * Uses:
 *
 *     - mean vector for compatibility with existing behavior
 *     - median magnitude as a robust magnitude reference
 *     - per-sample angular deviation
 *     - RMS angular deviation
 *     - 95th-percentile angular deviation
 *     - maximum angular deviation
 *     - acceleration-magnitude CV
 *     - median absolute magnitude deviation
 *     - beginning/end vector drift
 *     - beginning/end magnitude drift
 *     - outlier counting
 *
 *
 * IMPORTANT
 * ---------
 * The thresholds in this file are APPLICATION DEFAULTS.
 * They are not ISO limits and should not be described as such.
 *
 *
 * EXISTING API COMPATIBILITY
 * --------------------------
 * Existing callers can continue to use:
 *
 *     MountingCheck.evaluate(xs, ys, zs)
 *
 * Existing fields remain:
 *
 *     orientationStabilityDeg
 *     movementDuringSetup
 *     ready
 *     dominantAxis
 *     sampleCount
 *
 * New fields have default values so source compatibility is retained.
 */


/**
 * Result of one mounting/stability check.
 */
data class MountingCheckResult(

    /**
     * Mean angular deviation from the reference gravity direction.
     *
     * This retains the semantic meaning of the original implementation.
     */
    val orientationStabilityDeg: Float,

    /**
     * Simple movement bucket:
     *
     *     LOW
     *     MEDIUM
     *     HIGH
     */
    val movementDuringSetup: String,

    /**
     * True when the burst passes the application's default readiness gate.
     */
    val ready: Boolean,

    /**
     * Axis most aligned with the dominant gravity direction.
     *
     * Determined from the reference acceleration vector.
     */
    val dominantAxis: Axis,

    /**
     * Number of raw samples supplied.
     */
    val sampleCount: Int,

    /**
     * RMS angular deviation.
     *
     * More sensitive to repeated movement than the arithmetic mean.
     */
    val angularRmsDeg: Float = 0f,

    /**
     * 95th percentile angular deviation.
     *
     * Useful because a few brief movements should not disappear entirely
     * inside an average.
     */
    val angularP95Deg: Float = 0f,

    /**
     * Largest angular deviation in the burst.
     */
    val angularMaxDeg: Float = 0f,

    /**
     * Mean acceleration magnitude.
     */
    val meanAccelerationMagnitudeMs2: Float = 0f,

    /**
     * Median acceleration magnitude.
     */
    val medianAccelerationMagnitudeMs2: Float = 0f,

    /**
     * Standard deviation of acceleration magnitude.
     */
    val accelerationMagnitudeStdMs2: Float = 0f,

    /**
     * Coefficient of variation of acceleration magnitude:
     *
     *     std / mean × 100
     */
    val accelerationMagnitudeCvPercent: Float = 0f,

    /**
     * Median absolute deviation of acceleration magnitude.
     */
    val accelerationMagnitudeMadMs2: Float = 0f,

    /**
     * Difference between the first-half and second-half mean acceleration
     * vectors.
     */
    val vectorDriftMs2: Float = 0f,

    /**
     * Difference between the first-half and second-half acceleration
     * magnitudes.
     */
    val magnitudeDriftMs2: Float = 0f,

    /**
     * Samples considered angular/magnitude outliers.
     */
    val outlierCount: Int = 0,

    /**
     * Fraction of samples that were usable.
     *
     * Normally close to 1.0.
     */
    val usableSampleFraction: Float = 0f,

    /**
     * Magnitude of the reference acceleration vector.
     *
     * For a stationary phone this is primarily the gravity vector magnitude.
     */
    val referenceVectorMagnitudeMs2: Float = 0f,

    /**
     * Signed dominant-axis direction.
     *
     * Examples:
     *
     *     +Z
     *     -Z
     *     +X
     *     -Y
     *
     * Useful for documenting physical mounting orientation.
     */
    val dominantAxisDirection: String = "UNKNOWN",

    /**
     * Human-readable diagnostic messages.
     */
    val diagnostics: List<String> = emptyList(),

    /** Android sensor type used for this check. */
    val sensorType: Int = 0,

    /** Android sensor instance id used for this check. */
    val sensorId: Int = 0,

    /** Sensor identity snapshot used for provenance matching. */
    val sensorName: String = "",
    val sensorVendor: String = "",
    val sensorVersion: Int = 0,

    /** Operator-selected physical mounting method at check time. */
    val mountingMethod: String = ""
)


/**
 * Mounting/stability evaluator.
 */
object MountingCheck {

    // =====================================================================
    // APPLICATION DEFAULTS
    // =====================================================================

    /**
     * Minimum usable acceleration magnitude.
     *
     * Near-zero vectors do not provide a meaningful direction.
     */
    private const val MIN_VECTOR_MAGNITUDE_MS2 = 0.25f

    /**
     * Default angular thresholds.
     *
     * These are application defaults, not standards.
     */
    private const val LOW_ANGLE_DEG = 2f
    private const val MEDIUM_ANGLE_DEG = 6f

    /**
     * Additional magnitude-stability thresholds.
     *
     * These are intentionally moderate application-level defaults.
     *
     * A phone may be perfectly oriented while still vibrating strongly.
     */
    private const val READY_MAGNITUDE_CV_PERCENT = 3.0f
    private const val MEDIUM_MAGNITUDE_CV_PERCENT = 8.0f

    /**
     * Drift thresholds.
     *
     * These detect slow movement between the start and end of the burst.
     */
    private const val READY_VECTOR_DRIFT_MS2 = 0.25f
    private const val MEDIUM_VECTOR_DRIFT_MS2 = 0.75f

    /**
     * Outlier threshold for a sample.
     *
     * Used as an additional diagnostic rather than the sole readiness gate.
     */
    private const val OUTLIER_ANGLE_DEG = 8f
    private const val OUTLIER_MAGNITUDE_DEVIATION_MS2 = 1.0f

    /**
     * Minimum usable-sample fraction.
     */
    private const val MIN_USABLE_SAMPLE_FRACTION = 0.90f

    /**
     * Nominal standard gravity.
     *
     * Used only as a physical reference for diagnostics.
     */
    private const val NOMINAL_GRAVITY_MS2 = 9.80665f


    // =====================================================================
    // PUBLIC ENTRY POINT
    // =====================================================================

    /**
     * Evaluate a raw triaxial accelerometer burst.
     *
     * Existing call:
     *
     *     MountingCheck.evaluate(xs, ys, zs)
     *
     * remains valid.
     *
     * Optional threshold parameters are exposed so a future UI or machine
     * profile can tune them without rewriting the algorithm.
     */
    fun evaluate(
        xs: FloatArray,
        ys: FloatArray,
        zs: FloatArray,
        readyAngleDeg: Float = LOW_ANGLE_DEG,
        mediumAngleDeg: Float = MEDIUM_ANGLE_DEG,
        readyMagnitudeCvPercent: Float =
            READY_MAGNITUDE_CV_PERCENT,
        readyVectorDriftMs2: Float =
            READY_VECTOR_DRIFT_MS2
    ): MountingCheckResult? {

        // =================================================================
        // 1. BASIC VALIDATION
        // =================================================================

        val n =
            xs.size

        if (
            n < 2 ||
            ys.size != n ||
            zs.size != n
        ) {
            return null
        }


        // =================================================================
        // 2. BUILD USABLE SAMPLE SET
        // =================================================================
        //
        // A corrupt/non-finite sensor value should not poison the entire
        // reference vector.
        //
        // We retain the original sample count but count only finite samples
        // for the actual calculation.
        //

        val usableX =
            ArrayList<Float>(
                n
            )

        val usableY =
            ArrayList<Float>(
                n
            )

        val usableZ =
            ArrayList<Float>(
                n
            )

        for (
        i in 0 until n
        ) {

            val x =
                xs[i]

            val y =
                ys[i]

            val z =
                zs[i]

            if (
                x.isFinite() &&
                y.isFinite() &&
                z.isFinite()
            ) {

                val magnitude =
                    magnitude(
                        x,
                        y,
                        z
                    )

                if (
                    magnitude >=
                    MIN_VECTOR_MAGNITUDE_MS2
                ) {

                    usableX.add(
                        x
                    )

                    usableY.add(
                        y
                    )

                    usableZ.add(
                        z
                    )
                }
            }
        }

        val usableCount =
            usableX.size

        val usableFraction =
            usableCount.toFloat() /
                    n.toFloat()

        if (
            usableCount < 2 ||
            usableFraction < MIN_USABLE_SAMPLE_FRACTION
        ) {

            return MountingCheckResult(
                orientationStabilityDeg = Float.POSITIVE_INFINITY,
                movementDuringSetup = "HIGH",
                ready = false,
                dominantAxis = Axis.Z,
                sampleCount = n,
                usableSampleFraction =
                    usableFraction,
                diagnostics =
                    listOf(
                        "Insufficient usable accelerometer samples.",
                        String.format(
                            java.util.Locale.US,
                            "Usable %.1f%% of burst.",
                            usableFraction * 100f
                        )
                    )
            )
        }


        // =================================================================
        // 3. MEAN REFERENCE VECTOR
        // =================================================================

        val meanX =
            mean(
                usableX
            )

        val meanY =
            mean(
                usableY
            )

        val meanZ =
            mean(
                usableZ
            )

        val referenceMagnitude =
            magnitude(
                meanX,
                meanY,
                meanZ
            )

        /*
         * A very small mean vector means the acceleration direction is not
         * useful as a gravity reference.
         *
         * This can happen during free-fall or from a broken data stream.
         */
        if (
            referenceMagnitude <
            MIN_VECTOR_MAGNITUDE_MS2
        ) {

            return MountingCheckResult(
                orientationStabilityDeg = 0f,
                movementDuringSetup = "HIGH",
                ready = false,
                dominantAxis = Axis.Z,
                sampleCount = n,
                usableSampleFraction =
                    usableFraction,
                referenceVectorMagnitudeMs2 =
                    referenceMagnitude,
                diagnostics =
                    listOf(
                        "Reference acceleration vector is too small.",
                        "No reliable gravity direction could be established."
                    )
            )
        }


        // =================================================================
        // 4. MEDIAN MAGNITUDE
        // =================================================================
        //
        // Median is more robust than the mean against an individual shock.
        //

        val magnitudes =
            FloatArray(
                usableCount
            )

        for (
        i in 0 until usableCount
        ) {

            magnitudes[i] =
                magnitude(
                    usableX[i],
                    usableY[i],
                    usableZ[i]
                )
        }

        val medianMagnitude =
            median(
                magnitudes
            )

        val meanMagnitude =
            mean(
                magnitudes
            )

        val magnitudeStd =
            sampleStdDev(
                magnitudes,
                meanMagnitude
            )

        val magnitudeCvPercent =
            if (
                meanMagnitude > 1e-6f
            ) {

                (
                        magnitudeStd /
                                meanMagnitude
                        ) *
                        100f

            } else {
                Float.POSITIVE_INFINITY
            }

        val magnitudeMad =
            medianAbsoluteDeviation(
                magnitudes
            )


        // =================================================================
        // 5. ANGULAR DEVIATION
        // =================================================================
        //
        // Reference vector:
        //
        //     r = [meanX, meanY, meanZ]
        //
        // We calculate:
        //
        //     angle = atan2(|r × v|, r · v)
        //
        // rather than acos(dot(...)).
        //
        // For small angles, atan2 is numerically well behaved.
        //

        val angularDeviation =
            FloatArray(
                usableCount
            )

        var sumAngle =
            0.0

        var sumAngleSquared =
            0.0

        var maxAngle =
            0f

        for (
        i in 0 until usableCount
        ) {

            val x =
                usableX[i]

            val y =
                usableY[i]

            val z =
                usableZ[i]

            /*
             * Dot product:
             */
            val dot =
                (
                        x.toDouble() *
                                meanX.toDouble()
                        ) +
                        (
                                y.toDouble() *
                                        meanY.toDouble()
                                ) +
                        (
                                z.toDouble() *
                                        meanZ.toDouble()
                                )

            /*
             * Cross-product magnitude.
             */
            val crossX =
                y.toDouble() *
                        meanZ.toDouble() -
                        z.toDouble() *
                        meanY.toDouble()

            val crossY =
                z.toDouble() *
                        meanX.toDouble() -
                        x.toDouble() *
                        meanZ.toDouble()

            val crossZ =
                x.toDouble() *
                        meanY.toDouble() -
                        y.toDouble() *
                        meanX.toDouble()

            val crossMagnitude =
                sqrt(
                    crossX * crossX +
                            crossY * crossY +
                            crossZ * crossZ
                )

            val angleRad =
                atan2(
                    crossMagnitude,
                    abs(dot)
                )

            /*
             * Directional angle should normally consider the gravity vector
             * as an axis direction rather than distinguish +g from -g for
             * this stability check.
             *
             * Using abs(dot) therefore treats a 180° flip as 0°, which is
             * NOT appropriate for mounting readiness.
             *
             * So correct it below using the sign of dot.
             */
            val correctedAngleRad =
                if (
                    dot >= 0.0
                ) {

                    angleRad

                } else {

                    Math.PI -
                            angleRad
                }

            val angleDeg =
                Math.toDegrees(
                    correctedAngleRad
                ).toFloat()

            angularDeviation[i] =
                angleDeg

            sumAngle +=
                angleDeg.toDouble()

            sumAngleSquared +=
                angleDeg.toDouble() *
                        angleDeg.toDouble()

            if (
                angleDeg >
                maxAngle
            ) {

                maxAngle =
                    angleDeg
            }
        }

        val meanAngle =
            (
                    sumAngle /
                            usableCount.toDouble()
                    ).toFloat()

        val angularRms =
            sqrt(
                sumAngleSquared /
                        usableCount.toDouble()
            ).toFloat()

        val angularP95 =
            percentile(
                angularDeviation,
                0.95f
            )


        // =================================================================
        // 6. VECTOR DRIFT
        // =================================================================
        //
        // Split the burst into two halves.
        //
        // If the phone slowly rotates, the full-burst mean can hide some of
        // that movement. Comparing beginning and end exposes it.
        //

        val half =
            usableCount / 2

        val firstHalfStart =
            0

        val firstHalfEnd =
            half

        val secondHalfStart =
            half

        val secondHalfEnd =
            usableCount

        val firstMeanX =
            meanRange(
                usableX,
                firstHalfStart,
                firstHalfEnd
            )

        val firstMeanY =
            meanRange(
                usableY,
                firstHalfStart,
                firstHalfEnd
            )

        val firstMeanZ =
            meanRange(
                usableZ,
                firstHalfStart,
                firstHalfEnd
            )

        val secondMeanX =
            meanRange(
                usableX,
                secondHalfStart,
                secondHalfEnd
            )

        val secondMeanY =
            meanRange(
                usableY,
                secondHalfStart,
                secondHalfEnd
            )

        val secondMeanZ =
            meanRange(
                usableZ,
                secondHalfStart,
                secondHalfEnd
            )

        val vectorDrift =
            magnitude(
                secondMeanX - firstMeanX,
                secondMeanY - firstMeanY,
                secondMeanZ - firstMeanZ
            )

        val firstMagnitude =
            magnitude(
                firstMeanX,
                firstMeanY,
                firstMeanZ
            )

        val secondMagnitude =
            magnitude(
                secondMeanX,
                secondMeanY,
                secondMeanZ
            )

        val magnitudeDrift =
            abs(
                secondMagnitude -
                        firstMagnitude
            )


        // =================================================================
        // 7. OUTLIERS
        // =================================================================
        //
        // An outlier can indicate:
        //
        //     - accidental touch
        //     - mounting slip
        //     - shock
        //     - cable/fixture disturbance
        //     - transient movement
        //

        var outlierCount =
            0

        for (
        i in angularDeviation.indices
        ) {

            val angleOutlier =
                angularDeviation[i] >
                        OUTLIER_ANGLE_DEG

            val magnitudeOutlier =
                abs(
                    magnitudes[i] -
                            medianMagnitude
                ) >
                        OUTLIER_MAGNITUDE_DEVIATION_MS2

            if (
                angleOutlier ||
                magnitudeOutlier
            ) {

                outlierCount++
            }
        }


        // =================================================================
        // 8. DOMINANT AXIS
        // =================================================================

        val dominantAxis =
            when {

                abs(meanX) >=
                        abs(meanY) &&
                        abs(meanX) >=
                        abs(meanZ) -> {

                    Axis.X
                }

                abs(meanY) >=
                        abs(meanZ) -> {

                    Axis.Y
                }

                else -> {

                    Axis.Z
                }
            }

        val dominantAxisValue =
            when (
                dominantAxis
            ) {

                Axis.X ->
                    meanX

                Axis.Y ->
                    meanY

                Axis.Z ->
                    meanZ
            }

        val dominantAxisDirection =
            when {

                dominantAxisValue >=
                        0f -> {

                    "+${dominantAxis.name}"
                }

                else -> {

                    "-${dominantAxis.name}"
                }
            }


        // =================================================================
        // 9. MOVEMENT CLASSIFICATION
        // =================================================================
        //
        // This is intentionally multi-factor.
        //
        // Angular motion:
        //     mean / P95 / max
        //
        // Magnitude motion:
        //     CV
        //
        // Slow drift:
        //     beginning/end vector difference
        //

        val angleMovement =
            when {

                angularP95 <=
                        readyAngleDeg -> {

                    "LOW"
                }

                angularP95 <=
                        mediumAngleDeg -> {

                    "MEDIUM"
                }

                else -> {

                    "HIGH"
                }
            }

        val magnitudeMovement =
            when {

                magnitudeCvPercent <=
                        readyMagnitudeCvPercent -> {

                    "LOW"
                }

                magnitudeCvPercent <=
                        MEDIUM_MAGNITUDE_CV_PERCENT -> {

                    "MEDIUM"
                }

                else -> {

                    "HIGH"
                }
            }

        val driftMovement =
            when {

                vectorDrift <=
                        readyVectorDriftMs2 -> {

                    "LOW"
                }

                vectorDrift <=
                        MEDIUM_VECTOR_DRIFT_MS2 -> {

                    "MEDIUM"
                }

                else -> {

                    "HIGH"
                }
            }


        val movement =
            when {

                angleMovement == "HIGH" ||
                        magnitudeMovement == "HIGH" ||
                        driftMovement == "HIGH" -> {

                    "HIGH"
                }

                angleMovement == "MEDIUM" ||
                        magnitudeMovement == "MEDIUM" ||
                        driftMovement == "MEDIUM" -> {

                    "MEDIUM"
                }

                else -> {

                    "LOW"
                }
            }


        // =================================================================
        // 10. READINESS GATE
        // =================================================================
        //
        // All major stability conditions must pass.
        //
        // This intentionally errs toward:
        //
        //     "do not call a questionable mounting condition READY"
        //
        // because the cost of a second mounting check is much lower than
        // silently attaching poor setup conditions to a vibration result.
        //

        val ready =
            usableFraction >=
                    MIN_USABLE_SAMPLE_FRACTION &&

                    meanAngle <=
                    readyAngleDeg &&

                    angularP95 <=
                    readyAngleDeg * 1.5f &&

                    magnitudeCvPercent <=
                    readyMagnitudeCvPercent &&

                    vectorDrift <=
                    readyVectorDriftMs2 &&

                    outlierCount <=
                    max(
                        1,
                        (usableCount * 0.01f).toInt()
                    )


        // =================================================================
        // 11. DIAGNOSTICS
        // =================================================================

        val diagnostics =
            mutableListOf<String>()

        diagnostics.add(
            String.format(
                java.util.Locale.US,
                "Direction mean %.2f°, RMS %.2f°, P95 %.2f°, max %.2f°.",
                meanAngle,
                angularRms,
                angularP95,
                maxAngle
            )
        )

        diagnostics.add(
            String.format(
                java.util.Locale.US,
                "Acceleration magnitude mean %.3f m/s², median %.3f, CV %.2f%%.",
                meanMagnitude,
                medianMagnitude,
                magnitudeCvPercent
            )
        )

        diagnostics.add(
            String.format(
                java.util.Locale.US,
                "Vector drift %.3f m/s²; magnitude drift %.3f m/s².",
                vectorDrift,
                magnitudeDrift
            )
        )

        diagnostics.add(
            String.format(
                java.util.Locale.US,
                "Usable samples %.1f%% (%d/%d).",
                usableFraction * 100f,
                usableCount,
                n
            )
        )

        diagnostics.add(
            String.format(
                java.util.Locale.US,
                "Reference acceleration magnitude %.3f m/s² (nominal gravity %.3f).",
                referenceMagnitude,
                NOMINAL_GRAVITY_MS2
            )
        )

        diagnostics.add(
            "Dominant gravity axis: $dominantAxisDirection."
        )

        if (
            meanMagnitude <
            8.5f
        ) {

            diagnostics.add(
                "Warning: acceleration magnitude is unusually low for a stationary gravity reference."
            )
        }

        if (
            meanMagnitude >
            11.2f
        ) {

            diagnostics.add(
                "Warning: acceleration magnitude is unusually high for a stationary gravity reference."
            )
        }

        if (
            angularP95 >
            readyAngleDeg
        ) {

            diagnostics.add(
                "Orientation is not sufficiently stable for the READY gate."
            )
        }

        if (
            magnitudeCvPercent >
            readyMagnitudeCvPercent
        ) {

            diagnostics.add(
                "Acceleration magnitude is varying; possible vibration or movement."
            )
        }

        if (
            vectorDrift >
            readyVectorDriftMs2
        ) {

            diagnostics.add(
                "Beginning/end vector drift indicates possible mounting movement."
            )
        }

        if (
            outlierCount > 0
        ) {

            diagnostics.add(
                "$outlierCount outlier sample(s) detected in the setup burst."
            )
        }


        // =================================================================
        // 12. FINAL RESULT
        // =================================================================

        return MountingCheckResult(

            orientationStabilityDeg =
                meanAngle,

            movementDuringSetup =
                movement,

            ready =
                ready,

            dominantAxis =
                dominantAxis,

            sampleCount =
                n,

            angularRmsDeg =
                angularRms,

            angularP95Deg =
                angularP95,

            angularMaxDeg =
                maxAngle,

            meanAccelerationMagnitudeMs2 =
                meanMagnitude,

            medianAccelerationMagnitudeMs2 =
                medianMagnitude,

            accelerationMagnitudeStdMs2 =
                magnitudeStd,

            accelerationMagnitudeCvPercent =
                magnitudeCvPercent,

            accelerationMagnitudeMadMs2 =
                magnitudeMad,

            vectorDriftMs2 =
                vectorDrift,

            magnitudeDriftMs2 =
                magnitudeDrift,

            outlierCount =
                outlierCount,

            usableSampleFraction =
                usableFraction,

            referenceVectorMagnitudeMs2 =
                referenceMagnitude,

            dominantAxisDirection =
                dominantAxisDirection,

            diagnostics =
                diagnostics
        )
    }


    // =====================================================================
    // NUMERIC HELPERS
    // =====================================================================

    private fun magnitude(
        x: Float,
        y: Float,
        z: Float
    ): Float {

        return sqrt(
            (
                    x.toDouble() * x.toDouble() +
                            y.toDouble() * y.toDouble() +
                            z.toDouble() * z.toDouble()
                    )
        ).toFloat()
    }


    private fun mean(
        values: List<Float>
    ): Float {

        if (
            values.isEmpty()
        ) {
            return 0f
        }

        var sum =
            0.0

        for (
        value in values
        ) {

            sum +=
                value.toDouble()
        }

        return (
                sum /
                        values.size
                ).toFloat()
    }


    private fun mean(
        values: FloatArray
    ): Float {

        if (
            values.isEmpty()
        ) {
            return 0f
        }

        var sum =
            0.0

        for (
        value in values
        ) {

            sum +=
                value.toDouble()
        }

        return (
                sum /
                        values.size
                ).toFloat()
    }


    private fun meanRange(
        values: List<Float>,
        start: Int,
        end: Int
    ): Float {

        if (
            start >= end ||
            start < 0 ||
            end > values.size
        ) {
            return 0f
        }

        var sum =
            0.0

        for (
        i in start until end
        ) {

            sum +=
                values[i].toDouble()
        }

        return (
                sum /
                        (end - start).toDouble()
                ).toFloat()
    }


    /**
     * Sample standard deviation.
     *
     * Uses n - 1 denominator.
     */
    private fun sampleStdDev(
        values: FloatArray,
        meanValue: Float
    ): Float {

        if (
            values.size < 2
        ) {
            return 0f
        }

        var sumSquared =
            0.0

        for (
        value in values
        ) {

            val delta =
                value.toDouble() -
                        meanValue.toDouble()

            sumSquared +=
                delta * delta
        }

        return sqrt(
            sumSquared /
                    (
                            values.size - 1
                            )
        ).toFloat()
    }


    /**
     * Median.
     */
    private fun median(
        values: FloatArray
    ): Float {

        if (
            values.isEmpty()
        ) {
            return 0f
        }

        val sorted =
            values.copyOf()

        sorted.sort()

        val middle =
            sorted.size / 2

        return if (
            sorted.size % 2 == 0
        ) {

            (
                    sorted[middle - 1] +
                            sorted[middle]
                    ) / 2f

        } else {

            sorted[middle]
        }
    }


    /**
     * Median absolute deviation.
     *
     * MAD =
     *
     *     median(|x - median(x)|)
     *
     * It is a robust measure of spread.
     */
    private fun medianAbsoluteDeviation(
        values: FloatArray
    ): Float {

        if (
            values.isEmpty()
        ) {
            return 0f
        }

        val center =
            median(
                values
            )

        val deviations =
            FloatArray(
                values.size
            )

        for (
        i in values.indices
        ) {

            deviations[i] =
                abs(
                    values[i] -
                            center
                )
        }

        return median(
            deviations
        )
    }


    /**
     * Percentile via sorted copy.
     */
    private fun percentile(
        values: FloatArray,
        percentile: Float
    ): Float {

        if (
            values.isEmpty()
        ) {
            return 0f
        }

        val sorted =
            values.copyOf()

        sorted.sort()

        val p =
            percentile.coerceIn(
                0f,
                1f
            )

        val index =
            (
                    p *
                            (
                                    sorted.size - 1
                                    )
                    )
                .toInt()
                .coerceIn(
                    0,
                    sorted.lastIndex
                )

        return sorted[index]
    }
}
