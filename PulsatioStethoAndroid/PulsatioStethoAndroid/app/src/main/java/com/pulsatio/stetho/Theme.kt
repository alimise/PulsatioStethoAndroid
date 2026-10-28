package com.pulsatio.stetho

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Renk paleti (koyu, klinik ortamda goz yormayan) */
object Palette {
    val bgTop = 0xFF0A1020.toInt()
    val bgBottom = 0xFF0F1A2E.toInt()
    val surface = 0xFF141E31.toInt()
    val surface2 = 0xFF1B2740.toInt()
    val surface3 = 0xFF22304D.toInt()
    val stroke = 0x1AFFFFFF
    val text = 0xFFEAF0F8.toInt()
    val muted = 0xFF8E9BB3.toInt()
    val faint = 0xFF56647E.toInt()
    val accent = 0xFF2EE6C5.toInt()     // turkuaz
    val accent2 = 0xFF22A7F0.toInt()    // mavi
    val onAccent = 0xFF03161A.toInt()
    val coral = 0xFFFF5A6E.toInt()
    val coral2 = 0xFFFF8A5B.toInt()
    val amber = 0xFFFFC14D.toInt()
    val violet = 0xFF8B7CFF.toInt()
    val s1 = 0xFF2EE6C5.toInt()
    val s2 = 0xFF7AA8FF.toInt()

    fun alpha(c: Int, a: Float): Int = (c and 0x00FFFFFF) or ((a * 255f).toInt().coerceIn(0, 255) shl 24)
}

/** Olcu, yazi tipi ve ortak gorunum fabrikalari */
class Ui(val ctx: Context) {
    val density: Float = ctx.resources.displayMetrics.density
    var textScale = 1f

    val fontRegular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val fontMedium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val fontLight: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    val fontMono: Typeface = Typeface.MONOSPACE

    fun dp(v: Float): Int = (v * density + 0.5f).toInt()
    fun dp(v: Int): Int = dp(v.toFloat())
    fun dpf(v: Float): Float = v * density

    // ---------- arka planlar ----------

    fun rounded(color: Int, radiusDp: Float = 20f, strokeColor: Int? = null, strokeDp: Float = 1f): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dpf(radiusDp)
            if (strokeColor != null) setStroke(dp(strokeDp), strokeColor)
        }

    fun gradient(c1: Int, c2: Int, radiusDp: Float = 20f,
                 orientation: GradientDrawable.Orientation = GradientDrawable.Orientation.TL_BR): GradientDrawable =
        GradientDrawable(orientation, intArrayOf(c1, c2)).apply { cornerRadius = dpf(radiusDp) }

    fun ripple(content: Drawable, rippleColor: Int = 0x2EFFFFFF): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(rippleColor), content, null)

    // ---------- yerlesim ----------

    fun vbox(): LinearLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    fun hbox(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun lp(w: Int = MATCH_PARENT, h: Int = WRAP_CONTENT, weight: Float = 0f,
           top: Int = 0, bottom: Int = 0, start: Int = 0, end: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h, weight).apply { setMargins(dp(start), dp(top), dp(end), dp(bottom)) }

    fun spacer(hDp: Int): View = View(ctx).apply { minimumHeight = dp(hDp) }

    fun card(padDp: Int = 16, color: Int = Palette.surface): LinearLayout = vbox().apply {
        background = rounded(color, 22f, Palette.stroke)
        setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp))
    }

    // ---------- yazi ----------

    fun text(t: String, sizeSp: Float = 15f, color: Int = Palette.text,
             font: Typeface = fontRegular): TextView = TextView(ctx).apply {
        text = t
        textSize = sizeSp * textScale
        setTextColor(color)
        typeface = font
        setLineSpacing(0f, 1.15f)
    }

    fun title(t: String, sizeSp: Float = 24f): TextView = text(t, sizeSp, Palette.text, fontMedium)

    fun section(t: String): TextView = text(t.uppercase(), 12f, Palette.muted, fontMedium).apply {
        letterSpacing = 0.08f
    }

    fun badge(t: String, color: Int): TextView = text(t, 12f, color, fontMedium).apply {
        background = rounded(Palette.alpha(color, 0.14f), 12f)
        setPadding(dp(10), dp(4), dp(10), dp(4))
    }

    // ---------- kontroller ----------

    fun pill(t: String, style: PillButton.Style = PillButton.Style.PRIMARY, icon: Icon? = null,
             onClick: () -> Unit): PillButton =
        PillButton(ctx, this, style).apply {
            text = t
            if (icon != null) setIcon(icon)
            setOnClickListener { onClick() }
        }

    fun icon(icon: Icon, color: Int = Palette.text, sizeDp: Int = 24, filled: Boolean = false): ImageView =
        ImageView(ctx).apply {
            setImageDrawable(IconDrawable(icon, color, dp(sizeDp), filled))
        }

    /** Yuvarlak arka planli ikon (avatar / buton) */
    fun iconCircle(icon: Icon, fg: Int, bg: Int, sizeDp: Int = 44, iconDp: Int = 22,
                   filled: Boolean = false, onClick: (() -> Unit)? = null): FrameLayout =
        FrameLayout(ctx).apply {
            background = if (onClick != null) ripple(GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(bg)
            }) else GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(bg) }
            addView(icon(icon, fg, iconDp, filled), FrameLayout.LayoutParams(dp(iconDp), dp(iconDp), Gravity.CENTER))
            minimumWidth = dp(sizeDp)
            minimumHeight = dp(sizeDp)
            if (onClick != null) {
                isClickable = true
                setOnClickListener { onClick() }
            }
        }

    fun field(hint: String, type: Int = InputType.TYPE_CLASS_TEXT, multiLine: Boolean = false): EditText =
        EditText(ctx).apply {
            this.hint = hint
            setHintTextColor(Palette.faint)
            setTextColor(Palette.text)
            textSize = 16f * textScale
            typeface = fontRegular
            inputType = if (multiLine) (type or InputType.TYPE_TEXT_FLAG_MULTI_LINE) else type
            if (!multiLine) setSingleLine(true) else minLines = 2
            background = rounded(Palette.surface2, 16f, Palette.stroke)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            minHeight = dp(52)
        }

    fun fieldLabel(t: String): TextView = text(t, 13f, Palette.muted, fontMedium)

    /** Ust bar: geri, baslik/alt baslik, sagda eylemler */
    fun topBar(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null,
               actions: List<Pair<Icon, () -> Unit>> = emptyList(), right: View? = null): LinearLayout =
        hbox().apply {
            if (onBack != null) {
                addView(iconCircle(Icon.BACK, Palette.text, Palette.surface2, 44, 22, false, onBack),
                    lp(dp(44), dp(44), end = 12))
            }
            val tv = vbox()
            tv.addView(text(title, 20f, Palette.text, fontMedium))
            if (subtitle != null) tv.addView(text(subtitle, 13f, Palette.muted))
            addView(tv, lp(0, WRAP_CONTENT, 1f))
            if (right != null) addView(right, lp(WRAP_CONTENT, WRAP_CONTENT, start = 8))
            for ((ic, fn) in actions) {
                addView(iconCircle(ic, Palette.text, Palette.surface2, 44, 20, false, fn),
                    lp(dp(44), dp(44), start = 8))
            }
        }

    /** 1 Hasta – 2 Kayit – 3 Sonuc adim gostergesi */
    fun stepper(active: Int): LinearLayout = hbox().apply {
        val names = arrayOf("Hasta", "Kayıt", "Sonuç")
        for (i in names.indices) {
            val on = i <= active
            val dot = text("${i + 1}", 12f, if (on) Palette.onAccent else Palette.muted, fontMedium).apply {
                gravity = Gravity.CENTER
                background = if (i == active) gradient(Palette.accent, Palette.accent2, 12f)
                else if (on) rounded(Palette.accent, 12f) else rounded(Palette.surface2, 12f, Palette.stroke)
            }
            addView(dot, lp(dp(24), dp(24)))
            addView(text(names[i], 13f, if (i == active) Palette.text else Palette.muted,
                if (i == active) fontMedium else fontRegular), lp(WRAP_CONTENT, WRAP_CONTENT, start = 6, end = 6))
            if (i < names.size - 1) {
                addView(View(ctx).apply {
                    setBackgroundColor(if (i < active) Palette.accent else Palette.surface3)
                }, lp(0, dp(2), 1f, end = 6))
            }
        }
    }
}
