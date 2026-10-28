package com.pulsatio.stetho

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// =====================================================================
// Hap bicimli dugme (gecisli renk, basinca hafif kuculme)
// =====================================================================
class PillButton(ctx: Context, private val ui: Ui, style: Style) : TextView(ctx) {

    enum class Style { PRIMARY, DANGER, GHOST, TONAL, NEUTRAL }

    var style: Style = style
        set(v) { field = v; applyStyle() }

    private var iconType: Icon? = null

    init {
        gravity = Gravity.CENTER
        isAllCaps = false
        textSize = 16f * ui.textScale
        typeface = ui.fontMedium
        minHeight = ui.dp(54)
        minimumHeight = ui.dp(54)
        setPadding(ui.dp(20), 0, ui.dp(20), 0)
        isClickable = true
        isFocusable = true
        applyStyle()
    }

    fun setIcon(icon: Icon) {
        iconType = icon
        applyStyle()
    }

    private fun fg(): Int = when (style) {
        Style.PRIMARY -> Palette.onAccent
        Style.DANGER -> 0xFFFFFFFF.toInt()
        Style.TONAL -> Palette.accent
        else -> Palette.text
    }

    private fun applyStyle() {
        val bg = when (style) {
            Style.PRIMARY -> ui.gradient(Palette.accent, Palette.accent2, 28f,
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT)
            Style.DANGER -> ui.gradient(Palette.coral, Palette.coral2, 28f,
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT)
            Style.GHOST -> ui.rounded(Palette.surface, 28f, Palette.alpha(Palette.text, 0.16f))
            Style.TONAL -> ui.rounded(Palette.alpha(Palette.accent, 0.13f), 28f)
            Style.NEUTRAL -> ui.rounded(Palette.surface2, 28f)
        }
        background = ui.ripple(bg)
        setTextColor(fg())
        val ic = iconType
        if (ic != null) {
            val d = IconDrawable(ic, fg(), ui.dp(20), ic == Icon.PLAY || ic == Icon.PAUSE || ic == Icon.STOP)
            d.setBounds(0, 0, ui.dp(20), ui.dp(20))
            setCompoundDrawables(d, null, null, null)
            compoundDrawablePadding = ui.dp(8)
        }
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        animate().scaleX(if (pressed) 0.97f else 1f).scaleY(if (pressed) 0.97f else 1f).setDuration(110).start()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.38f
    }
}

// =====================================================================
// Kayan gostergeli secici
// =====================================================================
class SegmentedPill(
    ctx: Context,
    private val ui: Ui,
    private val labels: Array<String>,
    selected: Int,
    private val onSelect: (Int) -> Unit
) : View(ctx) {

    var selected: Int = selected
        private set
    private var pos = selected.toFloat()
    private var anim: ValueAnimator? = null

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.surface2 }
    private val ind = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = ui.dpf(14f) * ui.textScale
        typeface = ui.fontMedium
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    init {
        minimumHeight = ui.dp(46)
        isClickable = true
    }

    fun select(i: Int, notify: Boolean = false) {
        if (i == selected) return
        val from = pos
        selected = i
        anim?.cancel()
        anim = ValueAnimator.ofFloat(from, i.toFloat()).apply {
            duration = 240
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener { pos = it.animatedValue as Float; invalidate() }
            start()
        }
        if (notify) onSelect(i)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, ui.dp(46))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2f
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, track)
        val n = labels.size
        val segW = w / n
        val inset = ui.dpf(4f)
        rect.set(pos * segW + inset, inset, (pos + 1) * segW - inset, h - inset)
        if (ind.shader == null || rect.width() > 0) {
            ind.shader = LinearGradient(rect.left, 0f, rect.right, 0f, Palette.accent, Palette.accent2, Shader.TileMode.CLAMP)
        }
        canvas.drawRoundRect(rect, r - inset, r - inset, ind)
        val fm = txt.fontMetrics
        val baseline = h / 2f - (fm.ascent + fm.descent) / 2f
        for (i in 0 until n) {
            val closeness = 1f - min(1f, kotlin.math.abs(pos - i))
            txt.color = blend(Palette.muted, Palette.onAccent, closeness)
            canvas.drawText(labels[i], segW * i + segW / 2f, baseline, txt)
        }
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(c: Int, s: Int) = (c shr s) and 0xFF
        val r = (ch(a, 16) + (ch(b, 16) - ch(a, 16)) * t).toInt()
        val g = (ch(a, 8) + (ch(b, 8) - ch(a, 8)) * t).toInt()
        val bl = (ch(a, 0) + (ch(b, 0) - ch(a, 0)) * t).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        if (event.action == MotionEvent.ACTION_UP) {
            val i = (event.x / (width.toFloat() / labels.size)).toInt().coerceIn(0, labels.size - 1)
            if (i != selected) select(i, true)
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.45f
    }
}

// =====================================================================
// Kayit dugmesi: bos halka -> kayitta dolan ilerleme halkasi
// =====================================================================
class RecordButton(ctx: Context, private val ui: Ui) : View(ctx) {
    var recording = false
        set(v) { field = v; invalidate() }
    var progress = 0f
        set(v) { field = v; invalidate() }

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ui.dpf(4f)
        color = Palette.alpha(Palette.text, 0.14f)
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ui.dpf(5f)
        strokeCap = Paint.Cap.ROUND
        color = Palette.coral
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init { isClickable = true }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = ui.dp(96)
        setMeasuredDimension(s, s)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val c = w / 2f
        val r = c - ui.dpf(6f)
        if (recording) {
            val t = (SystemClock.uptimeMillis() % 1400L) / 1400f
            glow.shader = RadialGradient(c, c, r + ui.dpf(4f),
                Palette.alpha(Palette.coral, 0.30f * (1f - t) + 0.08f), 0, Shader.TileMode.CLAMP)
            canvas.drawCircle(c, c, r + ui.dpf(4f), glow)
            postInvalidateOnAnimation()
        }
        canvas.drawCircle(c, c, r, ring)
        if (recording) {
            rect.set(c - r, c - r, c + r, c + r)
            canvas.drawArc(rect, -90f, 360f * progress.coerceIn(0f, 1f), false, arc)
            val s = r * 0.42f
            fill.shader = null
            fill.color = Palette.coral
            rect.set(c - s, c - s, c + s, c + s)
            canvas.drawRoundRect(rect, ui.dpf(6f), ui.dpf(6f), fill)
        } else {
            val ir = r * 0.70f
            fill.shader = LinearGradient(c - ir, c - ir, c + ir, c + ir, Palette.coral, Palette.coral2, Shader.TileMode.CLAMP)
            canvas.drawCircle(c, c, ir, fill)
        }
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        animate().scaleX(if (pressed) 0.93f else 1f).scaleY(if (pressed) 0.93f else 1f).setDuration(120).start()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.4f
    }
}

// =====================================================================
// Nabiz gostergesi: nabiz hizinda atan kalp + buyuk sayi
// =====================================================================
class HeartRateView(ctx: Context, private val ui: Ui) : View(ctx) {
    var hr: Int? = null
        set(v) { field = v; invalidate() }
    var active = false
        set(v) { field = v; invalidate() }
    var caption = "nabız"

    private val heartPath = IconPaths.path(Icon.HEART)
    private val heart = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.text; typeface = ui.fontLight }
    private val unit = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.muted; typeface = ui.fontMedium }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), ui.dp(86))
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val hrv = hr
        var s = 1f
        if (active && hrv != null && hrv > 0) {
            val period = 60000f / hrv
            val ph = (SystemClock.uptimeMillis() % period.toLong()) / period
            s = 1f + 0.16f * exp(-ph * 9f) + 0.06f * exp(-max(0f, ph - 0.32f) * 14f) * if (ph > 0.32f) 1f else 0f
            postInvalidateOnAnimation()
        } else if (active) {
            postInvalidateDelayed(500)
        }
        val size = h * 0.62f
        val cx = size * 0.62f
        val cy = h / 2f
        heart.color = if (active) Palette.coral else Palette.alpha(Palette.coral, 0.35f)
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(s * size / 24f, s * size / 24f)
        canvas.translate(-12f, -12f)
        canvas.drawPath(heartPath, heart)
        canvas.restore()

        val x = cx + size * 0.75f
        num.textSize = h * 0.62f
        val t = if (hrv != null) "$hrv" else "--"
        canvas.drawText(t, x, cy + num.textSize * 0.36f, num)
        unit.textSize = ui.dpf(13f) * ui.textScale
        val nx = x + num.measureText(t) + ui.dpf(8f)
        canvas.drawText("bpm", nx, cy - ui.dpf(2f), unit)
        canvas.drawText(caption, nx, cy + unit.textSize + ui.dpf(2f), unit)
    }
}

// =====================================================================
// Govde haritasi: oskultasyon noktalari
// =====================================================================
class BodyMapView(ctx: Context, private val ui: Ui, private val onSelect: (Site) -> Unit) : View(ctx) {
    var selected: Site = Site.AORT
        set(v) { field = v; invalidate() }
    var done: Set<Site> = emptySet()
        set(v) { field = v; invalidate() }
    var locked = false

    private val bodyFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bodyLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = ui.dpf(1.6f); color = Palette.alpha(Palette.text, 0.22f)
        strokeCap = Paint.Cap.ROUND
    }
    private val rib = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = ui.dpf(1f); color = Palette.alpha(Palette.text, 0.08f)
    }
    private val heartGlow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ui.dpf(2f) }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = ui.fontMedium
    }
    private val body = android.graphics.Path()
    private val box = RectF()

    init { isClickable = true }

    private fun layoutBox() {
        val w = width.toFloat()
        val h = height.toFloat()
        val bw = min(w, h * 0.86f)
        val bh = bw / 0.86f
        box.set((w - bw) / 2f, (h - bh) / 2f, (w + bw) / 2f, (h + bh) / 2f)
    }

    private fun px(fx: Float) = box.left + fx * box.width()
    private fun py(fy: Float) = box.top + fy * box.height()

    override fun onDraw(canvas: Canvas) {
        layoutBox()
        body.reset()
        body.moveTo(px(0.40f), py(0.02f))
        body.cubicTo(px(0.40f), py(0.11f), px(0.29f), py(0.13f), px(0.14f), py(0.17f))
        body.cubicTo(px(0.06f), py(0.20f), px(0.05f), py(0.27f), px(0.07f), py(0.34f))
        body.lineTo(px(0.16f), py(0.98f))
        body.lineTo(px(0.84f), py(0.98f))
        body.lineTo(px(0.93f), py(0.34f))
        body.cubicTo(px(0.95f), py(0.27f), px(0.94f), py(0.20f), px(0.86f), py(0.17f))
        body.cubicTo(px(0.71f), py(0.13f), px(0.60f), py(0.11f), px(0.60f), py(0.02f))
        bodyFill.shader = LinearGradient(0f, box.top, 0f, box.bottom,
            Palette.alpha(Palette.surface3, 0.9f), Palette.alpha(Palette.surface, 0.4f), Shader.TileMode.CLAMP)
        canvas.drawPath(body, bodyFill)
        canvas.drawPath(body, bodyLine)

        // kaburgalar ve sternum
        for (i in 0 until 5) {
            val y = 0.24f + i * 0.085f
            canvas.drawLine(px(0.5f), py(y), px(0.20f + i * 0.01f), py(y + 0.06f), rib)
            canvas.drawLine(px(0.5f), py(y), px(0.80f - i * 0.01f), py(y + 0.06f), rib)
        }
        canvas.drawLine(px(0.5f), py(0.20f), px(0.5f), py(0.56f), bodyLine)

        // kalp golgesi
        heartGlow.shader = RadialGradient(px(0.57f), py(0.50f), box.width() * 0.17f,
            Palette.alpha(Palette.coral, 0.22f), 0, Shader.TileMode.CLAMP)
        canvas.drawCircle(px(0.57f), py(0.50f), box.width() * 0.17f, heartGlow)

        val r = ui.dpf(15f)
        label.textSize = ui.dpf(12f)
        val t = (SystemClock.uptimeMillis() % 1600L) / 1600f
        for (s in Site.values()) {
            val x = px(s.x)
            val y = py(s.y)
            val isSel = s == selected
            val isDone = s in done
            if (isSel) {
                dot.shader = null
                dot.color = Palette.alpha(Palette.accent, 0.25f * (1f - t))
                canvas.drawCircle(x, y, r + ui.dpf(12f) * t, dot)
            }
            dot.color = when {
                isDone -> Palette.accent
                isSel -> Palette.alpha(Palette.accent, 0.22f)
                else -> Palette.alpha(Palette.surface, 0.9f)
            }
            canvas.drawCircle(x, y, r, dot)
            dotLine.color = if (isSel || isDone) Palette.accent else Palette.alpha(Palette.text, 0.35f)
            canvas.drawCircle(x, y, r, dotLine)
            label.color = if (isDone) Palette.onAccent else if (isSel) Palette.accent else Palette.text
            val fm = label.fontMetrics
            canvas.drawText(if (isDone) "✓" else s.short, x, y - (fm.ascent + fm.descent) / 2f, label)
        }
        postInvalidateDelayed(40)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (locked) return false
        if (event.action == MotionEvent.ACTION_UP) {
            var best: Site? = null
            var bestD = ui.dpf(34f)
            for (s in Site.values()) {
                val d = hypot(event.x - px(s.x), event.y - py(s.y))
                if (d < bestD) { bestD = d; best = s }
            }
            val b = best
            if (b != null && b != selected) {
                selected = b
                onSelect(b)
            }
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

// =====================================================================
// Canli dalga formu (son N saniye, sutun basina min-max, gecisli renk)
// =====================================================================
class LiveWaveView(ctx: Context, private val ui: Ui, private val engine: AudioEngine) : View(ctx) {
    var seconds = 3
        set(v) { field = v.coerceIn(1, 6); buf = FloatArray(AudioEngine.FS * field) }
    var live = false
    var message = ""

    private var buf = FloatArray(AudioEngine.FS * 3)
    private var displayPeak = 0.01f
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = ui.dpf(1.4f) }
    private val grid = Paint().apply { color = Palette.alpha(Palette.text, 0.05f); strokeWidth = 1f }
    private val msg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.muted; textAlign = Paint.Align.CENTER; typeface = ui.fontMedium
    }
    private var shaderH = -1

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height.toFloat()
        if (w <= 0) return
        if (shaderH != height) {
            line.shader = LinearGradient(0f, 0f, w.toFloat(), 0f,
                Palette.alpha(Palette.accent2, 0.55f), Palette.accent, Shader.TileMode.CLAMP)
            shaderH = height
        }
        for (s in 1 until seconds) {
            val x = w * s / seconds.toFloat()
            canvas.drawLine(x, 0f, x, h, grid)
        }
        canvas.drawLine(0f, h / 2f, w.toFloat(), h / 2f, grid)

        if (!live) {
            msg.textSize = ui.dpf(14f)
            canvas.drawText(message, w / 2f, h / 2f - ui.dpf(10f), msg)
            // dekoratif sakin cizgi
            for (x in 0 until w step 3) {
                val y = h / 2f + sin(x / 18f + SystemClock.uptimeMillis() / 600f) * ui.dpf(2f)
                canvas.drawPoint(x.toFloat(), y + ui.dpf(24f), line)
            }
            postInvalidateDelayed(50)
            return
        }

        val b = buf
        engine.copyWaveform(b)
        var peak = 0f
        for (v in b) { val a = kotlin.math.abs(v); if (a > peak) peak = a }
        displayPeak = max(0.003f, displayPeak * 0.93f + peak * 0.07f)
        val scale = 0.44f * h / max(displayPeak, peak)
        val mid = h / 2f
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
            canvas.drawLine(x.toFloat(), mid - hi * scale, x.toFloat(), mid - lo * scale + 1f, line)
        }
    }
}

// =====================================================================
// Kayit dalga formu: S1/S2 isaretleri, surukleyerek gezinme, oynatma cizgisi
// =====================================================================
class RecordingWaveView(ctx: Context, private val ui: Ui) : View(ctx) {
    private var pcm: ShortArray? = null
    private var s1: FloatArray = FloatArray(0)
    private var s2: FloatArray = FloatArray(0)
    var windowSec = 4f
    private var startSec = 0f
    var playheadSec = -1f
        set(v) {
            field = v
            if (v >= 0f && (v < startSec || v > startSec + windowSec)) startSec = max(0f, v - windowSec * 0.2f)
            invalidate()
        }
    private var lastX = 0f

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = ui.dpf(1.2f); color = Palette.alpha(Palette.accent, 0.85f) }
    private val mk1 = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.s1; strokeWidth = ui.dpf(1.5f) }
    private val mk2 = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.s2; strokeWidth = ui.dpf(1.5f) }
    private val lbl = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = ui.dpf(11f); typeface = ui.fontMedium; textAlign = Paint.Align.CENTER }
    private val play = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); strokeWidth = ui.dpf(2f) }
    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.faint; textSize = ui.dpf(10f) }
    private val msg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.muted; textSize = ui.dpf(13f); textAlign = Paint.Align.CENTER }

    fun setData(p: ShortArray?, s1Times: FloatArray, s2Times: FloatArray) {
        pcm = p
        s1 = s1Times
        s2 = s2Times
        val dur = (p?.size ?: 0) / AudioEngine.FS.toFloat()
        startSec = max(0f, min(dur - windowSec, dur * 0.3f))
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height.toFloat()
        val p = pcm
        if (p == null || p.isEmpty() || w <= 0) {
            canvas.drawText("Yükleniyor…", w / 2f, h / 2f, msg)
            return
        }
        val fs = AudioEngine.FS
        val dur = p.size / fs.toFloat()
        val win = min(windowSec, dur)
        startSec = startSec.coerceIn(0f, max(0f, dur - win))
        val i0 = (startSec * fs).toInt()
        val n = (win * fs).toInt().coerceAtMost(p.size - i0)
        var peak = 1
        for (i in i0 until i0 + n) { val a = kotlin.math.abs(p[i].toInt()); if (a > peak) peak = a }
        val top = ui.dpf(18f)
        val bottom = h - ui.dpf(16f)
        val mid = (top + bottom) / 2f
        val scale = (bottom - top) * 0.46f / peak
        for (x in 0 until w) {
            val a = i0 + (x.toLong() * n / w).toInt()
            val b = max(a + 1, i0 + ((x + 1).toLong() * n / w).toInt())
            var lo = Int.MAX_VALUE
            var hi = Int.MIN_VALUE
            for (i in a until min(b, p.size)) { val v = p[i].toInt(); if (v < lo) lo = v; if (v > hi) hi = v }
            if (lo > hi) continue
            canvas.drawLine(x.toFloat(), mid - hi * scale, x.toFloat(), mid - lo * scale + 1f, line)
        }
        fun mark(times: FloatArray, paint: Paint, name: String) {
            lbl.color = paint.color
            for (t in times) {
                if (t < startSec || t > startSec + win) continue
                val x = (t - startSec) / win * w
                paint.alpha = 140
                canvas.drawLine(x, top, x, bottom, paint)
                paint.alpha = 255
                canvas.drawText(name, x, top - ui.dpf(4f), lbl)
            }
        }
        mark(s1, mk1, "S1")
        mark(s2, mk2, "S2")
        // zaman ekseni
        var t = kotlin.math.ceil(startSec.toDouble()).toFloat()
        while (t <= startSec + win) {
            val x = (t - startSec) / win * w
            canvas.drawText("${t.toInt()} sn", x + ui.dpf(3f), h - ui.dpf(3f), axis)
            t += 1f
        }
        if (playheadSec >= 0f && playheadSec in startSec..(startSec + win)) {
            val x = (playheadSec - startSec) / win * w
            canvas.drawLine(x, top, x, bottom, play)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val p = pcm ?: return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                lastX = event.x
                startSec -= dx / width * windowSec
                val dur = p.size / AudioEngine.FS.toFloat()
                startSec = startSec.coerceIn(0f, max(0f, dur - windowSec))
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }
}
