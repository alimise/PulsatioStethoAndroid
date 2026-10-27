package com.pulsatio.stetho

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.abs
import kotlin.math.max

/** Son 2 saniyelik ham sinyal; otomatik olceklenir (sadece goruntu). */
class WaveformView(context: Context, private val engine: AudioEngine) : View(context) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(51, 217, 242)
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val gridPaint = Paint().apply {
        color = Color.rgb(46, 51, 64)
        strokeWidth = 1f
    }
    private val buf = FloatArray(AudioEngine.FS * 2)
    private val path = Path()
    private var displayPeak = 0.01f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawColor(Color.rgb(15, 18, 23))
        for (i in 1..4) canvas.drawLine(0f, h * i / 5f, w, h * i / 5f, gridPaint)
        for (i in 1..9) canvas.drawLine(w * i / 10f, 0f, w * i / 10f, h, gridPaint)

        engine.copyWaveform(buf)
        var peak = 0f
        for (v in buf) {
            val a = abs(v)
            if (a > peak) peak = a
        }
        // olcegi yumusak degistir, cok kucuk gurultuyu asiri buyutme
        displayPeak = max(0.002f, displayPeak * 0.9f + peak * 0.1f)
        val scale = 0.45f * h / max(displayPeak, peak)

        path.reset()
        val n = buf.size
        val step = max(1, n / max(1, width))
        var i = 0
        var first = true
        while (i < n) {
            val px = i.toFloat() / (n - 1) * w
            val py = h / 2f - buf[i] * scale
            if (first) {
                path.moveTo(px, py)
                first = false
            } else {
                path.lineTo(px, py)
            }
            i += step
        }
        canvas.drawPath(path, linePaint)
    }
}
