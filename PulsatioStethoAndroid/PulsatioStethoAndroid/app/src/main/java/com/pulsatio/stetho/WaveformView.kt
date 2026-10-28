package com.pulsatio.stetho

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Son birkac saniyelik ham sinyal. Her piksel sutununda min-max cizilir
 * (genis tablet ekraninda da hizli ve keskin). Olcek otomatik (sadece goruntu).
 * Sol ustte nabiz, sag ustte zaman penceresi yazar.
 */
class WaveformView(context: Context, private val engine: AudioEngine) : View(context) {

    var seconds = 3
        set(v) {
            field = v.coerceIn(1, 6)
            buf = FloatArray(AudioEngine.FS * field)
        }
    var hrText = ""
    var subText = ""

    private var buf = FloatArray(AudioEngine.FS * 3)
    private var displayPeak = 0.01f
    private val density = context.resources.displayMetrics.density

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(46, 196, 182)
        strokeWidth = 1.5f * density
    }
    private val gridPaint = Paint().apply {
        color = Color.rgb(36, 44, 56)
        strokeWidth = 1f
    }
    private val midPaint = Paint().apply {
        color = Color.rgb(56, 66, 82)
        strokeWidth = 1f
    }
    private val hrPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 30f * density
        typeface = Typeface.DEFAULT_BOLD
    }
    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(140, 150, 165)
        textSize = 12f * density
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width
        val h = height.toFloat()
        if (w <= 0) return
        canvas.drawColor(Color.rgb(17, 22, 29))

        // izgara: her saniyede dikey cizgi
        for (s in 1 until seconds) {
            val x = w * s / seconds.toFloat()
            canvas.drawLine(x, 0f, x, h, gridPaint)
        }
        for (i in 1..3) canvas.drawLine(0f, h * i / 4f, w.toFloat(), h * i / 4f, if (i == 2) midPaint else gridPaint)

        val b = buf
        engine.copyWaveform(b)
        var peak = 0f
        for (v in b) {
            val a = abs(v)
            if (a > peak) peak = a
        }
        displayPeak = max(0.002f, displayPeak * 0.92f + peak * 0.08f)
        val scale = 0.46f * h / max(displayPeak, peak)
        val mid = h / 2f

        // her piksel sutunu icin min-max
        val n = b.size
        for (x in 0 until w) {
            val i0 = (x.toLong() * n / w).toInt()
            val i1 = max(i0 + 1, ((x + 1).toLong() * n / w).toInt())
            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            for (i in i0 until min(i1, n)) {
                val v = b[i]
                if (v < lo) lo = v
                if (v > hi) hi = v
            }
            if (lo > hi) continue
            canvas.drawLine(x.toFloat(), mid - hi * scale, x.toFloat(), mid - lo * scale + 1f, linePaint)
        }

        val pad = 10f * density
        if (hrText.isNotEmpty()) canvas.drawText(hrText, pad, pad + hrPaint.textSize, hrPaint)
        if (subText.isNotEmpty()) canvas.drawText(subText, pad, pad + hrPaint.textSize + smallPaint.textSize + 4f * density, smallPaint)
        val label = "${seconds} sn"
        canvas.drawText(label, w - pad - smallPaint.measureText(label), pad + smallPaint.textSize, smallPaint)
    }
}
