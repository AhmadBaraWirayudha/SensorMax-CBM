package com.research.sensormax

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

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
    val maxSampleGapMs: Float = 0f
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
                rawPeakAbs = 0f
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
        fun peakAbove(freqsHz: FloatArray, amps: FloatArray, minFreqHz: Float = MIN_ANALYSIS_FREQ_HZ): Pair<Float, Float> {
            if (freqsHz.isEmpty() || amps.isEmpty()) return 0f to 0f

            val freqRes = if (freqsHz.size > 1) freqsHz[1] - freqsHz[0] else 0f
            val minBin = if (freqRes > 0f) (minFreqHz / freqRes).roundToInt().coerceAtLeast(1) else 1
            val startBin = minOf(minBin, amps.size)

            var idx = 0
            var best = 0f
            for (k in startBin until amps.size) {
                if (amps[k] > best) {
                    best = amps[k]
                    idx = k
                }
            }
            val freq = if (idx < freqsHz.size) freqsHz[idx] else 0f
            return freq to best
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
                geometry.ballDiameterMm <= 0f ||
                geometry.pitchDiameterMm <= 0f
            ) {
                return null
            }

            val fr =
                rpmInput / 60f

            if (fr <= 0f) {
                return null
            }

            val ratio =
                geometry.ballDiameterMm /
                        geometry.pitchDiameterMm

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


        private fun matchBearingFrequency(
            envelopePeakFreqHz: Float,
            freqResolutionHz: Float,
            freqs: BearingFrequencies
        ): BearingMatch {

            if (
                envelopePeakFreqHz <= 0f ||
                freqResolutionHz <= 0f
            ) {
                return BearingMatch.NONE
            }

            fun near(
                target: Float
            ): Boolean {

                if (target <= 0f) {
                    return false
                }

                val band =
                    (
                            target *
                                    BEARING_TOLERANCE
                            ).coerceAtLeast(
                            freqResolutionHz
                        )

                return abs(
                    envelopePeakFreqHz -
                            target
                ) <= band
            }


            return when {

                near(freqs.bpfoHz) ->
                    BearingMatch.BPFO

                near(freqs.bpfiHz) ->
                    BearingMatch.BPFI

                near(freqs.bsfHz) ||
                        near(freqs.bsfHz * 2f) ->
                    BearingMatch.BSF

                near(freqs.ftfHz) ->
                    BearingMatch.FTF

                else ->
                    BearingMatch.NONE
            }
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

                val target =
                    oneX * order

                if (
                    spectrum.accelAmplitude.isEmpty()
                ) {
                    return 0f
                }

                var idx =
                    (
                            target /
                                    spectrum.freqResolutionHz
                            ).roundToInt()

                idx =
                    idx.coerceIn(
                        0,
                        spectrum.accelAmplitude.size - 1
                    )

                return spectrum.accelAmplitude[idx]
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


    private var count =
        0


    private val trend =
        mutableListOf<Float>()


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
     * timestampMs is based on SensorEvent.timestamp converted
     * to milliseconds by SensorEngine.
     *
     * A SpectrumResult is returned after every complete window.
     */
    fun addSample(
        timestampMs: Long,
        x: Float,
        y: Float,
        z: Float
    ): SpectrumResult? {

        if (count >= windowSize) {
            count = 0
        }


        bufX[count] =
            x

        bufY[count] =
            y

        bufZ[count] =
            z

        bufT[count] =
            timestampMs


        count++


        if (
            count <
            windowSize
        ) {
            return null
        }


        /*
         * SensorEvent timestamps are monotonic.
         * Calculate actual delivered sample rate.
         */
        val elapsedMs =
            bufT[windowSize - 1] -
                    bufT[0]


        if (elapsedMs <= 0L) {
            count = 0
            return null
        }


        val fs =
            (windowSize - 1) /
                    (elapsedMs / 1000.0)


        if (
            !fs.isFinite() ||
            fs <= 0.0
        ) {
            count = 0
            return null
        }


        /*
         * Timestamp jitter: standard deviation of the inter-sample
         * intervals actually seen in this window, vs. the mean interval
         * implied by fs. A steady sensor reads close to 0 ms; a busy CPU
         * or a throttled/backgrounded app shows up here before it shows
         * up as a wrong-looking spectrum.
         */
        val meanDtMs =
            elapsedMs.toDouble() / (windowSize - 1)

        var jitterSumSq = 0.0
        var maxGapMs = 0.0

        for (i in 1 until windowSize) {
            val dt = (bufT[i] - bufT[i - 1]).toDouble()
            val diff = dt - meanDtMs
            jitterSumSq += diff * diff
            if (dt > maxGapMs) maxGapMs = dt
        }

        val timestampJitterMs =
            sqrt(jitterSumSq / (windowSize - 1)).toFloat()


        val specX =
            spectrumFor(
                bufX,
                fs
            )

        val specY =
            spectrumFor(
                bufY,
                fs
            )

        val specZ =
            spectrumFor(
                bufZ,
                fs
            )


        val perAxis =
            linkedMapOf(
                Axis.X to specX,
                Axis.Y to specY,
                Axis.Z to specZ
            )


        // -----------------------------------------------------
        // Dominant axis
        // -----------------------------------------------------

        var dominantAxis =
            Axis.X

        var bestRms =
            specX.rmsAccel


        if (
            specY.rmsAccel >
            bestRms
        ) {

            dominantAxis =
                Axis.Y

            bestRms =
                specY.rmsAccel
        }


        if (
            specZ.rmsAccel >
            bestRms
        ) {

            dominantAxis =
                Axis.Z

            bestRms =
                specZ.rmsAccel
        }


        // -----------------------------------------------------
        // Overall RMS acceleration
        // -----------------------------------------------------

        val overallRmsAccel =
            sqrt(
                (
                        specX.rmsAccel *
                                specX.rmsAccel +

                                specY.rmsAccel *
                                specY.rmsAccel +

                                specZ.rmsAccel *
                                specZ.rmsAccel
                        ).toDouble()
            ).toFloat()


        // -----------------------------------------------------
        // Overall RMS velocity
        // -----------------------------------------------------

        val overallRmsVelocity =
            sqrt(
                (
                        specX.rmsVelocityMmS *
                                specX.rmsVelocityMmS +

                                specY.rmsVelocityMmS *
                                specY.rmsVelocityMmS +

                                specZ.rmsVelocityMmS *
                                specZ.rmsVelocityMmS
                        ).toDouble()
            ).toFloat()


        // -----------------------------------------------------
        // Overall RMS displacement
        // -----------------------------------------------------

        val overallRmsDisplacement =
            sqrt(
                (
                        specX.rmsDisplacementUm *
                                specX.rmsDisplacementUm +

                                specY.rmsDisplacementUm *
                                specY.rmsDisplacementUm +

                                specZ.rmsDisplacementUm *
                                specZ.rmsDisplacementUm
                        ).toDouble()
            ).toFloat()


        // -----------------------------------------------------
        // RMS trend
        // -----------------------------------------------------

        trend.add(
            overallRmsAccel
        )

        while (
            trend.size >
            TREND_CAPACITY
        ) {

            trend.removeAt(0)
        }


        val result =
            SpectrumResult(
                sampleRateHz =
                    fs,

                perAxis =
                    perAxis,

                dominantAxis =
                    dominantAxis,

                overallRmsAccel =
                    overallRmsAccel,

                overallRmsVelocityMmS =
                    overallRmsVelocity,

                overallRmsDisplacementUm =
                    overallRmsDisplacement,

                trend =
                    trend.toFloatArray(),

                timestampJitterMs =
                    timestampJitterMs,

                maxSampleGapMs =
                    maxGapMs.toFloat()
            )


        /*
         * Reuse the arrays for the next window.
         */
        count = 0


        return result
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

        val re =
            FloatArray(windowSize)

        val im =
            FloatArray(windowSize)


        // -----------------------------------------------------
        // Remove DC / gravity / static offset
        // -----------------------------------------------------

        var mean =
            0.0

        for (v in buf) {
            mean += v.toDouble()
        }

        mean /=
            windowSize


        // -----------------------------------------------------
        // Apply Hann window
        // -----------------------------------------------------

        for (i in 0 until windowSize) {

            re[i] =
                (
                        buf[i] -
                                mean.toFloat()
                        ) *
                        window[i]

            im[i] =
                0f
        }


        // -----------------------------------------------------
        // FFT
        // -----------------------------------------------------

        FFT.transform(
            re,
            im
        )


        /*
         * FULL single-sided spectrum.
         *
         * For N=256:
         * bins = 0 ... 128
         *
         * Nyquist = Fs / 2
         */
        val half =
            windowSize / 2 + 1


        val freqRes =
            (
                    fs /
                            windowSize
                    ).toFloat()


        val freqs =
            FloatArray(half)

        val accelAmp =
            FloatArray(half)

        val velAmp =
            FloatArray(half)

        val dispAmp =
            FloatArray(half)


        // -----------------------------------------------------
        // Peak search starts at 2 Hz.
        // Display still begins at 0 Hz.
        // -----------------------------------------------------

        val minBin =
            if (freqRes > 0f) {

                val b =
                    (
                            MIN_ANALYSIS_FREQ_HZ /
                                    freqRes
                            ).roundToInt()

                b.coerceAtLeast(1)

            } else {

                1
            }


        var peakIdx =
            0

        var peakAmp =
            0f


        var velRmsAccum =
            0.0

        var dispRmsAccum =
            0.0


        // -----------------------------------------------------
        // Build the complete spectrum
        // -----------------------------------------------------

        for (k in 0 until half) {

            val mag =
                sqrt(
                    (
                            re[k] *
                                    re[k] +

                                    im[k] *
                                    im[k]
                            ).toDouble()
                ).toFloat()


            /*
             * Single-sided amplitude scaling:
             *
             * DC       → 1 / windowSum
             * Nyquist  → 1 / windowSum
             * Others   → 2 / windowSum
             */
            val isDc =
                k == 0

            val isNyquist =
                k == windowSize / 2


            val scale =
                if (
                    isDc ||
                    isNyquist
                ) {

                    1f /
                            windowSum

                } else {

                    2f /
                            windowSum
                }


            val amp =
                mag *
                        scale


            accelAmp[k] =
                amp


            val fHz =
                k *
                        freqRes


            freqs[k] =
                fHz


            // -------------------------------------------------
            // Acceleration → velocity
            //
            // Skip low-frequency region because integration
            // strongly magnifies low-frequency noise.
            // -------------------------------------------------

            if (
                freqRes > 0f &&
                fHz >= VELOCITY_HP_CUTOFF_HZ
            ) {

                val omega =
                    2f *
                            PI.toFloat() *
                            fHz


                val velocity =
                    (
                            amp /
                                    omega
                            ) *
                            1000f


                velAmp[k] =
                    velocity


                /*
                 * RMS of a sinusoidal spectral component:
                 * amplitude / sqrt(2)
                 */
                velRmsAccum +=
                    (
                            velocity.toDouble() *
                                    velocity.toDouble()
                            ) / 2.0


                // ---------------------------------------------
                // Acceleration → displacement
                // ---------------------------------------------

                val displacement =
                    (
                            amp /
                                    (
                                            omega *
                                                    omega
                                            )
                            ) *
                            1_000_000f


                dispAmp[k] =
                    displacement


                dispRmsAccum +=
                    (
                            displacement.toDouble() *
                                    displacement.toDouble()
                            ) / 2.0
            }


            // -------------------------------------------------
            // Peak detection
            // -------------------------------------------------

            if (
                k >= minBin &&
                amp > peakAmp
            ) {

                peakAmp =
                    amp

                peakIdx =
                    k
            }
        }


        // -----------------------------------------------------
        // Time-domain shape stats (RMS, peak-to-peak, crest factor,
        // kurtosis) -- all computed on the mean-removed signal so a
        // gravity-orientation offset doesn't distort them.
        // -----------------------------------------------------

        val td = timeDomainStats(buf)

        // -----------------------------------------------------
        // Envelope spectrum
        // -----------------------------------------------------

        val envAmp =
            envelopeAmplitudeSpectrum(
                buf,
                fs
            )


        var envPeakIdx =
            0

        var envPeakAmp =
            0f


        for (
        k in minBin until envAmp.size
        ) {

            if (
                envAmp[k] >
                envPeakAmp
            ) {

                envPeakAmp =
                    envAmp[k]

                envPeakIdx =
                    k
            }
        }


        // -----------------------------------------------------
        // Final result
        // -----------------------------------------------------

        return AxisSpectrum(

            frequenciesHz =
                freqs,

            accelAmplitude =
                accelAmp,

            velocityAmplitude =
                velAmp,

            displacementAmplitudeUm =
                dispAmp,

            envelopeAmplitude =
                envAmp,

            freqResolutionHz =
                freqRes,

            peakFreqHz =
                if (freqs.isNotEmpty())
                    freqs[peakIdx]
                else
                    0f,

            peakAccelAmplitude =
                peakAmp,

            envelopePeakFreqHz =
                if (
                    freqs.isNotEmpty() &&
                    envAmp.isNotEmpty()
                ) {

                    freqs[
                        envPeakIdx.coerceIn(
                            0,
                            freqs.lastIndex
                        )
                    ]

                } else {

                    0f
                },

            envelopePeakAmplitude =
                envPeakAmp,

            rmsAccel =
                td.rms,

            rmsVelocityMmS =
                sqrt(
                    velRmsAccum
                ).toFloat(),

            rmsDisplacementUm =
                sqrt(
                    dispRmsAccum
                ).toFloat(),

            peakToPeak =
                td.peakToPeak,

            crestFactor =
                td.crestFactor,

            kurtosis =
                td.kurtosis,

            rawPeakAbs =
                td.rawPeakAbs
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
    ): FloatArray {

        val n =
            buf.size

        if (n == 0) {
            return FloatArray(0)
        }


        /*
         * These values form a lightweight:
         *
         * raw signal
         *     ↓
         * high-pass
         *     ↓
         * rectification
         *     ↓
         * low-pass envelope
         */
        val hpAlpha =
            0.98f

        val lpAlpha =
            0.2f


        // -----------------------------------------------------
        // High-pass stage
        // -----------------------------------------------------

        val hp =
            FloatArray(n)

        var hpPrev =
            0f

        var xPrev =
            buf[0]


        for (i in 0 until n) {

            val x =
                buf[i]

            val y =
                hpAlpha *
                        (
                                hpPrev +
                                        x -
                                        xPrev
                                )

            hp[i] =
                y

            hpPrev =
                y

            xPrev =
                x
        }


        // -----------------------------------------------------
        // Rectification + low-pass envelope
        // -----------------------------------------------------

        val env =
            FloatArray(n)

        var lp =
            0f


        for (i in 0 until n) {

            val rect =
                abs(
                    hp[i]
                )

            lp +=
                lpAlpha *
                        (
                                rect -
                                        lp
                                )

            env[i] =
                lp
        }


        // -----------------------------------------------------
        // Remove envelope DC
        // -----------------------------------------------------

        var mean =
            0.0

        for (v in env) {
            mean +=
                v.toDouble()
        }

        mean /=
            n


        // -----------------------------------------------------
        // Window + FFT
        // -----------------------------------------------------

        val re =
            FloatArray(n)

        val im =
            FloatArray(n)


        for (i in 0 until n) {

            re[i] =
                (
                        env[i] -
                                mean.toFloat()
                        ) *
                        window[i]

            im[i] =
                0f
        }


        FFT.transform(
            re,
            im
        )


        /*
         * Full one-sided envelope spectrum:
         * 0 ... Nyquist.
         */
        val half =
            n / 2 + 1


        val amp =
            FloatArray(half)


        for (k in 0 until half) {

            val mag =
                sqrt(
                    (
                            re[k] *
                                    re[k] +

                                    im[k] *
                                    im[k]
                            ).toDouble()
                ).toFloat()


            val isDc =
                k == 0

            val isNyquist =
                k == n / 2


            val scale =
                if (
                    isDc ||
                    isNyquist
                ) {

                    1f /
                            windowSum

                } else {

                    2f /
                            windowSum
                }


            amp[k] =
                mag *
                        scale
        }


        return amp
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