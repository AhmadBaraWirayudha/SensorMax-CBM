package com.research.sensormax

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View

/**
 * Lightweight waterfall/cascade spectrogram -- the "Waterfall/cascade
 * spectrum" from the CBM reference doc's recommended graph list. Each
 * [pushSpectrum] call adds one new time-slice at the top; frequency runs
 * left-to-right, intensity is color-coded blue (quiet) through red (loud).
 * Lets a developing fault's amplitude growth be seen across a monitoring
 * session, not just in a single snapshot.
 *
 * Implemented as a plain Bitmap rebuilt on each push (every ~2.5s, one FFT
 * window) rather than per-frame, so cost is a non-issue even on the
 * CPH2471's entry-level chipset. MPAndroidChart has no native heatmap
 * chart type, hence a small hand-rolled View instead of a library widget.
 */
class WaterfallView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val MAX_ROWS = 40
    }

    private val rows = ArrayDeque<FloatArray>()
    private var runningMax = 1e-6f
    private val drawPaint = Paint().apply { isFilterBitmap = true }
    private var bitmap: Bitmap? = null
    private val srcRect = Rect()
    private val dstRect = Rect()

    /** Adds one new spectrum slice (magnitude per frequency bin) to the top of the waterfall. */
    fun pushSpectrum(amplitudes: FloatArray) {
        if (amplitudes.isEmpty()) return
        rows.addFirst(amplitudes.copyOf())
        while (rows.size > MAX_ROWS) rows.removeLast()

        var localMax = 0f
        for (v in amplitudes) if (v > localMax) localMax = v
        // Slowly-decaying running max keeps the color scale stable rather than
        // flickering every window, while still adapting to a genuine trend.
        runningMax = if (localMax > runningMax) localMax else (runningMax * 0.97f).coerceAtLeast(1e-6f)

        rebuildBitmap()
        invalidate()
    }

    /** Clears all history -- call when a deployment session (re)starts or the axis selection changes. */
    fun clear() {
        rows.clear()
        runningMax = 1e-6f
        bitmap = null
        invalidate()
    }

    private fun rebuildBitmap() {
        var width = 0
        for (row in rows) if (row.size > width) width = row.size
        val height = rows.size
        if (width <= 0 || height <= 0) return

        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        var y = 0
        for (row in rows) {
            for (x in 0 until width) {
                val amp = if (x < row.size) row[x] else 0f
                bmp.setPixel(x, y, amplitudeToColor(amp))
            }
            y++
        }
        bitmap = bmp
    }

    private fun amplitudeToColor(amp: Float): Int {
        val norm = (amp / runningMax).coerceIn(0f, 1f)
        // Blue (quiet) -> cyan -> green -> yellow -> red (loud): hue 240 down to 0
        val hue = 240f * (1f - norm)
        return Color.HSVToColor(floatArrayOf(hue, 0.9f, 0.35f + 0.65f * norm))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        srcRect.set(0, 0, bmp.width, bmp.height)
        dstRect.set(0, 0, width, height)
        canvas.drawBitmap(bmp, srcRect, dstRect, drawPaint)
    }
}