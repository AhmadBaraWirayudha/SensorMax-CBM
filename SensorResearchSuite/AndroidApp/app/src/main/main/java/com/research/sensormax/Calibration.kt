package com.research.sensormax

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Calibration System
 *
 * IMPORTANT ENGINEERING NOTE
 * --------------------------
 *
 * This file currently implements a STATIC REST CHARACTERIZATION.
 *
 * A stationary accelerometer measures:
 *
 *     measured = gravity + sensor_offset + scale_error + noise
 *
 * Therefore:
 *
 *     mean(X/Y/Z)
 *
 * from ONE resting orientation is NOT a complete physical bias estimate.
 *
 * The existing public fields named biasX/biasY/biasZ are retained for
 * compatibility with the rest of SensorMax, but in a single-position
 * calibration they should technically be interpreted as:
 *
 *     "resting-axis mean"
 *
 * rather than:
 *
 *     "identified accelerometer bias"
 *
 * A true accelerometer calibration should eventually use multiple known
 * orientations (e.g. six-position calibration) to estimate:
 *
 *     - offset / bias
 *     - scale factor
 *     - possibly cross-axis misalignment
 *
 * This implementation therefore focuses on making the STATIC REST
 * characterization trustworthy before adding a multi-position solver.
 *
 * Design goals:
 *     1. Never calibrate while the phone is moving.
 *     2. Ignore startup settling samples.
 *     3. Measure per-axis noise.
 *     4. Measure resultant/vector noise.
 *     5. Estimate achieved sample rate from sensor timestamps.
 *     6. Report timestamp jitter.
 *     7. Detect slow movement/drift during the burst.
 *     8. Provide robust MAD-based noise estimates.
 *     9. Preserve the existing SensorMax API.
 *
 * Same-session behavior remains unchanged:
 *     - CalibrationRecord is in-memory unless another subsystem persists it.
 *     - Calibration is not treated as universal between devices.
 */

/**
 * Result of a static calibration burst.
 *
 * Existing fields are retained for compatibility with SensorEngine.
 *
 * The additional fields provide better measurement-quality information
 * without requiring the current SensorEngine constructor to change.
 */
data class CalibrationBurstStats(

    /*
     * COMPATIBILITY FIELDS
     *
     * These currently represent the mean resting acceleration on each axis.
     *
     * They should NOT be treated as isolated physical sensor bias values
     * unless gravity orientation has also been modeled.
     */
    val biasX: Float,
    val biasY: Float,
    val biasZ: Float,

    /*
     * Classical population-noise estimate.
     *
     * For the current implementation this is calculated as sample standard
     * deviation (n - 1 denominator), which is more appropriate when the
     * measured burst is considered a sample of the sensor's underlying
     * behavior.
     */
    val noiseX: Float,
    val noiseY: Float,
    val noiseZ: Float,

    /*
     * Achieved sampling rate calculated from sensor timestamps.
     */
    val achievedHz: Double,

    /*
     * Total samples supplied to evaluate().
     */
    val sampleCount: Int,

    /*
     * Number of samples actually used after warm-up exclusion.
     */
    val analyzedSampleCount: Int = sampleCount,

    /*
     * Mean magnitude of the measured gravity/acceleration vector.
     *
     * At rest this should be near 9.81 m/s², subject to sensor error,
     * orientation, temperature, and Android vendor processing.
     */
    val gravityMagnitudeMs2: Float = 0f,

    /*
     * Absolute difference between measured gravity magnitude and nominal g.
     *
     * This is a diagnostic metric, NOT automatically a calibration error.
     */
    val gravityMagnitudeErrorMs2: Float = 0f,

    /*
     * Vector noise:
     *
     *     sqrt(noiseX² + noiseY² + noiseZ²)
     *
     * This is a useful single-number characterization of static noise.
     */
    val vectorNoiseMs2: Float = 0f,

    /*
     * Robust noise estimate derived from MAD:
     *
     *     robust sigma ≈ 1.4826 × MAD
     *
     * This is useful when a small number of transient samples would
     * disproportionately affect classical standard deviation.
     */
    val robustNoiseX: Float = 0f,
    val robustNoiseY: Float = 0f,
    val robustNoiseZ: Float = 0f,

    /*
     * Resultant robust noise.
     */
    val robustVectorNoiseMs2: Float = 0f,

    /*
     * Mean-vector drift between the beginning and end of the analyzed burst.
     *
     * A large value suggests the phone moved or its operating state changed.
     */
    val meanVectorDriftMs2: Float = 0f,

    /*
     * Timestamp statistics.
     */
    val minIntervalMs: Long = 0L,
    val maxIntervalMs: Long = 0L,
    val timestampJitterMs: Float = 0f,

    /*
     * Number of timestamps that did not advance.
     *
     * This matters because the current SensorEngine converts Android's
     * nanosecond timestamp to milliseconds before passing it here.
     */
    val nonIncreasingTimestampCount: Int = 0,

    /*
     * Warm-up samples discarded before analysis.
     */
    val warmupDiscardedSamples: Int = 0,

    /*
     * True only when the burst passed the static-stability checks.
     */
    val stationary: Boolean = false,

    /*
     * Human-readable reasons for a failed/weak calibration.
     */
    val validationMessages: List<String> = emptyList()
)


/**
 * Device/sensor-specific calibration record.
 *
 * Existing schema intentionally preserved so current SensorEngine and
 * SnapshotMetadata code remain compatible.
 */
data class CalibrationRecord(

    val calId: String,

    val manufacturer: String,

    val model: String,

    val sensorName: String,

    val sensorVendor: String,

    val sensorVersion: Int,

    /** Android sensor type identifying the physical sensor class. */
    val sensorType: Int = 0,

    /** Android sensor instance id where available (API 2+). */
    val sensorId: Int = 0,

    val mountingMethod: String,

    val dateEpochMs: Long,

    val operatorName: String,

    /*
     * Compatibility fields.
     *
     * In the current single-position implementation these are the measured
     * resting means, not a full gravity-separated bias solution.
     */
    val biasX: Float,
    val biasY: Float,
    val biasZ: Float,

    /*
     * Classical static noise estimate.
     */
    val noiseX: Float,
    val noiseY: Float,
    val noiseZ: Float,

    /*
     * Declared Android sensor range.
     *
     * This is metadata, not a physical range test.
     */
    val declaredRangeMs2: Float,

    /*
     * Actual sampling rate observed during calibration.
     */
    val achievedSamplingHz: Double,

    /*
     * Operator/application notes.
     */
    val notes: String

) {

    /**
     * RSS noise across the three axes.
     *
     * This is retained for compatibility with MeasurementQuality.
     */
    fun noiseMagnitude(): Float =
        sqrt(
            noiseX * noiseX +
                    noiseY * noiseY +
                    noiseZ * noiseZ
        )

    /**
     * Returns the static acceleration magnitude implied by the stored
     * resting means.
     */
    fun gravityMagnitude(): Float =
        sqrt(
            biasX * biasX +
                    biasY * biasY +
                    biasZ * biasZ
        )
}


/**
 * Static calibration / characterization algorithms.
 */
object CalibrationCheck {

    /*
     * Nominal standard gravity.
     *
     * Used only as a physical reference for reporting gravity magnitude.
     */
    private const val NOMINAL_GRAVITY_MS2 = 9.80665f

    /*
     * Default startup settling period.
     *
     * Android sensors and vendor pipelines may require a short period before
     * the signal becomes representative of the stationary condition.
     */
    private const val DEFAULT_WARMUP_NS = 500_000_000L

    /*
     * Minimum usable analyzed sample count.
     *
     * With SENSOR_DELAY_GAME, a three-second burst should normally provide
     * substantially more than this.
     */
    private const val MIN_ANALYZED_SAMPLES = 20

    /*
     * Default maximum vector noise permitted for a "stationary" calibration.
     *
     * This is intentionally not a MEMS chip datasheet limit.
     *
     * It is a practical application-level gate intended to reject a phone
     * that is obviously moving/vibrating during the calibration procedure.
     *
     * It should remain configurable rather than being treated as universal.
     */
    private const val DEFAULT_MAX_VECTOR_NOISE_MS2 = 0.25f

    /*
     * Default maximum beginning-to-end mean-vector drift.
     *
     * This catches cases where the phone slowly changes orientation even
     * though instantaneous noise is relatively small.
     */
    private const val DEFAULT_MAX_MEAN_DRIFT_MS2 = 0.25f


    /**
     * Evaluate a stationary calibration burst.
     *
     * Existing SensorEngine calls this signature without optional arguments,
     * so the current integration remains source-compatible.
     *
     * [xs], [ys], [zs]
     *     Raw accelerometer axis values in m/s².
     *
     * [tsNs]
     *     Sensor-event timestamps in nanoseconds on the sensor monotonic
     *     clock, directly from SensorEvent.timestamp.
     *
     * [warmupNs]
     *     Initial period ignored for statistics, in nanoseconds.
     *
     * [maxVectorNoiseMs2]
     *     Maximum vector noise accepted as "stationary".
     *
     * [maxMeanDriftMs2]
     *     Maximum beginning-to-end mean-vector drift accepted as stationary.
     *
     * Return:
     *     A CalibrationBurstStats object when enough data exists.
     *     null when the input itself is unusable.
     *
     * IMPORTANT:
     *     A non-null result can still have stationary == false.
     *     That distinction is intentional:
     *
     *         null
     *             = malformed / unusable input
     *
     *         result + stationary=false
     *             = valid measurements, but operator should not trust this
     *               burst as a stationary calibration.
     */
    fun evaluate(
        xs: FloatArray,
        ys: FloatArray,
        zs: FloatArray,
        tsNs: LongArray,
        warmupNs: Long = DEFAULT_WARMUP_NS,
        maxVectorNoiseMs2: Float = DEFAULT_MAX_VECTOR_NOISE_MS2,
        maxMeanDriftMs2: Float = DEFAULT_MAX_MEAN_DRIFT_MS2
    ): CalibrationBurstStats? {

        /*
         * ================================================================
         * 1. BASIC ARRAY VALIDATION
         * ================================================================
         */

        val n = xs.size

        if (
            n < 2 ||
            ys.size != n ||
            zs.size != n ||
            tsNs.size != n
        ) {
            return null
        }


        /*
         * ================================================================
         * 2. IDENTIFY WARM-UP REGION
         * ================================================================
         */

        val firstTimestamp =
            tsNs.first()

        val warmupBoundary =
            firstTimestamp +
                    max(
                        0L,
                        warmupNs
                    )

        var startIndex = 0

        while (
            startIndex < n &&
            tsNs[startIndex] < warmupBoundary
        ) {
            startIndex++
        }

        /*
         * If the requested warm-up period consumed almost the entire burst,
         * fall back to the original data rather than silently producing an
         * unusable calibration.
         */
        if (
            n - startIndex <
            MIN_ANALYZED_SAMPLES
        ) {
            startIndex = 0
        }

        val analyzedCount =
            n - startIndex

        if (
            analyzedCount <
            MIN_ANALYZED_SAMPLES
        ) {
            return null
        }


        /*
         * ================================================================
         * 3. COPY ANALYZED DATA
         * ================================================================
         *
         * Keeping the working arrays separate makes all subsequent
         * statistics refer to exactly the same sample subset.
         */

        val ax =
            xs.copyOfRange(
                startIndex,
                n
            )

        val ay =
            ys.copyOfRange(
                startIndex,
                n
            )

        val az =
            zs.copyOfRange(
                startIndex,
                n
            )


        /*
         * ================================================================
         * 4. BASIC MEANS
         * ================================================================
         *
         * These are the values exposed through the existing biasX/Y/Z
         * fields for compatibility.
         *
         * They are NOT a full physical bias solution.
         */

        val meanX =
            mean(ax)

        val meanY =
            mean(ay)

        val meanZ =
            mean(az)


        /*
         * ================================================================
         * 5. CLASSICAL STANDARD DEVIATION
         * ================================================================
         */

        val noiseX =
            sampleStdDev(
                ax,
                meanX
            )

        val noiseY =
            sampleStdDev(
                ay,
                meanY
            )

        val noiseZ =
            sampleStdDev(
                az,
                meanZ
            )


        /*
         * ================================================================
         * 6. VECTOR NOISE
         * ================================================================
         *
         * This represents aggregate 3-axis static variation.
         */

        val vectorNoise =
            sqrt(
                noiseX * noiseX +
                        noiseY * noiseY +
                        noiseZ * noiseZ
            )


        /*
         * ================================================================
         * 7. GRAVITY MAGNITUDE
         * ================================================================
         *
         * We calculate:
         *
         *     |mean acceleration vector|
         *
         * because this is orientation-independent.
         *
         * This does NOT determine per-axis bias.
         */

        val gravityMagnitude =
            sqrt(
                meanX * meanX +
                        meanY * meanY +
                        meanZ * meanZ
            )

        val gravityError =
            abs(
                gravityMagnitude -
                        NOMINAL_GRAVITY_MS2
            )


        /*
         * ================================================================
         * 8. ROBUST MAD NOISE
         * ================================================================
         *
         * Median absolute deviation:
         *
         *     MAD = median(|x - median(x)|)
         *
         * For a Gaussian-distributed process:
         *
         *     sigma ≈ 1.4826 * MAD
         *
         * This gives a second view of the noise that is less influenced by
         * one or two transient samples.
         */

        val robustNoiseX =
            robustSigma(
                ax
            )

        val robustNoiseY =
            robustSigma(
                ay
            )

        val robustNoiseZ =
            robustSigma(
                az
            )

        val robustVectorNoise =
            sqrt(
                robustNoiseX * robustNoiseX +
                        robustNoiseY * robustNoiseY +
                        robustNoiseZ * robustNoiseZ
            )


        /*
         * ================================================================
         * 9. TIMESTAMP QUALITY
         * ================================================================
         *
         * Positive timestamp intervals are used to estimate actual delivery
         * frequency and timing stability.
         *
         * Non-increasing intervals are counted instead of crashing.
         */

        var positiveIntervalCount = 0

        var intervalSum = 0.0

        var minInterval =
            Long.MAX_VALUE

        var maxInterval =
            Long.MIN_VALUE

        var nonIncreasingCount = 0

        /*
         * First pass: interval statistics.
         */

        for (
        i in startIndex + 1 until n
        ) {

            val dtNs =
                tsNs[i] -
                        tsNs[i - 1]

            if (dtNs <= 0L) {

                nonIncreasingCount++

            } else {

                positiveIntervalCount++

                intervalSum +=
                    dtNs.toDouble()

                minInterval =
                    min(
                        minInterval,
                        dtNs
                    )

                maxInterval =
                    max(
                        maxInterval,
                        dtNs
                    )
            }
        }

        /*
         * Calculate sample rate from first and last usable timestamp.
         * SensorEvent timestamps are nanoseconds; public interval fields
         * remain milliseconds for compatibility.
         */
        val elapsedNs =
            (
                    tsNs[n - 1] -
                            tsNs[startIndex]
                    ).toDouble()

        val achievedHz =
            if (
                elapsedNs > 0.0 &&
                analyzedCount > 1
            ) {
                (
                        analyzedCount - 1
                        ) *
                        1_000_000_000.0 /
                        elapsedNs
            } else {
                0.0
            }


        /*
         * ================================================================
         * 10. TIMESTAMP JITTER
         * ================================================================
         */

        val meanIntervalNs =
            if (
                positiveIntervalCount > 0
            ) {
                intervalSum /
                        positiveIntervalCount
            } else {
                0.0
            }

        var intervalSquaredError =
            0.0

        if (
            positiveIntervalCount > 0
        ) {

            for (
            i in startIndex + 1 until n
            ) {

                val dtNs =
                    tsNs[i] -
                            tsNs[i - 1]

                if (dtNs > 0L) {

                    val error =
                        dtNs.toDouble() -
                                meanIntervalNs

                    intervalSquaredError +=
                        error * error
                }
            }
        }

        val timestampJitterMs =
            if (
                positiveIntervalCount > 1
            ) {

                (sqrt(
                    intervalSquaredError /
                            (
                                    positiveIntervalCount - 1
                                    )
                ) / 1_000_000.0).toFloat()

            } else {
                0f
            }


        /*
         * ================================================================
         * 11. BEGINNING-TO-END DRIFT
         * ================================================================
         *
         * Split the analyzed burst into two regions.
         *
         * Compare their mean acceleration vectors.
         *
         * This detects slow movement which normal standard deviation can
         * sometimes hide.
         */

        val half =
            analyzedCount / 2

        val firstHalfX =
            mean(
                ax.copyOfRange(
                    0,
                    half
                )
            )

        val firstHalfY =
            mean(
                ay.copyOfRange(
                    0,
                    half
                )
            )

        val firstHalfZ =
            mean(
                az.copyOfRange(
                    0,
                    half
                )
            )

        val secondHalfX =
            mean(
                ax.copyOfRange(
                    half,
                    analyzedCount
                )
            )

        val secondHalfY =
            mean(
                ay.copyOfRange(
                    half,
                    analyzedCount
                )
            )

        val secondHalfZ =
            mean(
                az.copyOfRange(
                    half,
                    analyzedCount
                )
            )

        val driftX =
            secondHalfX -
                    firstHalfX

        val driftY =
            secondHalfY -
                    firstHalfY

        val driftZ =
            secondHalfZ -
                    firstHalfZ

        val meanVectorDrift =
            sqrt(
                driftX * driftX +
                        driftY * driftY +
                        driftZ * driftZ
            )


        /*
         * ================================================================
         * 12. VALIDATION
         * ================================================================
         */

        val validationMessages =
            mutableListOf<String>()

        /*
         * No positive timestamp progression.
         */
        if (
            achievedHz <= 0.0
        ) {

            validationMessages.add(
                "No usable sensor timestamp progression."
            )
        }

        /*
         * Excessive vector noise.
         */
        if (
            vectorNoise >
            maxVectorNoiseMs2
        ) {

            validationMessages.add(
                "Static noise too high: " +
                        String.format(
                            java.util.Locale.US,
                            "%.3f m/s² RSS > %.3f m/s² limit.",
                            vectorNoise,
                            maxVectorNoiseMs2
                        )
            )
        }

        /*
         * Excessive mean drift.
         */
        if (
            meanVectorDrift >
            maxMeanDriftMs2
        ) {

            validationMessages.add(
                "Mean acceleration drift too high: " +
                        String.format(
                            java.util.Locale.US,
                            "%.3f m/s² > %.3f m/s² limit.",
                            meanVectorDrift,
                            maxMeanDriftMs2
                        )
            )
        }

        /*
         * Non-increasing timestamps are worth reporting but are not always
         * fatal because this method receives millisecond timestamps.
         *
         * Multiple sub-millisecond samples can collapse to the same
         * millisecond after the caller converts Android's nanosecond
         * timestamps.
         */
        if (
            nonIncreasingCount > 0
        ) {

            validationMessages.add(
                "Non-increasing timestamps: " +
                        "$nonIncreasingCount " +
                        "(possible timestamp source/ordering issue)."
            )
        }

        /*
         * Gravity magnitude is diagnostic, not a hard stationary gate.
         *
         * A real stationary sensor with significant bias/scale error can
         * still produce a magnitude different from nominal 1 g. Rejecting
         * it here would prevent us from measuring that error.
         */
        if (
            gravityError >
            1.5f
        ) {

            validationMessages.add(
                String.format(
                    java.util.Locale.US,
                    "Gravity magnitude differs from nominal 9.807 m/s² by %.3f m/s².",
                    gravityError
                )
            )
        }

        /*
         * Stationarity is deliberately based primarily on:
         *
         *     vector noise
         *     mean-vector drift
         *
         * rather than gravity magnitude.
         */
        val stationary =
            achievedHz > 0.0 &&
                    analyzedCount >= MIN_ANALYZED_SAMPLES &&
                    vectorNoise <= maxVectorNoiseMs2 &&
                    meanVectorDrift <= maxMeanDriftMs2


        /*
         * ================================================================
         * 13. INTERVAL EDGE CASE CLEANUP
         * ================================================================
         */

        val finalMinInterval =
            if (
                positiveIntervalCount > 0
            ) {
                minInterval / 1_000_000L
            } else {
                0L
            }

        val finalMaxInterval =
            if (
                positiveIntervalCount > 0
            ) {
                maxInterval / 1_000_000L
            } else {
                0L
            }


        /*
         * ================================================================
         * 14. RESULT
         * ================================================================
         */

        return CalibrationBurstStats(

            biasX =
                meanX,

            biasY =
                meanY,

            biasZ =
                meanZ,

            noiseX =
                noiseX,

            noiseY =
                noiseY,

            noiseZ =
                noiseZ,

            achievedHz =
                achievedHz,

            sampleCount =
                n,

            analyzedSampleCount =
                analyzedCount,

            gravityMagnitudeMs2 =
                gravityMagnitude,

            gravityMagnitudeErrorMs2 =
                gravityError,

            vectorNoiseMs2 =
                vectorNoise,

            robustNoiseX =
                robustNoiseX,

            robustNoiseY =
                robustNoiseY,

            robustNoiseZ =
                robustNoiseZ,

            robustVectorNoiseMs2 =
                robustVectorNoise,

            meanVectorDriftMs2 =
                meanVectorDrift,

            minIntervalMs =
                finalMinInterval,

            maxIntervalMs =
                finalMaxInterval,

            timestampJitterMs =
                timestampJitterMs,

            nonIncreasingTimestampCount =
                nonIncreasingCount,

            warmupDiscardedSamples =
                startIndex,

            stationary =
                stationary,

            validationMessages =
                validationMessages
        )
    }


    // ====================================================================
    // BASIC STATISTICS
    // ====================================================================

    /**
     * Arithmetic mean with Double accumulation to reduce Float precision
     * loss over a longer burst.
     */
    private fun mean(
        values: FloatArray
    ): Float {

        if (values.isEmpty()) {
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


    /**
     * Sample standard deviation.
     *
     * Uses n - 1 denominator.
     */
    private fun sampleStdDev(
        values: FloatArray,
        mean: Float
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
                        mean.toDouble()

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
     * Median of a FloatArray.
     *
     * A copy is sorted so the original sensor data remain untouched.
     */
    private fun median(
        values: FloatArray
    ): Float {

        if (values.isEmpty()) {
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
     * Robust Gaussian-equivalent sigma from MAD.
     *
     * sigma ~= 1.4826 * MAD
     */
    private fun robustSigma(
        values: FloatArray
    ): Float {

        if (
            values.size < 2
        ) {
            return 0f
        }

        val m =
            median(
                values
            )

        val absoluteDeviation =
            FloatArray(
                values.size
            )

        for (
        i in values.indices
        ) {

            absoluteDeviation[i] =
                abs(
                    values[i] -
                            m
                )
        }

        val mad =
            median(
                absoluteDeviation
            )

        return (
                1.4826f *
                        mad
                )
    }
}
