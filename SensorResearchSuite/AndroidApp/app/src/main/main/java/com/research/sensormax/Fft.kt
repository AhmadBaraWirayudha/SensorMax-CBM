package com.research.sensormax

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Dependency-free radix-2 Cooley-Tukey FFT for SensorMax.
 *
 * This implementation keeps the public API used by VibrationAnalyzer:
 *
 *     FFT.isPowerOfTwo(n)
 *     FFT.transform(re, im)
 *     FFT.hannWindow(n)
 *
 * while improving the implementation in several ways:
 *
 *  - FFT plans are cached, so repeated 256-point transforms do not rebuild
 *    bit-reversal information or trigonometric twiddle factors.
 *  - Twiddle factors are precomputed once per FFT size instead of being
 *    advanced with a multiplicative recurrence on every butterfly. This
 *    reduces cumulative phase drift and removes repeated sin/cos work from
 *    the hot loop.
 *  - The forward transform keeps the same convention as the original code:
 *        X[k] = sum x[n] * exp(-j*2*pi*k*n/N)
 *  - An inverse transform is provided for verification/future DSP work.
 *  - Input validation is explicit and fail-fast.
 *  - The hot transform path performs no heap allocation after the FFT plan
 *    has been created.
 *
 * IMPORTANT:
 * This class does not filter, detrend, calibrate, or resample sensor data.
 * Those are separate DSP stages. The FFT receives the already-prepared
 * window and transforms it without changing the samples themselves.
 */
object FFT {

    /**
     * Internal immutable plan for one FFT length.
     *
     * bitReverse[i] gives the index exchanged with i before the butterfly
     * stages.
     *
     * For each stage s:
     *     cos[s][k] = cos(-2*pi*k/stageSize)
     *     sin[s][k] = sin(-2*pi*k/stageSize)
     *
     * The inverse transform reuses the same cosine values and negates the
     * sine term.
     */
    private data class FFTPlan(
        val size: Int,
        val bitReverse: IntArray,
        val cosTables: Array<FloatArray>,
        val sinTables: Array<FloatArray>
    )

    /**
     * SensorMax currently uses one FFT size repeatedly (normally 256), so a
     * one-plan cache is enough and avoids maintaining an unbounded map of
     * plans if a future feature experiments with other lengths.
     */
    @Volatile
    private var cachedPlan: FFTPlan? = null

    /**
     * True when [n] is a positive power of two.
     */
    fun isPowerOfTwo(
        n: Int
    ): Boolean =
        n > 0 &&
                (n and (n - 1)) == 0

    /**
     * Forward in-place FFT.
     *
     * Convention:
     *
     *     X[k] =
     *         Σ x[n] * exp(-j 2πkn/N)
     *
     * [re] and [im] must have equal, power-of-two length.
     *
     * The arrays are overwritten in place.
     *
     * For a real-valued signal, initialize [im] to zero before calling.
     *
     * There is intentionally no 1/N normalization in the forward transform,
     * matching the original SensorMax implementation.
     */
    fun transform(
        re: FloatArray,
        im: FloatArray
    ) {
        transformInternal(
            re = re,
            im = im,
            inverse = false
        )
    }

    /**
     * In-place inverse FFT.
     *
     * Convention:
     *
     *     x[n] =
     *         (1/N) Σ X[k] * exp(+j 2πkn/N)
     *
     * After this function returns, [re]/[im] contain the reconstructed time
     * domain signal and are normalized by 1/N.
     *
     * This is primarily useful for testing and future DSP operations; the
     * current SensorMax vibration analysis only needs the forward transform.
     */
    fun inverseTransform(
        re: FloatArray,
        im: FloatArray
    ) {
        transformInternal(
            re = re,
            im = im,
            inverse = true
        )

        val scale =
            1f / re.size.toFloat()

        for (i in re.indices) {
            re[i] *= scale
            im[i] *= scale
        }
    }

    /**
     * Common iterative radix-2 transform implementation.
     */
    private fun transformInternal(
        re: FloatArray,
        im: FloatArray,
        inverse: Boolean
    ) {
        val n =
            re.size

        require(im.size == n) {
            "FFT: re/im length mismatch ($n vs ${im.size})"
        }

        require(isPowerOfTwo(n)) {
            "FFT: size must be a positive power of two, got $n"
        }

        if (n <= 1) {
            return
        }

        val plan =
            getPlan(n)

        // ================================================================
        // BIT-REVERSAL PERMUTATION
        // ================================================================
        //
        // The permutation itself is computed once when the plan is built.
        // The transform only performs the required swaps here.
        //
        for (i in 0 until n) {

            val j =
                plan.bitReverse[i]

            if (i < j) {

                val tr =
                    re[i]

                re[i] =
                    re[j]

                re[j] =
                    tr

                val ti =
                    im[i]

                im[i] =
                    im[j]

                im[j] =
                    ti
            }
        }

        // ================================================================
        // ITERATIVE COOLEY-TUKEY BUTTERFLIES
        // ================================================================
        //
        // For every stage:
        //
        //     even = A
        //     odd  = B
        //
        //     T = B * W
        //
        //     A' = A + T
        //     B' = A - T
        //
        // The forward transform uses exp(-jθ).
        // The inverse uses exp(+jθ), i.e. the opposite sine sign.
        //
        var stageIndex =
            0

        var stageSize =
            2

        while (
            stageSize <= n
        ) {

            val half =
                stageSize ushr 1

            val cosTable =
                plan.cosTables[stageIndex]

            val sinTable =
                plan.sinTables[stageIndex]

            var blockStart =
                0

            while (
                blockStart < n
            ) {

                for (
                k in 0 until half
                ) {

                    val evenIndex =
                        blockStart + k

                    val oddIndex =
                        evenIndex + half

                    val wr =
                        cosTable[k]

                    /*
                     * Forward:
                     *
                     *     sin(theta)
                     *
                     * Inverse:
                     *
                     *     -sin(theta)
                     */
                    val wi =
                        if (inverse) {
                            -sinTable[k]
                        } else {
                            sinTable[k]
                        }

                    val oddRe =
                        re[oddIndex]

                    val oddIm =
                        im[oddIndex]

                    val twiddleRe =
                        oddRe * wr -
                                oddIm * wi

                    val twiddleIm =
                        oddRe * wi +
                                oddIm * wr

                    val evenRe =
                        re[evenIndex]

                    val evenIm =
                        im[evenIndex]

                    re[evenIndex] =
                        evenRe +
                                twiddleRe

                    im[evenIndex] =
                        evenIm +
                                twiddleIm

                    re[oddIndex] =
                        evenRe -
                                twiddleRe

                    im[oddIndex] =
                        evenIm -
                                twiddleIm
                }

                blockStart +=
                    stageSize
            }

            stageIndex++

            stageSize =
                stageSize shl 1
        }
    }

    /**
     * Returns the cached FFT plan for [n], creating it once if required.
     *
     * The plan is immutable after construction, so it is safe for concurrent
     * reads. Synchronization only occurs when a new size has to replace the
     * cached plan.
     */
    private fun getPlan(
        n: Int
    ): FFTPlan {

        val existing =
            cachedPlan

        if (
            existing != null &&
            existing.size == n
        ) {
            return existing
        }

        return synchronized(this) {

            val again =
                cachedPlan

            if (
                again != null &&
                again.size == n
            ) {

                again

            } else {

                buildPlan(
                    n
                ).also {
                    cachedPlan = it
                }
            }
        }
    }

    /**
     * Builds all data needed by the iterative FFT for one size.
     *
     * This runs only on the first transform for a given size.
     */
    private fun buildPlan(
        n: Int
    ): FFTPlan {

        require(isPowerOfTwo(n)) {
            "FFT: cannot build plan for non-power-of-two size $n"
        }

        val bitReverse =
            buildBitReverseTable(
                n
            )

        val stageCount =
            Integer.numberOfTrailingZeros(
                n
            )

        val cosTables =
            Array(
                stageCount
            ) {
                FloatArray(0)
            }

        val sinTables =
            Array(
                stageCount
            ) {
                FloatArray(0)
            }

        var stageSize =
            2

        var stageIndex =
            0

        while (
            stageSize <= n
        ) {

            val half =
                stageSize ushr 1

            val cosTable =
                FloatArray(
                    half
                )

            val sinTable =
                FloatArray(
                    half
                )

            /*
             * Forward-transform angle:
             *
             *     -2πk / stageSize
             *
             * Store this convention once. The inverse simply negates the
             * sine component.
             */
            val angleScale =
                -2.0 *
                        PI /
                        stageSize.toDouble()

            for (
            k in 0 until half
            ) {

                val angle =
                    angleScale *
                            k.toDouble()

                cosTable[k] =
                    cos(
                        angle
                    ).toFloat()

                sinTable[k] =
                    sin(
                        angle
                    ).toFloat()
            }

            cosTables[stageIndex] =
                cosTable

            sinTables[stageIndex] =
                sinTable

            stageIndex++

            stageSize =
                stageSize shl 1
        }

        return FFTPlan(
            size =
                n,

            bitReverse =
                bitReverse,

            cosTables =
                cosTables,

            sinTables =
                sinTables
        )
    }

    /**
     * Builds an N-element bit-reversal table.
     *
     * For N = 8:
     *
     *     0 -> 0
     *     1 -> 4
     *     2 -> 2
     *     3 -> 6
     *     4 -> 1
     *     5 -> 5
     *     6 -> 3
     *     7 -> 7
     */
    private fun buildBitReverseTable(
        n: Int
    ): IntArray {

        val table =
            IntArray(
                n
            )

        if (
            n <= 1
        ) {
            return table
        }

        val bits =
            Integer.numberOfTrailingZeros(
                n
            )

        for (
        i in 0 until n
        ) {

            var value =
                i

            var reversed =
                0

            repeat(
                bits
            ) {

                reversed =
                    (
                            reversed shl 1
                            ) or
                            (
                                    value and 1
                                    )

                value =
                    value ushr 1
            }

            table[i] =
                reversed
        }

        return table
    }

    /**
     * Symmetric Hann window of length [n]:
     *
     *     w[i] =
     *         0.5 * (1 - cos(2πi/(n-1)))
     *
     * The symmetric definition is retained from the original SensorMax
     * implementation because VibrationAnalyzer currently uses it when
     * calculating its window sum for amplitude normalization.
     */
    fun hannWindow(
        n: Int
    ): FloatArray {

        require(n >= 0) {
            "FFT: Hann window length cannot be negative: $n"
        }

        if (
            n == 0
        ) {
            return FloatArray(
                0
            )
        }

        if (
            n == 1
        ) {
            return FloatArray(
                1
            ) {
                1f
            }
        }

        val window =
            FloatArray(
                n
            )

        val denominator =
            (
                    n - 1
                    ).toDouble()

        val twoPi =
            2.0 *
                    PI

        for (
        i in 0 until n
        ) {

            window[i] =
                (
                        0.5 *
                                (
                                        1.0 -
                                                cos(
                                                    twoPi *
                                                            i.toDouble() /
                                                            denominator
                                                )
                                        )
                        ).toFloat()
        }

        return window
    }
}
