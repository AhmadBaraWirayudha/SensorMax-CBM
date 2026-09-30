package com.research.sensormax

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Independent measurement-quality subsystem (design-debt Section 16):
 *
 * "The quality score should be kept separate from machine-health state.
 *  A machine can be healthy but badly measured, or abnormal but very
 *  well measured."
 *
 * This file never looks at RMS level, ISO zone, or fault hints -- only at
 * how trustworthy the measurement itself was.
 *
 * Two of the components the design doc lists for this score are still
 * conditional or missing: mounting stability and orientation stability
 * are scored for real once "Check Mounting" has been run this session
 * (see [MountingCheck]), but read as NOT_AVAILABLE until then; calibration
 * state has no implementation at all yet (Section 33 is still an open
 * TODO). Rather than fake a passing grade for what hasn't been checked,
 * these are excluded from the score's denominator whenever they're
 * NOT_AVAILABLE, so the number that IS shown only reflects what was
 * actually checked.
 *
 * Scoring bands (PASS/WARN/FAIL cutoffs) below are a plain, documented
 * heuristic written for this app -- they are NOT derived from the IAFMI
 * CRV paper or ISO 17359, which don't specify a phone-based scoring
 * formula. Treat the 0-100 number as a relative indicator to compare
 * sessions with, not a certified metric (same spirit as design-debt
 * Section 19, threshold provenance).
 */

enum class QualityGrade { PASS, WARN, FAIL, INFO, NOT_AVAILABLE }

data class QualityFactor(
    val name: String,
    val grade: QualityGrade,
    val detail: String
)

data class QualityScore(
    val score0to100: Int,
    val factors: List<QualityFactor>
) {
    /** Factors worth calling out on a compact single-line UI -- anything not a plain PASS/INFO. */
    fun notableFactors(): List<QualityFactor> =
        factors.filter { it.grade == QualityGrade.WARN || it.grade == QualityGrade.FAIL }
}

object MeasurementQuality {

    /**
     * @param measuredHz actual FFT sample rate for this window
     * @param requestedHz operator-requested capture rate (the throttle slider)
     * @param jitterMs [SpectrumResult.timestampJitterMs] for this window
     * @param maxGapMs [SpectrumResult.maxSampleGapMs] for this window
     * @param acceptedEvents vibration-source events actually used for FFT so far this session
     * @param rawEvents vibration-source events seen so far this session (pre-throttle)
     * @param rawPeakAbs largest |raw sample| seen in this window, m/s^2 (pre mean-removal), max across axes
     * @param sensorMaxRange the vibration sensor's hardware Sensor.maximumRange, m/s^2 (0f if unknown)
     * @param resolutionHz FFT bin resolution for this window -- informational only, not scored
     * @param mountingCheck the most recent [MountingCheckResult] run this session, or null if
     *   "Check Mounting" hasn't been pressed yet -- Mounting/Orientation stay NOT_AVAILABLE until then
     */
    fun evaluate(
        measuredHz: Double,
        requestedHz: Double,
        jitterMs: Float,
        maxGapMs: Float,
        acceptedEvents: Long,
        rawEvents: Long,
        rawPeakAbs: Float,
        sensorMaxRange: Float,
        resolutionHz: Double,
        mountingCheck: MountingCheckResult? = null,
        calibration: CalibrationRecord? = null
    ): QualityScore {

        val factors = mutableListOf<QualityFactor>()
        val meanDtMs = if (measuredHz > 0.0) 1000.0 / measuredHz else 0.0

        // -- Sampling: how close the measured rate came to what was requested --
        val samplingRatio = if (requestedHz > 0.0) (measuredHz / requestedHz).coerceAtMost(1.0) else 1.0
        factors += QualityFactor(
            "Sampling",
            when {
                samplingRatio >= 0.95 -> QualityGrade.PASS
                samplingRatio >= 0.80 -> QualityGrade.WARN
                else -> QualityGrade.FAIL
            },
            String.format(Locale.US, "%.1f Hz measured vs %.1f Hz requested (%.0f%%)", measuredHz, requestedHz, samplingRatio * 100.0)
        )

        // -- Jitter: how evenly spaced the samples actually were, relative to the mean interval --
        val jitterRatio = if (meanDtMs > 0.0) jitterMs / meanDtMs else 0.0
        factors += QualityFactor(
            "Jitter",
            when {
                jitterRatio <= 0.15 -> QualityGrade.PASS
                jitterRatio <= 0.40 -> QualityGrade.WARN
                else -> QualityGrade.FAIL
            },
            String.format(Locale.US, "%.2f ms (mean interval %.2f ms)", jitterMs, meanDtMs)
        )

        // -- Window completeness: was there a stall inside this window? --
        val gapRatio = if (meanDtMs > 0.0) maxGapMs / meanDtMs else 0.0
        factors += QualityFactor(
            "Window completeness",
            when {
                gapRatio <= 3.0 -> QualityGrade.PASS
                gapRatio <= 8.0 -> QualityGrade.WARN
                else -> QualityGrade.FAIL
            },
            String.format(Locale.US, "largest gap %.1fx the mean interval", gapRatio)
        )

        // -- Dropped/irregular events: this session's throttle-drop ratio so far --
        val acceptRatio = if (rawEvents > 0) acceptedEvents.toDouble() / rawEvents else 1.0
        factors += QualityFactor(
            "Dropped events",
            when {
                acceptRatio >= 0.90 -> QualityGrade.PASS
                acceptRatio >= 0.70 -> QualityGrade.WARN
                else -> QualityGrade.FAIL
            },
            String.format(Locale.US, "%d/%d vibration events used (%.0f%%)", acceptedEvents, rawEvents, acceptRatio * 100.0)
        )

        // -- Sensor clipping / range margin --
        if (sensorMaxRange > 0f) {
            val marginRatio = 1f - (rawPeakAbs / sensorMaxRange).coerceIn(0f, 1f)
            factors += QualityFactor(
                "Sensor range margin",
                when {
                    marginRatio >= 0.30f -> QualityGrade.PASS
                    marginRatio >= 0.10f -> QualityGrade.WARN
                    else -> QualityGrade.FAIL
                },
                String.format(Locale.US, "peak %.2f of \u00B1%.1f m/s\u00B2 range (%.0f%% margin)", rawPeakAbs, sensorMaxRange, marginRatio * 100f)
            )
        } else {
            factors += QualityFactor("Sensor range margin", QualityGrade.NOT_AVAILABLE, "sensor maximumRange unknown")
        }

        // -- Informational only: a design/configuration fact, not a pass/fail gate --
        factors += QualityFactor(
            "Resolution",
            QualityGrade.INFO,
            String.format(Locale.US, "%.3f Hz/bin", resolutionHz)
        )

        // -- Not yet implemented: reported honestly, excluded from the score --
        factors += QualityFactor("Calibration", QualityGrade.NOT_AVAILABLE, "no calibration subsystem yet (Section 33)")

        if (mountingCheck != null) {
            factors += QualityFactor(
                "Mounting stability",
                when {
                    mountingCheck.ready -> QualityGrade.PASS
                    mountingCheck.movementDuringSetup == "MEDIUM" -> QualityGrade.WARN
                    else -> QualityGrade.FAIL
                },
                "movement during setup: ${mountingCheck.movementDuringSetup}"
            )
            factors += QualityFactor(
                "Orientation stability",
                when {
                    mountingCheck.orientationStabilityDeg <= 2f -> QualityGrade.PASS
                    mountingCheck.orientationStabilityDeg <= 6f -> QualityGrade.WARN
                    else -> QualityGrade.FAIL
                },
                String.format(Locale.US, "\u00B1%.1f\u00B0 (axis %s is vertical)", mountingCheck.orientationStabilityDeg, mountingCheck.dominantAxis)
            )
        } else {
            factors += QualityFactor("Mounting stability", QualityGrade.NOT_AVAILABLE, "mounting not checked this session (Section 17)")
            factors += QualityFactor("Orientation stability", QualityGrade.NOT_AVAILABLE, "mounting not checked this session (Section 17)")
        }

        val scored = factors.filter { it.grade != QualityGrade.NOT_AVAILABLE && it.grade != QualityGrade.INFO }
        val points = scored.map {
            when (it.grade) {
                QualityGrade.PASS -> 100
                QualityGrade.WARN -> 60
                QualityGrade.FAIL -> 20
                QualityGrade.INFO, QualityGrade.NOT_AVAILABLE -> 0
            }
        }.sum()

        val score = if (scored.isNotEmpty()) (points.toDouble() / scored.size).roundToInt() else 0

        return QualityScore(score, factors)
    }
}
