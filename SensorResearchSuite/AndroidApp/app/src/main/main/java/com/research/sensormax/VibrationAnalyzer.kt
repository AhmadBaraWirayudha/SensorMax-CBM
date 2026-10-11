package com.research.sensormax

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import java.util.ArrayDeque

enum class Axis { X, Y, Z }

/**
 * One axis's spectral picture for one analysis window.
 */
data class AxisSpectrum(
    val frequenciesHz: FloatArray,
    val accelAmplitude: FloatArray,
    val velocityAmplitude: FloatArray,
    val displacementAmplitudeUm: FloatArray,
    val envelopeAmplitude: FloatArray,
    val freqResolutionHz: Float,
    val peakFreqHz: Float,
    val peakAccelAmplitude: Float,
    val envelopePeakFreqHz: Float,
    val envelopePeakAmplitude: Float,
    val rmsAccel: Float,
    val rmsVelocityMmS: Float,
    val rmsDisplacementUm: Float,
    // -- Time-domain shape stats (design-debt Section 28), computed on the
    // mean-removed signal for consistency with rmsAccel. Not meaningful for
    // the synthetic RSS/vector combination (see combinedRss), where they
    // are left at 0f.
    val peakToPeak: Float = 0f,
    val crestFactor: Float = 0f,
    val kurtosis: Float = 0f,
    val peakSnrDb: Float = 0f,
    val envelopeUsable: Boolean = true,
    // Largest |raw sample| in this window, pre-mean-removal (design-debt
    // Section 16, "sensor clipping" / "sensor range margin"). Compared
    // against Sensor.maximumRange by whichever Android-aware layer has
    // that value -- this class stays framework-independent.
    val rawPeakAbs: Float = 0f
)

/**
 * One completed analysis window across all three axes.
 */
data class SpectrumResult(
    val sampleRateHz: Double,
    val perAxis: Map<Axis, AxisSpectrum>,
    val dominantAxis: Axis,
    val overallRmsAccel: Float,
    val overallRmsVelocityMmS: Float,
    val overallRmsDisplacementUm: Float,
    val trend: FloatArray,
    // Standard deviation of inter-sample intervals within this window, in ms
    // (design-debt Section 5.3 "timestamp jitter"). Large values mean the
    // OS delivered samples less regularly than the average rate suggests.
    val timestampJitterMs: Float = 0f,
    // Largest single inter-sample interval seen in this window, in ms
    // (design-debt Section 16, "window completeness" -- a stall inside an
    // otherwise-averaged-out window won't show up in the mean rate alone).
    val maxSampleGapMs: Float = 0f,
    val windowDurationSec: Double = 0.0,
    val nyquistHz: Double = 0.0,
    val timestampsMonotonic: Boolean = true
)

/**
 * ISO 20816-3 broadband RMS-velocity zone boundaries.
 *
 * This remains an advisory classification only.
 * A phone MEMS accelerometer is not equivalent to a certified
 * industrial vibration probe.
 */
enum class IsoMachineClass(
    val label: String,
    private val abMmS: Float,
    private val bcMmS: Float,
    private val cdMmS: Float
) {
    NONE(
        "Not classified (raw numbers only)",
        0f,
        0f,
        0f
    ),

    GROUP1_RIGID(
        "Group 1 >300kW - rigid support",
        2.3f,
        4.5f,
        7.1f
    ),

    GROUP1_FLEXIBLE(
        "Group 1 >300kW - flexible support",
        3.5f,
        7.1f,
        11.0f
    ),

    GROUP2_RIGID(
        "Group 2 15-300kW - rigid support",
        1.4f,
        2.8f,
        4.5f
    ),

    GROUP2_FLEXIBLE(
        "Group 2 15-300kW - flexible support",
        2.3f,
        4.5f,
        7.1f
    );

    fun zoneFor(
        overallRmsVelocityMmS: Float
    ): IsoZone {

        if (this == NONE) {
            return IsoZone.UNCLASSIFIED
        }

        return when {
            overallRmsVelocityMmS <= abMmS -> IsoZone.A
            overallRmsVelocityMmS <= bcMmS -> IsoZone.B
            overallRmsVelocityMmS <= cdMmS -> IsoZone.C
            else -> IsoZone.D
        }
    }
}


enum class IsoZone(
    val label: String,
    val advice: String
) {

    A(
        "Zone A",
        "New-machine baseline condition."
    ),

    B(
        "Zone B",
        "Acceptable for unrestricted long-term operation."
    ),

    C(
        "Zone C",
        "Unsatisfactory for continuous running -- schedule repair."
    ),

    D(
        "Zone D",
        "Severe -- investigate and correct without delay."
    ),

    UNCLASSIFIED(
        "Unclassified",
        "Select an ISO 20816-3 machine group for a zone call."
    )
}


/**
 * Rolling-element bearing geometry.
 */
data class BearingGeometry(
    val numElements: Int,
    val ballDiameterMm: Float,
    val pitchDiameterMm: Float,
    val contactAngleDeg: Float = 0f,
    /**
     * Where this geometry came from. Always "User-entered (unverified)"
     * today -- SensorMax has no manufacturer-datasheet lookup yet. Kept as
     * an explicit field (design-debt Section 6.5) so a future bearing
     * library / nameplate-capture feature (Sections 33, 61) has somewhere
     * honest to record "manufacturer datasheet" or "verified" instead of
     * silently implying every geometry is trustworthy.
     */
    val geometrySource: String = "User-entered (unverified)"
)


/**
 * Characteristic bearing frequencies.
 */
data class BearingFrequencies(
    val ftfHz: Float,
    val bpfoHz: Float,
    val bpfiHz: Float,
    val bsfHz: Float
)


enum class BearingMatch {
    NONE,
    BPFO,
    BPFI,
    BSF,
    FTF
}


/**
 * Converts triaxial accelerometer windows into:
 *
 * - RMS acceleration
 * - single-sided FFT
 * - velocity spectrum
 * - displacement spectrum
 * - envelope spectrum
 * - bearing-frequency hints
 * - running-speed/order hints
 *
 * Framework-independent.
 */

/** Refines an FFT-bin peak using parabolic interpolation of log-magnitude. */
private fun refinePeakFrequency(
    freqsHz: FloatArray,
    amplitudes: FloatArray,
    peakIndex: Int
): Float {
    if (
        peakIndex <= 0 ||
        peakIndex >= amplitudes.lastIndex ||
        freqsHz.size != amplitudes.size ||
        freqsHz.size < 3
    ) {
        return if (peakIndex in freqsHz.indices) freqsHz[peakIndex] else 0f
    }

    val y1 = ln(max(amplitudes[peakIndex - 1], 1e-12f).toDouble())
    val y2 = ln(max(amplitudes[peakIndex], 1e-12f).toDouble())
    val y3 = ln(max(amplitudes[peakIndex + 1], 1e-12f).toDouble())
    val denominator = y1 - 2.0 * y2 + y3

    if (!denominator.isFinite() || abs(denominator) < 1e-12) {
        return freqsHz[peakIndex]
    }

    val delta = (
            0.5 * (y1 - y3) / denominator
            ).toFloat().coerceIn(-0.5f, 0.5f)

    val freqRes =
        if (freqsHz.size > 1) freqsHz[1] - freqsHz[0] else 0f

    return (freqsHz[peakIndex] + delta * freqRes).coerceAtLeast(0f)
}

/** Returns the strongest spectral amplitude within a target frequency band. */
private fun amplitudeNearFrequency(
    frequenciesHz: FloatArray,
    amplitudes: FloatArray,
    targetHz: Float,
    halfWidthHz: Float
): Float {
    if (
        frequenciesHz.isEmpty() ||
        amplitudes.isEmpty() ||
        !targetHz.isFinite() ||
        !halfWidthHz.isFinite() ||
        targetHz < 0f ||
        halfWidthHz <= 0f
    ) return 0f

    val count = minOf(frequenciesHz.size, amplitudes.size)
    var best = 0f

    for (i in 0 until count) {
        if (abs(frequenciesHz[i] - targetHz) <= halfWidthHz) {
            val a = amplitudes[i]
            if (a.isFinite() && a > best) best = a
        }
    }

    return best
}

/** Approximate peak-to-local-floor ratio in dB; not an absolute calibrated dB value. */
private fun peakSnrDb(
    frequenciesHz: FloatArray,
    amplitudes: FloatArray,
    peakFreqHz: Float
): Float {
    if (
        frequenciesHz.size < 8 ||
        amplitudes.size < 8 ||
        !peakFreqHz.isFinite() ||
        peakFreqHz <= 0f
    ) return 0f

    val count = minOf(frequenciesHz.size, amplitudes.size)
    var peakIndex = 0
    var bestError = Float.MAX_VALUE

    for (i in 0 until count) {
        val error = abs(frequenciesHz[i] - peakFreqHz)
        if (error < bestError) {
            bestError = error
            peakIndex = i
        }
    }

    val radius = max(3, count / 32)
    val exclusion = max(1, radius / 3)
    val start = max(0, peakIndex - radius)
    val end = min(count - 1, peakIndex + radius)

    var floorSum = 0.0
    var floorCount = 0

    for (i in start..end) {
        if (abs(i - peakIndex) <= exclusion) continue
        val a = amplitudes[i]
        if (a.isFinite() && a > 1e-12f) {
            floorSum += a.toDouble()
            floorCount++
        }
    }

    if (floorCount == 0) return 0f

    val floor = (floorSum / floorCount).toFloat().coerceAtLeast(1e-12f)
    val peak = amplitudes[peakIndex].coerceAtLeast(1e-12f)

    return (
            20.0 * ln((peak / floor).toDouble()) / ln(10.0)
            ).toFloat()
}

class VibrationAnalyzer(
    private val windowSize: Int = 256
) {

    init {
        require(
            FFT.isPowerOfTwo(windowSize)
        ) {
            "windowSize must be a power of two, got $windowSize"
        }
    }


    companion object {

        /*
         * Peak detection ignores the DC / very-low-frequency region.
         *
         * IMPORTANT:
         * This does NOT restrict the displayed FFT.
         * The FFT itself still spans 0 Hz → Nyquist.
         */
        private const val MIN_ANALYSIS_FREQ_HZ = 2f

        /*
         * Low-frequency cutoff before integrating acceleration
         * into velocity/displacement.
         */
        private const val VELOCITY_HP_CUTOFF_HZ = 3f

        /*
         * Number of RMS trend points retained.
         */
        private const val TREND_CAPACITY = 120

        private const val ORDER_TOLERANCE = 0.06f

        private const val BEARING_TOLERANCE = 0.06f

        private const val OVERLAP_FRACTION = 0.50f

        // Envelope detector targets. These are converted to actual digital
        // filter coefficients using the measured sample rate.
        private const val ENVELOPE_HP_CUTOFF_HZ = 10f
        private const val ENVELOPE_LP_CUTOFF_HZ = 8f

        // Practical quality flag only; not a standard or certification limit.
        private const val MIN_ENVELOPE_SAMPLE_RATE_HZ = 80.0

        private const val EPS = 1e-9f


        fun classify(
            isoClass: IsoMachineClass,
            overallRmsVelocityMmS: Float
        ): IsoZone {

            return isoClass.zoneFor(
                overallRmsVelocityMmS
            )
        }


        /**
         * Bin-by-bin vector-magnitude combination of the three per-axis
         * spectra: combined[k] = sqrt(x[k]^2 + y[k]^2 + z[k]^2).
         *
         * This is NOT a physically measured axis -- it is an
         * orientation-independent "how much total vibration is present at
         * this frequency" view (design-debt Section 30, "RSS / VECTOR").
         * It exists because a handheld or magnet-mounted phone's X/Y/Z axes
         * are not guaranteed to line up with the machine's true
         * radial/axial/tangential directions, unlike a fixed industrial
         * probe. Overall RMS fields are taken from the caller's already
         * cross-axis-combined [SpectrumResult] values rather than
         * recomputed here. peakToPeak/crestFactor/kurtosis are time-domain
         * stats that don't have a meaningful equivalent for this synthetic
         * combination, so they are left at 0f.
         */
        fun combinedRss(
            x: AxisSpectrum,
            y: AxisSpectrum,
            z: AxisSpectrum,
            overallRmsAccel: Float,
            overallRmsVelocityMmS: Float,
            overallRmsDisplacementUm: Float
        ): AxisSpectrum {

            val accelAmp = rssArrays(x.accelAmplitude, y.accelAmplitude, z.accelAmplitude)
            val velAmp = rssArrays(x.velocityAmplitude, y.velocityAmplitude, z.velocityAmplitude)
            val dispAmp = rssArrays(x.displacementAmplitudeUm, y.displacementAmplitudeUm, z.displacementAmplitudeUm)
            val envAmp = rssArrays(x.envelopeAmplitude, y.envelopeAmplitude, z.envelopeAmplitude)

            val (peakF, peakA) = peakAbove(x.frequenciesHz, accelAmp, MIN_ANALYSIS_FREQ_HZ)
            val (envPeakF, envPeakA) = peakAbove(x.frequenciesHz, envAmp, MIN_ANALYSIS_FREQ_HZ)

            return AxisSpectrum(
                frequenciesHz = x.frequenciesHz,
                accelAmplitude = accelAmp,
                velocityAmplitude = velAmp,
                displacementAmplitudeUm = dispAmp,
                envelopeAmplitude = envAmp,
                freqResolutionHz = x.freqResolutionHz,
                peakFreqHz = peakF,
                peakAccelAmplitude = peakA,
                envelopePeakFreqHz = envPeakF,
                envelopePeakAmplitude = envPeakA,
                rmsAccel = overallRmsAccel,
                rmsVelocityMmS = overallRmsVelocityMmS,
                rmsDisplacementUm = overallRmsDisplacementUm,
                peakToPeak = 0f,
                crestFactor = 0f,
                kurtosis = 0f,
                rawPeakAbs = maxOf(x.rawPeakAbs, y.rawPeakAbs, z.rawPeakAbs),
                peakSnrDb = peakSnrDb(x.frequenciesHz, accelAmp, peakF),
                envelopeUsable = x.envelopeUsable && y.envelopeUsable && z.envelopeUsable
            )
        }

        private fun rssArrays(a: FloatArray, b: FloatArray, c: FloatArray): FloatArray =
            FloatArray(a.size) { i ->
                sqrt((a[i] * a[i] + b[i] * b[i] + c[i] * c[i]).toDouble()).toFloat()
            }

        /**
         * Largest amplitude at or above [minFreqHz], mirroring the same
         * peak-search floor every other spectrum in this file uses so a
         * quiet DC/low-frequency bin never wins by default.
         */
        fun peakAbove(
            freqsHz: FloatArray,
            amps: FloatArray,
            minFreqHz: Float = MIN_ANALYSIS_FREQ_HZ
        ): Pair<Float, Float> {
            if (freqsHz.isEmpty() || amps.isEmpty()) return 0f to 0f

            val count = minOf(freqsHz.size, amps.size)
            if (count <= 0) return 0f to 0f

            val freqRes =
                if (freqsHz.size > 1) freqsHz[1] - freqsHz[0] else 0f

            val minBin =
                if (freqRes > 0f) {
                    (minFreqHz / freqRes).roundToInt().coerceAtLeast(1)
                } else {
                    1
                }

            val startBin = minOf(minBin, count)
            if (startBin >= count) return 0f to 0f

            var bestIdx = startBin
            var bestAmp = 0f

            for (k in startBin until count) {
                val a = amps[k]
                if (a.isFinite() && a >= 0f && a > bestAmp) {
                    bestAmp = a
                    bestIdx = k
                }
            }

            if (bestAmp <= 0f) return 0f to 0f

            return refinePeakFrequency(freqsHz, amps, bestIdx) to bestAmp
        }

        /**
         * Calculate FTF / BPFO / BPFI / BSF.
         */
        fun bearingFrequencies(
            geometry: BearingGeometry,
            rpmInput: Float
        ): BearingFrequencies? {

            if (
                geometry.numElements <= 0 ||
                !geometry.ballDiameterMm.isFinite() ||
                !geometry.pitchDiameterMm.isFinite() ||
                geometry.ballDiameterMm <= 0f ||
                geometry.pitchDiameterMm <= 0f ||
                !geometry.contactAngleDeg.isFinite() ||
                geometry.contactAngleDeg < 0f ||
                geometry.contactAngleDeg > 90f ||
                !rpmInput.isFinite() ||
                rpmInput <= 0f
            ) {
                return null
            }

            val ratio =
                geometry.ballDiameterMm /
                        geometry.pitchDiameterMm

            if (!ratio.isFinite() || ratio <= 0f || ratio >= 1f) {
                return null
            }

            val fr = rpmInput / 60f

            val cosPhi =
                cos(
                    geometry.contactAngleDeg *
                            (PI.toFloat() / 180f)
                )

            val n =
                geometry.numElements.toFloat()


            val ftf =
                0.5f *
                        fr *
                        (1f - ratio * cosPhi)


            val bpfo =
                (n / 2f) *
                        fr *
                        (1f - ratio * cosPhi)


            val bpfi =
                (n / 2f) *
                        fr *
                        (1f + ratio * cosPhi)


            val bsf =
                (
                        geometry.pitchDiameterMm /
                                (2f * geometry.ballDiameterMm)
                        ) *
                        fr *
                        (
                                1f -
                                        (ratio * cosPhi) *
                                        (ratio * cosPhi)
                                )

            return BearingFrequencies(
                ftfHz = ftf,
                bpfoHz = bpfo,
                bpfiHz = bpfi,
                bsfHz = bsf
            )
        }


        private data class BearingCandidate(
            val match: BearingMatch,
            val relativeError: Float
        )

        private fun matchBearingFrequency(
            envelopePeakFreqHz: Float,
            freqResolutionHz: Float,
            freqs: BearingFrequencies
        ): BearingMatch {
            if (
                envelopePeakFreqHz <= 0f ||
                !envelopePeakFreqHz.isFinite() ||
                freqResolutionHz <= 0f ||
                !freqResolutionHz.isFinite()
            ) {
                return BearingMatch.NONE
            }

            val candidates = mutableListOf<BearingCandidate>()

            fun evaluate(match: BearingMatch, baseHz: Float) {
                if (!baseHz.isFinite() || baseHz <= 0f) return

                for (harmonic in 1..3) {
                    val target = baseHz * harmonic
                    val tolerance = max(
                        target * BEARING_TOLERANCE,
                        freqResolutionHz
                    )
                    val error = abs(envelopePeakFreqHz - target)

                    if (error <= tolerance) {
                        candidates += BearingCandidate(
                            match = match,
                            relativeError = error / max(target, EPS)
                        )
                    }
                }
            }

            evaluate(BearingMatch.BPFO, freqs.bpfoHz)
            evaluate(BearingMatch.BPFI, freqs.bpfiHz)
            evaluate(BearingMatch.BSF, freqs.bsfHz)
            evaluate(BearingMatch.FTF, freqs.ftfHz)

            return candidates
                .minByOrNull { it.relativeError }
                ?.match
                ?: BearingMatch.NONE
        }

        fun bearingMatchCode(
            envelopePeakFreqHz: Float,
            freqResolutionHz: Float,
            freqs: BearingFrequencies
        ): String {

            return matchBearingFrequency(
                envelopePeakFreqHz,
                freqResolutionHz,
                freqs
            ).name
        }


        fun bearingHint(
            envelopePeakFreqHz: Float,
            freqResolutionHz: Float,
            freqs: BearingFrequencies
        ): String {

            if (
                envelopePeakFreqHz <= 0f ||
                freqResolutionHz <= 0f
            ) {

                return "Insufficient signal for a bearing hint yet."
            }


            return when (
                matchBearingFrequency(
                    envelopePeakFreqHz,
                    freqResolutionHz,
                    freqs
                )
            ) {

                BearingMatch.BPFO ->
                    "Envelope peak matches BPFO -- pattern consistent with an outer-race defect."

                BearingMatch.BPFI ->
                    "Envelope peak matches BPFI -- pattern consistent with an inner-race defect."

                BearingMatch.BSF ->
                    "Envelope peak matches BSF (or 2x BSF, often the stronger line) -- pattern consistent with a rolling-element defect."

                BearingMatch.FTF ->
                    "Envelope peak matches FTF (cage speed) -- pattern consistent with cage wear or looseness."

                BearingMatch.NONE ->
                    "No envelope peak near a computed bearing frequency right now."
            }
        }


        fun faultHint(
            spectrum: AxisSpectrum,
            rpmInput: Float
        ): String {

            if (rpmInput <= 0f) {
                return "Enter RPM to unlock 1X/2X/3X order-based hints."
            }

            if (
                spectrum.freqResolutionHz <= 0f ||
                spectrum.peakFreqHz <= 0f
            ) {

                return "Insufficient signal for an order-based hint yet."
            }


            val oneX =
                rpmInput / 60f

            if (oneX <= 0f) {
                return "Insufficient signal for an order-based hint yet."
            }


            fun ampNear(
                order: Int
            ): Float {

                val target = oneX * order

                if (spectrum.accelAmplitude.isEmpty()) {
                    return 0f
                }

                return amplitudeNearFrequency(
                    spectrum.frequenciesHz,
                    spectrum.accelAmplitude,
                    target,
                    max(
                        spectrum.freqResolutionHz,
                        target * ORDER_TOLERANCE
                    )
                )
            }


            val a1 =
                ampNear(1)

            val a2 =
                ampNear(2)

            val a3 =
                ampNear(3)


            val ref =
                if (
                    spectrum.peakAccelAmplitude >
                    1e-6f
                ) {
                    spectrum.peakAccelAmplitude
                } else {
                    1e-6f
                }


            var elevatedCount =
                0

            if (a1 >= ref * 0.4f) {
                elevatedCount++
            }

            if (a2 >= ref * 0.4f) {
                elevatedCount++
            }

            if (a3 >= ref * 0.4f) {
                elevatedCount++
            }


            val order =
                spectrum.peakFreqHz /
                        oneX


            fun near(
                target: Int
            ): Boolean {

                return abs(
                    order - target
                ) <= target *
                        ORDER_TOLERANCE
            }


            return when {

                elevatedCount >= 3 ->
                    "Multiple running-speed harmonics elevated -- pattern often seen with mechanical looseness or a soft foot."

                near(1) ->
                    "Dominant peak near 1X running speed -- pattern often seen with unbalance (also check bent shaft / soft foot)."

                near(2) ->
                    "Dominant peak near 2X running speed -- pattern often seen with misalignment (also check bent shaft / looseness)."

                near(3) ->
                    "Dominant peak near 3X running speed -- often paired with misalignment or coupling issues."

                else ->
                    "Dominant peak is non-synchronous with running speed -- check the envelope spectrum against bearing/gear-mesh frequencies."
            }
        }
    }


    // =========================================================
    // WINDOW / BUFFERS
    // =========================================================

    private val window =
        FFT.hannWindow(windowSize)

    private val windowSum =
        window.sum().let {
            if (it > 0f) it else 1f
        }


    private val bufX =
        FloatArray(windowSize)

    private val bufY =
        FloatArray(windowSize)

    private val bufZ =
        FloatArray(windowSize)

    private val bufT =
        LongArray(windowSize)

    /** 50% overlap between consecutive FFT windows. */
    private val hopSize =
        max(1, (windowSize * OVERLAP_FRACTION).roundToInt())

    private var count =
        0


    private val trend =
        ArrayDeque<Float>(TREND_CAPACITY)


    fun reset() {

        count = 0

        trend.clear()

        for (i in 0 until windowSize) {
            bufX[i] = 0f
            bufY[i] = 0f
            bufZ[i] = 0f
            bufT[i] = 0L
        }
    }


    /**
     * Add one triaxial sample.
     *
     * [timestampNs] is the Android SensorEvent monotonic timestamp in
     * nanoseconds. Keeping nanosecond precision here avoids quantizing
     * inter-sample timing before sample-rate/jitter estimation.
     *
     * A SpectrumResult is returned after every complete window.
     */
    fun addSample(
        timestampNs: Long,
        x: Float,
        y: Float,
        z: Float
    ): SpectrumResult? {

        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) {
            return null
        }

        if (count > 0 && timestampNs <= bufT[count - 1]) {
            // Preserve timestamp monotonicity for sample-rate/jitter analysis.
            return null
        }

        bufX[count] = x
        bufY[count] = y
        bufZ[count] = z
        bufT[count] = timestampNs
        count++

        if (count < windowSize) {
            return null
        }

        val result = analyzeCurrentWindow()

        if (hopSize < windowSize) {
            System.arraycopy(bufX, hopSize, bufX, 0, windowSize - hopSize)
            System.arraycopy(bufY, hopSize, bufY, 0, windowSize - hopSize)
            System.arraycopy(bufZ, hopSize, bufZ, 0, windowSize - hopSize)
            System.arraycopy(bufT, hopSize, bufT, 0, windowSize - hopSize)
            count = windowSize - hopSize
        } else {
            count = 0
        }

        return result
    }

    private fun analyzeCurrentWindow(): SpectrumResult? {

        val elapsedNs =
            bufT[windowSize - 1] - bufT[0]

        if (elapsedNs <= 0L) {
            return null
        }

        val fs =
            (windowSize - 1) / (elapsedNs / 1_000_000_000.0)

        if (!fs.isFinite() || fs <= 0.0) {
            return null
        }

        val meanDtNs =
            elapsedNs.toDouble() / (windowSize - 1)

        var jitterSumSq = 0.0
        var maxGapMs = 0.0
        var timestampsMonotonic = true

        for (i in 1 until windowSize) {
            val dtNs = (bufT[i] - bufT[i - 1]).toDouble()
            if (dtNs <= 0.0) timestampsMonotonic = false

            val diffNs = dtNs - meanDtNs
            jitterSumSq += diffNs * diffNs
            val dtMs = dtNs / 1_000_000.0
            if (dtMs > maxGapMs) maxGapMs = dtMs
        }

        val timestampJitterMs =
            (sqrt(jitterSumSq / (windowSize - 1)) / 1_000_000.0).toFloat()

        val specX = spectrumFor(bufX, fs)
        val specY = spectrumFor(bufY, fs)
        val specZ = spectrumFor(bufZ, fs)

        val perAxis = linkedMapOf(
            Axis.X to specX,
            Axis.Y to specY,
            Axis.Z to specZ
        )

        var dominantAxis = Axis.X
        var bestRms = specX.rmsAccel

        if (specY.rmsAccel > bestRms) {
            dominantAxis = Axis.Y
            bestRms = specY.rmsAccel
        }

        if (specZ.rmsAccel > bestRms) {
            dominantAxis = Axis.Z
        }

        val overallRmsAccel = sqrt(
            (
                    specX.rmsAccel * specX.rmsAccel +
                            specY.rmsAccel * specY.rmsAccel +
                            specZ.rmsAccel * specZ.rmsAccel
                    ).toDouble()
        ).toFloat()

        val overallRmsVelocity = sqrt(
            (
                    specX.rmsVelocityMmS * specX.rmsVelocityMmS +
                            specY.rmsVelocityMmS * specY.rmsVelocityMmS +
                            specZ.rmsVelocityMmS * specZ.rmsVelocityMmS
                    ).toDouble()
        ).toFloat()

        val overallRmsDisplacement = sqrt(
            (
                    specX.rmsDisplacementUm * specX.rmsDisplacementUm +
                            specY.rmsDisplacementUm * specY.rmsDisplacementUm +
                            specZ.rmsDisplacementUm * specZ.rmsDisplacementUm
                    ).toDouble()
        ).toFloat()

        trend.addLast(overallRmsAccel)
        while (trend.size > TREND_CAPACITY) trend.removeFirst()

        return SpectrumResult(
            sampleRateHz = fs,
            perAxis = perAxis,
            dominantAxis = dominantAxis,
            overallRmsAccel = overallRmsAccel,
            overallRmsVelocityMmS = overallRmsVelocity,
            overallRmsDisplacementUm = overallRmsDisplacement,
            trend = trend.toList().toFloatArray(),
            timestampJitterMs = timestampJitterMs,
            maxSampleGapMs = maxGapMs.toFloat(),
            windowDurationSec = elapsedNs / 1_000_000_000.0,
            nyquistHz = fs / 2.0,
            timestampsMonotonic = timestampsMonotonic
        )
    }

    /**
     * Generates the full single-sided spectrum:
     *
     * 0 Hz
     * ...
     * Fs/2
     *
     * For an even N this includes the Nyquist bin.
     */
    private fun spectrumFor(
        buf: FloatArray,
        fs: Double
    ): AxisSpectrum {

        val re = FloatArray(windowSize)
        val im = FloatArray(windowSize)

        var mean = 0.0
        var rawPeakAbs = 0f

        for (v in buf) {
            mean += v.toDouble()
            rawPeakAbs = max(rawPeakAbs, abs(v))
        }

        mean /= windowSize

        for (i in 0 until windowSize) {
            re[i] = (buf[i] - mean.toFloat()) * window[i]
            im[i] = 0f
        }

        FFT.transform(re, im)

        val half = windowSize / 2 + 1
        val freqRes = (fs / windowSize).toFloat()
        val freqs = FloatArray(half)
        val accelAmp = FloatArray(half)
        val velAmp = FloatArray(half)
        val dispAmp = FloatArray(half)

        val minBin =
            if (freqRes > 0f) {
                (MIN_ANALYSIS_FREQ_HZ / freqRes).roundToInt().coerceAtLeast(1)
            } else {
                1
            }

        var peakIdx = 0
        var peakAmp = 0f
        var velRmsAccum = 0.0
        var dispRmsAccum = 0.0

        for (k in 0 until half) {
            val mag = sqrt(
                (re[k] * re[k] + im[k] * im[k]).toDouble()
            ).toFloat()

            val isDc = k == 0
            val isNyquist = k == windowSize / 2
            val scale = if (isDc || isNyquist) 1f / windowSum else 2f / windowSum
            val amp = mag * scale
            val fHz = k * freqRes

            freqs[k] = fHz
            accelAmp[k] = amp

            if (fHz >= VELOCITY_HP_CUTOFF_HZ && fHz > 0f) {
                val omega = 2f * PI.toFloat() * fHz
                if (omega > EPS) {
                    val velocity = (amp / omega) * 1000f
                    val displacement = (amp / (omega * omega)) * 1_000_000f

                    velAmp[k] = velocity
                    dispAmp[k] = displacement

                    velRmsAccum += velocity.toDouble() * velocity.toDouble() / 2.0
                    dispRmsAccum += displacement.toDouble() * displacement.toDouble() / 2.0
                }
            }

            if (k >= minBin && amp.isFinite() && amp > peakAmp) {
                peakAmp = amp
                peakIdx = k
            }
        }

        val td = timeDomainStats(buf)
        val envResult = envelopeAmplitudeSpectrum(buf, fs)

        // Suppress low-rate envelope output so the existing bearing-hint UI
        // cannot interpret an under-sampled envelope as a bearing diagnostic.
        val envAmp =
            if (envResult.second) {
                envResult.first
            } else {
                FloatArray(envResult.first.size)
            }

        var envPeakIdx = 0
        var envPeakAmp = 0f
        val envStart = minOf(minBin, envAmp.size)

        for (k in envStart until envAmp.size) {
            val value = envAmp[k]
            if (value.isFinite() && value > envPeakAmp) {
                envPeakAmp = value
                envPeakIdx = k
            }
        }

        val peakFreq =
            if (peakAmp > 0f) refinePeakFrequency(freqs, accelAmp, peakIdx) else 0f

        val envelopePeakFreq =
            if (envPeakAmp > 0f) refinePeakFrequency(freqs, envAmp, envPeakIdx) else 0f

        return AxisSpectrum(
            frequenciesHz = freqs,
            accelAmplitude = accelAmp,
            velocityAmplitude = velAmp,
            displacementAmplitudeUm = dispAmp,
            envelopeAmplitude = envAmp,
            freqResolutionHz = freqRes,
            peakFreqHz = peakFreq,
            peakAccelAmplitude = peakAmp,
            envelopePeakFreqHz = envelopePeakFreq,
            envelopePeakAmplitude = envPeakAmp,
            rmsAccel = td.rms,
            rmsVelocityMmS = sqrt(velRmsAccum).toFloat(),
            rmsDisplacementUm = sqrt(dispRmsAccum).toFloat(),
            peakToPeak = td.peakToPeak,
            crestFactor = td.crestFactor,
            kurtosis = td.kurtosis,
            rawPeakAbs = rawPeakAbs,
            peakSnrDb = peakSnrDb(freqs, accelAmp, peakFreq),
            envelopeUsable = envResult.second
        )
    }

    /**
     * Simplified amplitude-demodulation envelope spectrum.
     *
     * Full output range:
     * 0 Hz → Nyquist.
     *
     * The envelope amplitude is relative / arbitrary units.
     */
    private fun envelopeAmplitudeSpectrum(
        buf: FloatArray,
        fs: Double
    ): Pair<FloatArray, Boolean> {

        val n = buf.size
        if (n == 0 || fs <= 0.0 || !fs.isFinite()) {
            return FloatArray(0) to false
        }

        val nyquist = fs / 2.0
        val usable = fs >= MIN_ENVELOPE_SAMPLE_RATE_HZ

        val hpCutoff = min(ENVELOPE_HP_CUTOFF_HZ.toDouble(), nyquist * 0.70).toFloat()
        val lpCutoff = min(ENVELOPE_LP_CUTOFF_HZ.toDouble(), nyquist * 0.30).toFloat()

        if (hpCutoff <= 0f || lpCutoff <= 0f) {
            return FloatArray(n / 2 + 1) to false
        }

        val dtSec = 1.0 / fs
        val hpRc = 1.0 / (2.0 * PI * hpCutoff.toDouble())
        val hpAlpha = (hpRc / (hpRc + dtSec)).toFloat()
        val lpAlpha = (1.0 - exp(-2.0 * PI * lpCutoff.toDouble() * dtSec)).toFloat()

        var mean = 0.0
        for (v in buf) mean += v.toDouble()
        mean /= n

        val hp = FloatArray(n)
        var hpPrev = 0f
        var xPrev = buf[0] - mean.toFloat()

        for (i in 0 until n) {
            val x = buf[i] - mean.toFloat()
            val y = hpAlpha * (hpPrev + x - xPrev)
            hp[i] = y
            hpPrev = y
            xPrev = x
        }

        val env = FloatArray(n)
        var lp = 0f

        for (i in 0 until n) {
            val rectified = abs(hp[i])
            lp += lpAlpha * (rectified - lp)
            env[i] = lp
        }

        var envMean = 0.0
        for (v in env) envMean += v.toDouble()
        envMean /= n

        val re = FloatArray(n)
        val im = FloatArray(n)

        for (i in 0 until n) {
            re[i] = (env[i] - envMean.toFloat()) * window[i]
            im[i] = 0f
        }

        FFT.transform(re, im)

        val half = n / 2 + 1
        val amp = FloatArray(half)

        for (k in 0 until half) {
            val mag = sqrt(
                (re[k] * re[k] + im[k] * im[k]).toDouble()
            ).toFloat()

            val isDc = k == 0
            val isNyquist = k == n / 2
            val scale = if (isDc || isNyquist) 1f / windowSum else 2f / windowSum
            amp[k] = mag * scale
        }

        return amp to usable
    }

    /** RMS + shape stats, all from one pass over the mean-removed (dynamic-only) signal. */
    private data class TimeDomainStats(
        val rms: Float,
        val peakToPeak: Float,
        val crestFactor: Float,
        val kurtosis: Float,
        val rawPeakAbs: Float
    )

    /**
     * Dynamic acceleration RMS plus peak-to-peak, crest factor, and
     * kurtosis (design-debt Section 28).
     *
     * Removes the mean first, so:
     *
     * raw accelerometer:
     *     vibration + gravity + DC bias
     *
     * becomes:
     *     vibration only
     *
     * [TimeDomainStats.rms] is the value used for the RMS trend (same
     * definition the old standalone centeredRms() used).
     *
     * Kurtosis uses the standard (non-excess) definition -- a Gaussian /
     * random-noise signal reads ~3.0; higher values are commonly
     * associated with impacting (e.g. a damaged bearing), though on its
     * own this is a shape statistic, not a diagnosis.
     */
    private fun timeDomainStats(
        buf: FloatArray
    ): TimeDomainStats {

        if (buf.isEmpty()) {
            return TimeDomainStats(0f, 0f, 0f, 0f, 0f)
        }

        var mean = 0.0
        for (v in buf) {
            mean += v.toDouble()
        }
        mean /= buf.size

        var sumSq = 0.0
        var sum4 = 0.0
        var minC = Float.MAX_VALUE
        var maxC = -Float.MAX_VALUE
        var peakAbs = 0f
        var rawPeakAbs = 0f

        for (v in buf) {
            val centered = v.toDouble() - mean
            sumSq += centered * centered
            sum4 += centered * centered * centered * centered

            val cf = centered.toFloat()
            if (cf < minC) minC = cf
            if (cf > maxC) maxC = cf

            val a = abs(cf)
            if (a > peakAbs) peakAbs = a

            // Raw (not mean-removed) -- this is what actually hits the
            // sensor's hardware range limit, gravity offset included.
            val rawAbs = abs(v)
            if (rawAbs > rawPeakAbs) rawPeakAbs = rawAbs
        }

        val variance = sumSq / buf.size
        val rms = sqrt(variance).toFloat()
        val peakToPeak = maxC - minC
        val crestFactor = if (rms > 1e-9f) peakAbs / rms else 0f
        val kurtosis = if (variance > 1e-12) ((sum4 / buf.size) / (variance * variance)).toFloat() else 0f

        return TimeDomainStats(
            rms = rms,
            peakToPeak = peakToPeak,
            crestFactor = crestFactor,
            kurtosis = kurtosis,
            rawPeakAbs = rawPeakAbs
        )
    }
}
