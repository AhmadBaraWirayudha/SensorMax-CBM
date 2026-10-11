package com.research.sensormax

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private fun assertNear(actual: Double, expected: Double, tol: Double, label: String) {
    check(abs(actual - expected) <= tol) { "$label: actual=$actual expected=$expected tol=$tol" }
}

private fun assertTrue(condition: Boolean, label: String) {
    check(condition) { label }
}

fun main() {
    // FFT round-trip and impulse response.
    run {
        val n = 256
        val original = FloatArray(n) { i ->
            (0.7 * sin(2.0 * PI * 17.0 * i / n) + 0.2 * cos(2.0 * PI * 41.0 * i / n)).toFloat()
        }
        val re = original.copyOf()
        val im = FloatArray(n)
        FFT.transform(re, im)
        val peak17 = sqrt((re[17] * re[17] + im[17] * im[17]).toDouble())
        val peak41 = sqrt((re[41] * re[41] + im[41] * im[41]).toDouble())
        assertTrue(peak17 > peak41, "FFT expected 17-bin component to dominate 41-bin component")
        FFT.inverseTransform(re, im)
        var rmse = 0.0
        for (i in 0 until n) {
            val e = re[i] - original[i]
            rmse += e * e
        }
        rmse = sqrt(rmse / n)
        assertNear(rmse, 0.0, 1e-5, "FFT inverse RMSE")
    }

    // Static calibration: 50 Hz stationary burst after 500 ms warmup.
    run {
        val n = 250
        val xs = FloatArray(n) { 0.12f + 0.002f * sin(2.0 * PI * it / 20.0).toFloat() }
        val ys = FloatArray(n) { -0.18f + 0.002f * cos(2.0 * PI * it / 20.0).toFloat() }
        val zs = FloatArray(n) { 9.80665f + 0.001f * sin(2.0 * PI * it / 25.0).toFloat() }
        val ts = LongArray(n) { it * 20_000_000L }
        val result = CalibrationCheck.evaluate(xs, ys, zs, ts)
            ?: error("Calibration returned null for valid burst")
        assertTrue(result.stationary, "Stationary calibration burst should pass")
        assertNear(result.achievedHz, 50.0, 0.25, "Calibration achieved Hz")
        assertTrue(result.analyzedSampleCount > 200, "Calibration should retain most samples")
        assertTrue(result.nonIncreasingTimestampCount == 0, "Calibration timestamps should be strictly increasing")
    }


    // Calibration must reject a burst with excessive vector movement.
    run {
        val n = 120
        val xs = FloatArray(n) { if (it % 2 == 0) 0f else 1.0f }
        val ys = FloatArray(n) { 0f }
        val zs = FloatArray(n) { 9.80665f }
        val ts = LongArray(n) { it * 20_000_000L }
        val result = CalibrationCheck.evaluate(xs, ys, zs, ts)
            ?: error("Unstable calibration burst should still produce diagnostics")
        assertTrue(!result.stationary, "Moving calibration burst must be rejected")
    }

    // Mounting check: fixed gravity vector should be stable and ready.
    run {
        val n = 200
        val xs = FloatArray(n) { 0.03f }
        val ys = FloatArray(n) { 0.01f }
        val zs = FloatArray(n) { 9.80665f }
        val result = MountingCheck.evaluate(xs, ys, zs)
            ?: error("Mounting check returned null for valid burst")
        assertTrue(result.ready, "Stable gravity vector should pass mounting check")
    }

    // Live waveform filter should preserve a constant signal after initialization.
    run {
        val filter = LiveWaveformFilter(10f)
        val first = filter.process(0L, 1f, 2f, 3f)
        val second = filter.process(20L, 1f, 2f, 3f)
        assertNear(first[0].toDouble(), 1.0, 1e-6, "Filter initial X")
        assertNear(second[1].toDouble(), 2.0, 1e-5, "Filter constant Y")
        assertNear(second[2].toDouble(), 3.0, 1e-5, "Filter constant Z")
    }

    // Vibration analyzer: exact 10 Hz tone sampled at 50 Hz.
    run {
        val analyzer = VibrationAnalyzer(256)
        val fs = 50.0
        var results = 0
        var last: SpectrumResult? = null
        for (i in 0 until 300) {
            val t = i / fs
            val x = (0.5 * sin(2.0 * PI * 10.0 * t)).toFloat()
            val tsNs = (i * 1_000_000_000L / 50L)
            last = analyzer.addSample(tsNs, x, 0f, 0f) ?: last
            if (last != null) results++
        }
        val result = last ?: error("Vibration analyzer produced no complete window")
        assertNear(result.sampleRateHz, 50.0, 1.0, "Analyzer sample rate")
        assertNear((result.perAxis[result.dominantAxis]?.peakFreqHz ?: 0f).toDouble(), 10.0, 1.0, "Analyzer dominant frequency")
        assertTrue(results > 0, "Analyzer should emit at least one spectrum")
    }


    // Golden-vector FFT amplitude and analytical velocity/displacement check.
    run {
        val n = 256
        val fs = 128.0
        val frequency = 10.0
        val accelAmplitude = 1.0
        val analyzer = VibrationAnalyzer(n)
        var last: SpectrumResult? = null
        for (i in 0 until n) {
            val t = i / fs
            val x = (accelAmplitude * sin(2.0 * PI * frequency * t)).toFloat()
            val tsNs = (i * 1_000_000_000.0 / fs).toLong()
            last = analyzer.addSample(tsNs, x, 0f, 0f) ?: last
        }
        val result = last ?: error("Golden-vector analyzer produced no result")
        val axis = result.perAxis[result.dominantAxis] ?: error("Missing dominant axis")
        val expectedVelocityPeak = accelAmplitude / (2.0 * PI * frequency) * 1000.0
        val expectedDisplacementPeak = accelAmplitude / ((2.0 * PI * frequency) * (2.0 * PI * frequency)) * 1_000_000.0
        assertNear(axis.peakAccelAmplitude.toDouble(), 1.0, 0.08, "Golden acceleration amplitude")
        assertNear(axis.rmsAccel.toDouble(), 1.0 / sqrt(2.0), 0.08, "Acceleration RMS analytical value")
        assertNear(axis.velocityAmplitude.maxOrNull()?.toDouble() ?: 0.0, expectedVelocityPeak, 0.8, "Velocity amplitude conversion")
        assertNear(axis.displacementAmplitudeUm.maxOrNull()?.toDouble() ?: 0.0, expectedDisplacementPeak, 130.0, "Displacement amplitude conversion")
        assertNear(result.sampleRateHz, fs, 0.15, "Nanosecond sample rate")
        assertTrue(result.timestampsMonotonic, "Nanosecond timestamps should remain monotonic")
        assertTrue(result.timestampJitterMs < 0.01f, "Synthetic timestamp jitter should remain near zero")
    }

    // Synthetic running-speed harmonic test: 1X + 2X + 3X must retain 1X as the dominant component.
    run {
        val analyzer = VibrationAnalyzer(256)
        val fs = 256.0
        val rpm = 1800.0
        val oneX = rpm / 60.0
        var last: SpectrumResult? = null
        for (i in 0 until 320) {
            val t = i / fs
            val x = (1.0 * sin(2.0 * PI * oneX * t) +
                    0.45 * sin(2.0 * PI * 2.0 * oneX * t) +
                    0.25 * sin(2.0 * PI * 3.0 * oneX * t)).toFloat()
            val tsNs = (i * 1_000_000_000.0 / fs).toLong()
            last = analyzer.addSample(tsNs, x, 0f, 0f) ?: last
        }
        val result = last ?: error("Harmonic analyzer produced no result")
        val axis = result.perAxis[result.dominantAxis] ?: error("Missing harmonic axis")
        assertNear(axis.peakFreqHz.toDouble(), oneX, 1.5, "1X dominant peak")
    }

    // Controlled amplitude modulation should produce an envelope peak near the modulation frequency.
    run {
        val analyzer = VibrationAnalyzer(256)
        val fs = 512.0
        val carrierHz = 100.0
        val modulationHz = 16.0
        var last: SpectrumResult? = null
        for (i in 0 until 384) {
            val t = i / fs
            val envelope = 1.0 + 0.8 * sin(2.0 * PI * modulationHz * t)
            val x = (envelope * sin(2.0 * PI * carrierHz * t)).toFloat()
            val tsNs = (i * 1_000_000_000.0 / fs).toLong()
            last = analyzer.addSample(tsNs, x, 0f, 0f) ?: last
        }
        val result = last ?: error("Envelope analyzer produced no result")
        val axis = result.perAxis[result.dominantAxis] ?: error("Missing envelope axis")
        assertTrue(axis.envelopeUsable, "Envelope should be usable at 512 Hz")
        assertNear(axis.envelopePeakFreqHz.toDouble(), modulationHz, 4.0, "Envelope modulation peak")
    }

    println("PURE_KOTLIN_QA_PASS")
}
