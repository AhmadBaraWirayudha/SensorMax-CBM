package com.research.sensormax

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import kotlin.math.ln
import kotlin.math.max

/**
 * SensorMax waterfall / cascade spectrogram.
 *
 * RUNTIME
 * -------
 * Receives an already-computed FFT magnitude spectrum:
 *
 *     pushSpectrum(FloatArray)
 *
 * and stores successive spectra as rows.
 *
 * PREVIEW
 * -------
 * Android Studio Layout Preview cannot provide live FFT data, so when
 * isInEditMode == true this View draws a deterministic structural preview.
 *
 * The preview is NOT measurement data.
 *
 * API
 * ---
 * pushSpectrum(FloatArray)
 * clear()
 */
class WaterfallView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {

        private const val MAX_ROWS = 48

        private const val MIN_AMPLITUDE = 1e-9f

        private const val DISPLAY_MIN_DB = -45f
        private const val DISPLAY_MAX_DB = 0f

        private const val TEMPORAL_SMOOTHING_ALPHA = 0.30f

        private const val REFERENCE_PERCENTILE = 0.95f

        private const val REFERENCE_ATTACK_ALPHA = 0.25f
        private const val REFERENCE_RELEASE_ALPHA = 0.03f

        private const val MIN_REFERENCE_FRACTION = 1e-5f
    }

    // =====================================================================
    // HISTORY
    // =====================================================================

    /**
     * Newest row is index 0.
     */
    private val rows =
        ArrayDeque<FloatArray>()

    /**
     * Number of FFT bins represented by each row.
     */
    private var spectrumWidth =
        0

    // =====================================================================
    // TEMPORAL FILTER
    // =====================================================================

    private var previousSpectrum: FloatArray? =
        null

    // =====================================================================
    // REFERENCE
    // =====================================================================

    private var referenceAmplitude =
        MIN_AMPLITUDE

    // =====================================================================
    // BITMAP
    // =====================================================================

    private var bitmap: Bitmap? =
        null

    private var pixelBuffer =
        IntArray(0)

    private val drawPaint =
        Paint().apply {
            isFilterBitmap = false
            isAntiAlias = false
            isDither = false
        }

    private val emptyStatePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(100, 116, 139)
            textSize = 14f
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.MONOSPACE,
                android.graphics.Typeface.BOLD
            )
        }

    private val srcRect =
        Rect()

    private val dstRect =
        Rect()

    // =====================================================================
    // PUBLIC API
    // =====================================================================

    /**
     * Add one FFT magnitude spectrum.
     *
     * Newest spectrum is displayed at the top.
     */
    fun pushSpectrum(
        amplitudes: FloatArray
    ) {

        if (amplitudes.isEmpty()) {
            return
        }

        val cleanSpectrum =
            sanitizeSpectrum(
                amplitudes
            )

        if (cleanSpectrum.isEmpty()) {
            return
        }

        // FFT dimensions must remain consistent across the waterfall.
        if (spectrumWidth == 0) {

            spectrumWidth =
                cleanSpectrum.size

        } else if (
            cleanSpectrum.size != spectrumWidth
        ) {

            clear()

            spectrumWidth =
                cleanSpectrum.size
        }

        val filteredSpectrum =
            temporalSmooth(
                cleanSpectrum
            )

        val currentReference =
            percentile(
                filteredSpectrum,
                REFERENCE_PERCENTILE
            )

        updateReference(
            currentReference
        )

        rows.addFirst(
            filteredSpectrum.copyOf()
        )

        while (
            rows.size > MAX_ROWS
        ) {
            rows.removeLast()
        }

        rebuildBitmap()

        invalidate()
    }

    /**
     * Clears waterfall history and adaptive state.
     */
    fun clear() {

        rows.clear()

        spectrumWidth =
            0

        previousSpectrum =
            null

        referenceAmplitude =
            MIN_AMPLITUDE

        bitmap =
            null

        pixelBuffer =
            IntArray(0)

        invalidate()
    }

    // =====================================================================
    // SANITIZATION
    // =====================================================================

    private fun sanitizeSpectrum(
        amplitudes: FloatArray
    ): FloatArray {

        val clean =
            FloatArray(
                amplitudes.size
            )

        var validCount =
            0

        for (
        i in amplitudes.indices
        ) {

            val value =
                amplitudes[i]

            if (
                value.isFinite() &&
                value > 0f
            ) {

                clean[i] =
                    value

                validCount++

            } else {

                clean[i] =
                    0f
            }
        }

        if (
            validCount == 0
        ) {
            return FloatArray(0)
        }

        return clean
    }

    // =====================================================================
    // TEMPORAL SMOOTHING
    // =====================================================================

    private fun temporalSmooth(
        current: FloatArray
    ): FloatArray {

        val previous =
            previousSpectrum

        if (
            previous == null ||
            previous.size != current.size
        ) {

            previousSpectrum =
                current.copyOf()

            return current.copyOf()
        }

        val result =
            FloatArray(
                current.size
            )

        val alpha =
            TEMPORAL_SMOOTHING_ALPHA

        for (
        i in current.indices
        ) {

            result[i] =
                alpha * current[i] +
                        (
                                1f - alpha
                                ) * previous[i]
        }

        previousSpectrum =
            result.copyOf()

        return result
    }

    // =====================================================================
    // REFERENCE TRACKING
    // =====================================================================

    private fun updateReference(
        currentReference: Float
    ) {

        val candidate =
            max(
                currentReference,
                MIN_AMPLITUDE
            )

        if (
            candidate > referenceAmplitude
        ) {

            referenceAmplitude +=
                REFERENCE_ATTACK_ALPHA *
                        (
                                candidate -
                                        referenceAmplitude
                                )

        } else {

            referenceAmplitude +=
                REFERENCE_RELEASE_ALPHA *
                        (
                                candidate -
                                        referenceAmplitude
                                )
        }

        referenceAmplitude =
            max(
                referenceAmplitude,
                MIN_AMPLITUDE
            )
    }

    // =====================================================================
    // PERCENTILE
    // =====================================================================

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

    // =====================================================================
    // BITMAP
    // =====================================================================

    private fun rebuildBitmap() {

        val width =
            spectrumWidth

        val height =
            rows.size

        if (
            width <= 0 ||
            height <= 0
        ) {

            bitmap =
                null

            return
        }

        val existing =
            bitmap

        bitmap =
            if (
                existing != null &&
                !existing.isRecycled &&
                existing.width == width &&
                existing.height == height
            ) {

                existing

            } else {

                Bitmap.createBitmap(
                    width,
                    height,
                    Bitmap.Config.ARGB_8888
                )
            }

        val requiredPixels =
            width * height

        if (
            pixelBuffer.size != requiredPixels
        ) {

            pixelBuffer =
                IntArray(
                    requiredPixels
                )
        }

        var pixelIndex =
            0

        for (
        row in rows
        ) {

            for (
            x in 0 until width
            ) {

                val amplitude =
                    if (
                        x < row.size
                    ) {
                        row[x]
                    } else {
                        0f
                    }

                pixelBuffer[pixelIndex] =
                    amplitudeToColor(
                        amplitude
                    )

                pixelIndex++
            }
        }

        bitmap?.setPixels(
            pixelBuffer,
            0,
            width,
            0,
            0,
            width,
            height
        )
    }

    // =====================================================================
    // AMPLITUDE -> COLOR
    // =====================================================================

    private fun amplitudeToColor(
        amplitude: Float
    ): Int {

        val safeAmplitude =
            max(
                amplitude,
                MIN_AMPLITUDE
            )

        val reference =
            max(
                referenceAmplitude,
                MIN_AMPLITUDE
            )

        val ratio =
            max(
                safeAmplitude / reference,
                MIN_REFERENCE_FRACTION
            )

        val db =
            (
                    20.0 *
                            ln(
                                ratio.toDouble()
                            ) /
                            ln(10.0)
                    )
                .toFloat()

        val normalized =
            (
                    (
                            db -
                                    DISPLAY_MIN_DB
                            ) /
                            (
                                    DISPLAY_MAX_DB -
                                            DISPLAY_MIN_DB
                                    )
                    )
                .coerceIn(
                    0f,
                    1f
                )

        return colorMap(
            normalized
        )
    }

    // =====================================================================
    // COLOR MAP
    // =====================================================================

    private fun colorMap(
        value: Float
    ): Int {

        val v =
            value.coerceIn(
                0f,
                1f
            )

        return when {

            v < 0.20f -> {

                interpolateColor(
                    Color.rgb(
                        4,
                        10,
                        30
                    ),
                    Color.rgb(
                        0,
                        55,
                        150
                    ),
                    v / 0.20f
                )
            }

            v < 0.40f -> {

                interpolateColor(
                    Color.rgb(
                        0,
                        55,
                        150
                    ),
                    Color.rgb(
                        0,
                        180,
                        220
                    ),
                    (
                            v -
                                    0.20f
                            ) / 0.20f
                )
            }

            v < 0.65f -> {

                interpolateColor(
                    Color.rgb(
                        0,
                        180,
                        220
                    ),
                    Color.rgb(
                        255,
                        220,
                        0
                    ),
                    (
                            v -
                                    0.40f
                            ) / 0.25f
                )
            }

            else -> {

                interpolateColor(
                    Color.rgb(
                        255,
                        220,
                        0
                    ),
                    Color.rgb(
                        220,
                        20,
                        20
                    ),
                    (
                            v -
                                    0.65f
                            ) / 0.35f
                )
            }
        }
    }

    private fun interpolateColor(
        colorA: Int,
        colorB: Int,
        fraction: Float
    ): Int {

        val f =
            fraction.coerceIn(
                0f,
                1f
            )

        val r =
            (
                    Color.red(colorA) +
                            (
                                    Color.red(colorB) -
                                            Color.red(colorA)
                                    ) * f
                    )
                .toInt()
                .coerceIn(0, 255)

        val g =
            (
                    Color.green(colorA) +
                            (
                                    Color.green(colorB) -
                                            Color.green(colorA)
                                    ) * f
                    )
                .toInt()
                .coerceIn(0, 255)

        val b =
            (
                    Color.blue(colorA) +
                            (
                                    Color.blue(colorB) -
                                            Color.blue(colorA)
                                    ) * f
                    )
                .toInt()
                .coerceIn(0, 255)

        return Color.rgb(
            r,
            g,
            b
        )
    }

    // =====================================================================
    // PREVIEW + RUNTIME DRAWING
    // =====================================================================

    override fun onDraw(
        canvas: Canvas
    ) {

        /*
         * ==============================================================
         * ANDROID STUDIO LAYOUT PREVIEW
         * ==============================================================
         *
         * Do NOT call the normal bitmap path here.
         *
         * Preview has no real FFT data, so draw only the component itself.
         */
        if (
            isInEditMode
        ) {

            drawPreview(
                canvas
            )

            return
        }

        super.onDraw(
            canvas
        )

        val bmp =
            bitmap

        if (
            bmp == null ||
            bmp.isRecycled ||
            bmp.width <= 0 ||
            bmp.height <= 0 ||
            width <= 0 ||
            height <= 0
        ) {
            drawEmptyState(
                canvas
            )
            return
        }

        srcRect.set(
            0,
            0,
            bmp.width,
            bmp.height
        )

        dstRect.set(
            0,
            0,
            width,
            height
        )

        canvas.drawBitmap(
            bmp,
            srcRect,
            dstRect,
            drawPaint
        )
    }

    private fun drawEmptyState(
        canvas: Canvas
    ) {

        canvas.drawColor(
            Color.rgb(8, 14, 24)
        )

        if (width <= 0 || height <= 0) {
            return
        }

        canvas.drawText(
            "Waiting for spectral history",
            width / 2f,
            height / 2f,
            emptyStatePaint
        )
    }

    // =====================================================================
    // LAYOUT PREVIEW
    // =====================================================================

    private fun drawPreview(
        canvas: Canvas
    ) {

        canvas.drawColor(
            Color.rgb(
                8,
                14,
                24
            )
        )

        if (
            width <= 0 ||
            height <= 0
        ) {
            return
        }

        val gridPaint =
            Paint().apply {
                color =
                    Color.rgb(
                        30,
                        41,
                        59
                    )

                strokeWidth =
                    1f

                isAntiAlias =
                    false
            }

        val textPaint =
            Paint(
                Paint.ANTI_ALIAS_FLAG
            ).apply {

                color =
                    Color.rgb(
                        100,
                        116,
                        139
                    )

                textSize =
                    18f

                textAlign =
                    Paint.Align.CENTER

                typeface =
                    android.graphics.Typeface.create(
                        android.graphics.Typeface.MONOSPACE,
                        android.graphics.Typeface.BOLD
                    )
            }

        val rowsPreview =
            8

        val columnsPreview =
            16

        /*
         * Draw horizontal grid.
         */
        for (
        r in 1 until rowsPreview
        ) {

            val y =
                height.toFloat() *
                        r /
                        rowsPreview.toFloat()

            canvas.drawLine(
                0f,
                y,
                width.toFloat(),
                y,
                gridPaint
            )
        }

        /*
         * Draw vertical FFT-bin grid.
         */
        for (
        c in 1 until columnsPreview
        ) {

            val x =
                width.toFloat() *
                        c /
                        columnsPreview.toFloat()

            canvas.drawLine(
                x,
                0f,
                x,
                height.toFloat(),
                gridPaint
            )
        }

        /*
         * Draw a structural label only.
         *
         * This is explicitly not a measurement.
         */
        canvas.drawText(
            "SPECTRAL WATERFALL",
            width / 2f,
            height / 2f,
            textPaint
        )
    }
}
