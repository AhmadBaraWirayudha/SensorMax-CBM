package com.research.sensormax

/**
 * Minimal, dependency-free radix-2 Cooley-Tukey FFT.
 *
 * SensorMax runs this on-device on the CPH2471's entry-level 8-core/~2.3GHz
 * chipset with 3GB RAM, so this file intentionally avoids pulling in a DSP
 * library: a few dozen lines of iterative, in-place, allocation-light Kotlin
 * is both fast enough for a 256-point window and easy to audit by hand.
 */
object FFT {

    /** True when [n] is a positive power of two -- the only size this FFT accepts. */
    fun isPowerOfTwo(n: Int): Boolean = n > 0 && (n and (n - 1)) == 0

    /**
     * In-place FFT. [re] and [im] must have equal, power-of-two length; both
     * are overwritten with the transform (re[k]/im[k] = real/imaginary part
     * of bin k). For a real-valued input, initialize [im] to all zeros
     * before calling.
     */
    fun transform(re: FloatArray, im: FloatArray) {
        val n = re.size
        require(im.size == n) { "FFT: re/im length mismatch ($n vs ${im.size})" }
        require(isPowerOfTwo(n)) { "FFT: size must be a power of two, got $n" }
        if (n <= 1) return

        // --- Bit-reversal permutation ---
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
            var m = n shr 1
            while (m >= 1 && j >= m) {
                j -= m
                m = m shr 1
            }
            j += m
        }

        // --- Iterative Cooley-Tukey butterflies ---
        var size = 2
        while (size <= n) {
            val half = size shr 1
            val theta = -2.0 * Math.PI / size
            val stepWr = Math.cos(theta).toFloat()
            val stepWi = Math.sin(theta).toFloat()
            var start = 0
            while (start < n) {
                var wr = 1f
                var wi = 0f
                for (k in 0 until half) {
                    val evenIdx = start + k
                    val oddIdx = evenIdx + half
                    val tr = re[oddIdx] * wr - im[oddIdx] * wi
                    val ti = re[oddIdx] * wi + im[oddIdx] * wr
                    re[oddIdx] = re[evenIdx] - tr
                    im[oddIdx] = im[evenIdx] - ti
                    re[evenIdx] += tr
                    im[evenIdx] += ti
                    val nextWr = wr * stepWr - wi * stepWi
                    val nextWi = wr * stepWi + wi * stepWr
                    wr = nextWr
                    wi = nextWi
                }
                start += size
            }
            size = size shl 1
        }
    }

    /**
     * Symmetric Hann window of length [n]: w[i] = 0.5*(1 - cos(2*pi*i/(n-1))).
     * Applied before [transform] to reduce spectral leakage from the
     * non-periodic accelerometer window.
     */
    fun hannWindow(n: Int): FloatArray {
        if (n <= 1) return FloatArray(n) { 1f }
        return FloatArray(n) { i ->
            (0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (n - 1)))).toFloat()
        }
    }
}