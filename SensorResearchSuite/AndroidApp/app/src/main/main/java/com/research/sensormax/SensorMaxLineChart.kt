package com.research.sensormax

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import com.github.mikephil.charting.charts.LineChart

/**
 * SensorMax real chart widget.
 *
 * This is NOT a placeholder.
 *
 * Runtime:
 *     Uses the real MPAndroidChart LineChart implementation.
 *
 * Android Studio Layout Preview:
 *     Uses a lightweight deterministic drawing path because preview
 *     does not have a live SensorMax acquisition/FFT session.
 *
 * MainActivity can continue using:
 *
 *     private lateinit var vibrationChart: LineChart
 *     private lateinit var spectrumChart: LineChart
 *     private lateinit var trendChart: LineChart
 *     private lateinit var envelopeChart: LineChart
 */
class SensorMaxLineChart @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LineChart(context, attrs, defStyleAttr) {

    private val previewBackground =
        Paint().apply {
            color = Color.rgb(8, 14, 24)
            style = Paint.Style.FILL
        }

    private val previewGrid =
        Paint().apply {
            color = Color.rgb(30, 41, 59)
            strokeWidth = 1f
            style = Paint.Style.STROKE
            isAntiAlias = false
        }

    private val previewTrace =
        Paint().apply {
            color = Color.rgb(34, 211, 238)
            strokeWidth = 3f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

    private val emptyStateTextPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(100, 116, 139)
            textSize = 14f
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.MONOSPACE,
                android.graphics.Typeface.BOLD
            )
        }

    private var emptyStateText: String? =
        null

    private val previewText =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(100, 116, 139)
            textSize = 18f
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.MONOSPACE,
                android.graphics.Typeface.BOLD
            )
        }

    override fun onDraw(canvas: Canvas) {

        /*
         * ==============================================================
         * ANDROID STUDIO PREVIEW
         * ==============================================================
         */
        if (isInEditMode) {

            drawPreview(canvas)

            return
        }

        /*
         * ==============================================================
         * REAL RUNTIME CHART
         * ==============================================================
         */
        super.onDraw(canvas)

        if (data == null || data.entryCount == 0) {
            emptyStateText?.let { message ->
                canvas.drawText(
                    message,
                    width / 2f,
                    height / 2f,
                    emptyStateTextPaint
                )
            }
        }
    }

    fun setEmptyStateText(text: String?) {
        emptyStateText = text
        invalidate()
    }

    private fun drawPreview(
        canvas: Canvas
    ) {

        canvas.drawRect(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            previewBackground
        )

        if (
            width <= 0 ||
            height <= 0
        ) {
            return
        }

        val left = 16f
        val right = width.toFloat() - 16f
        val top = 16f
        val bottom = height.toFloat() - 16f

        /*
         * Horizontal grid.
         */
        val horizontalLines = 5

        for (
        i in 0..horizontalLines
        ) {

            val y =
                top +
                        (bottom - top) *
                        i /
                        horizontalLines.toFloat()

            canvas.drawLine(
                left,
                y,
                right,
                y,
                previewGrid
            )
        }

        /*
         * Vertical grid.
         */
        val verticalLines = 6

        for (
        i in 0..verticalLines
        ) {

            val x =
                left +
                        (right - left) *
                        i /
                        verticalLines.toFloat()

            canvas.drawLine(
                x,
                top,
                x,
                bottom,
                previewGrid
            )
        }

        /*
         * Structural waveform only.
         *
         * This is not presented as measurement data.
         */
        val points = 72

        var previousX = left
        var previousY =
            (
                    top +
                            bottom
                    ) / 2f

        for (
        i in 1..points
        ) {

            val fraction =
                i /
                        points.toFloat()

            val x =
                left +
                        (
                                right - left
                                ) *
                        fraction

            val y =
                (
                        top +
                                bottom
                        ) / 2f +
                        (
                                bottom - top
                                ) *
                        0.15f *
                        kotlin.math.sin(
                            fraction *
                                    Math.PI *
                                    5.0
                        )
                            .toFloat()

            canvas.drawLine(
                previousX,
                previousY,
                x,
                y,
                previewTrace
            )

            previousX = x
            previousY = y
        }

        /*
         * Label identifies this as the structural preview.
         */
        canvas.drawText(
            "SENSORMAX CHART",
            width / 2f,
            height / 2f + 7f,
            previewText
        )
    }
}
