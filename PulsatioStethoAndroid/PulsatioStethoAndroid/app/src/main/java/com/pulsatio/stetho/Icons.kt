package com.pulsatio.stetho

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

enum class Icon { HEART, HOME, LIST, TUNE, BLUETOOTH, PLAY, PAUSE, STOP, BACK, PLUS, SHARE, TRASH,
    PERSON, ECG, CHECK, SEARCH, STETHO, CHEVRON, INFO, WARN, CLOCK }

/** 24x24 koordinatlarda cizilen cizgi ikonlar (harici kaynak gerekmez) */
object IconPaths {
    fun path(icon: Icon): Path {
        val p = Path()
        when (icon) {
            Icon.HEART -> {
                p.moveTo(12f, 20.5f)
                p.cubicTo(5f, 15.5f, 2.5f, 12f, 2.5f, 8.4f)
                p.cubicTo(2.5f, 5.5f, 4.7f, 3.5f, 7.3f, 3.5f)
                p.cubicTo(9.2f, 3.5f, 11f, 4.6f, 12f, 6.3f)
                p.cubicTo(13f, 4.6f, 14.8f, 3.5f, 16.7f, 3.5f)
                p.cubicTo(19.3f, 3.5f, 21.5f, 5.5f, 21.5f, 8.4f)
                p.cubicTo(21.5f, 12f, 19f, 15.5f, 12f, 20.5f)
                p.close()
            }
            Icon.HOME -> {
                p.moveTo(3f, 11f); p.lineTo(12f, 3.5f); p.lineTo(21f, 11f)
                p.moveTo(5.5f, 9.5f); p.lineTo(5.5f, 20f); p.lineTo(18.5f, 20f); p.lineTo(18.5f, 9.5f)
                p.moveTo(10f, 20f); p.lineTo(10f, 14f); p.lineTo(14f, 14f); p.lineTo(14f, 20f)
            }
            Icon.LIST -> {
                for (y in floatArrayOf(6f, 12f, 18f)) {
                    p.moveTo(9f, y); p.lineTo(20f, y)
                    p.addCircle(4.5f, y, 1.2f, Path.Direction.CW)
                }
            }
            Icon.TUNE -> {
                p.moveTo(3f, 6f); p.lineTo(21f, 6f); p.addCircle(9f, 6f, 2.2f, Path.Direction.CW)
                p.moveTo(3f, 12f); p.lineTo(21f, 12f); p.addCircle(15f, 12f, 2.2f, Path.Direction.CW)
                p.moveTo(3f, 18f); p.lineTo(21f, 18f); p.addCircle(7f, 18f, 2.2f, Path.Direction.CW)
            }
            Icon.BLUETOOTH -> {
                p.moveTo(6.5f, 7f); p.lineTo(17f, 16.5f); p.lineTo(12f, 21f); p.lineTo(12f, 3f)
                p.lineTo(17f, 7.5f); p.lineTo(6.5f, 17f)
            }
            Icon.PLAY -> { p.moveTo(8f, 5f); p.lineTo(19f, 12f); p.lineTo(8f, 19f); p.close() }
            Icon.PAUSE -> {
                p.addRoundRect(RectF(6.5f, 5f, 10f, 19f), 1f, 1f, Path.Direction.CW)
                p.addRoundRect(RectF(14f, 5f, 17.5f, 19f), 1f, 1f, Path.Direction.CW)
            }
            Icon.STOP -> p.addRoundRect(RectF(6f, 6f, 18f, 18f), 2.5f, 2.5f, Path.Direction.CW)
            Icon.BACK -> { p.moveTo(15f, 5f); p.lineTo(8f, 12f); p.lineTo(15f, 19f) }
            Icon.CHEVRON -> { p.moveTo(9f, 5f); p.lineTo(16f, 12f); p.lineTo(9f, 19f) }
            Icon.PLUS -> { p.moveTo(12f, 5f); p.lineTo(12f, 19f); p.moveTo(5f, 12f); p.lineTo(19f, 12f) }
            Icon.SHARE -> {
                p.addCircle(18f, 5.5f, 2.5f, Path.Direction.CW)
                p.addCircle(6f, 12f, 2.5f, Path.Direction.CW)
                p.addCircle(18f, 18.5f, 2.5f, Path.Direction.CW)
                p.moveTo(8.2f, 10.8f); p.lineTo(15.8f, 6.7f)
                p.moveTo(8.2f, 13.2f); p.lineTo(15.8f, 17.3f)
            }
            Icon.TRASH -> {
                p.moveTo(4f, 7f); p.lineTo(20f, 7f)
                p.moveTo(9f, 7f); p.lineTo(9f, 4f); p.lineTo(15f, 4f); p.lineTo(15f, 7f)
                p.moveTo(6f, 7f); p.lineTo(7f, 20f); p.lineTo(17f, 20f); p.lineTo(18f, 7f)
                p.moveTo(10f, 11f); p.lineTo(10f, 16f); p.moveTo(14f, 11f); p.lineTo(14f, 16f)
            }
            Icon.PERSON -> {
                p.addCircle(12f, 8f, 4f, Path.Direction.CW)
                p.moveTo(4f, 21f); p.cubicTo(4f, 16.5f, 7.5f, 14f, 12f, 14f); p.cubicTo(16.5f, 14f, 20f, 16.5f, 20f, 21f)
            }
            Icon.ECG -> {
                p.moveTo(2f, 12f); p.lineTo(6.5f, 12f); p.lineTo(8.5f, 7.5f); p.lineTo(11f, 18f)
                p.lineTo(14f, 4f); p.lineTo(16f, 12f); p.lineTo(22f, 12f)
            }
            Icon.CHECK -> { p.moveTo(5f, 12.5f); p.lineTo(10f, 17f); p.lineTo(19f, 7f) }
            Icon.SEARCH -> {
                p.addCircle(10.5f, 10.5f, 6f, Path.Direction.CW)
                p.moveTo(15f, 15f); p.lineTo(20.5f, 20.5f)
            }
            Icon.STETHO -> {
                p.moveTo(6f, 3f); p.lineTo(6f, 8f); p.cubicTo(6f, 12.5f, 12f, 12.5f, 12f, 8f); p.lineTo(12f, 3f)
                p.moveTo(9f, 11.5f); p.lineTo(9f, 15f); p.cubicTo(9f, 19.5f, 17f, 19.5f, 17f, 15f); p.lineTo(17f, 13.5f)
                p.addCircle(17f, 11f, 2.5f, Path.Direction.CW)
            }
            Icon.INFO -> {
                p.addCircle(12f, 12f, 9f, Path.Direction.CW)
                p.moveTo(12f, 11f); p.lineTo(12f, 16.5f)
                p.moveTo(12f, 7.5f); p.lineTo(12f, 8f)
            }
            Icon.WARN -> {
                p.moveTo(12f, 3.5f); p.lineTo(21.5f, 20f); p.lineTo(2.5f, 20f); p.close()
                p.moveTo(12f, 9.5f); p.lineTo(12f, 14f)
                p.moveTo(12f, 16.8f); p.lineTo(12f, 17.2f)
            }
            Icon.CLOCK -> {
                p.addCircle(12f, 12f, 9f, Path.Direction.CW)
                p.moveTo(12f, 7f); p.lineTo(12f, 12f); p.lineTo(15.5f, 14f)
            }
        }
        return p
    }
}

class IconDrawable(
    private val icon: Icon,
    private var color: Int,
    private val sizePx: Int,
    private val filled: Boolean = false
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = if (filled) Paint.Style.FILL else Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2f
        this.color = this@IconDrawable.color
    }
    private val path = IconPaths.path(icon)

    fun setTint2(c: Int) {
        color = c
        paint.color = c
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()) / 24f
        canvas.save()
        canvas.translate(b.left + (b.width() - 24f * s) / 2f, b.top + (b.height() - 24f * s) / 2f)
        canvas.scale(s, s)
        // dolu ikonlarin icindeki cizgiler (PAUSE/STOP/PLAY) dolu cizilir
        canvas.drawPath(path, paint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Drawable API")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = sizePx
    override fun getIntrinsicHeight(): Int = sizePx
}
