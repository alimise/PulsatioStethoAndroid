package com.pulsatio.stetho

import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Calendar

// =====================================================================
// Ortak parcalar
// =====================================================================

fun Screen.smallPill(t: String, style: PillButton.Style, icon: Icon? = null, onClick: () -> Unit): PillButton =
    ui.pill(t, style, icon, onClick).apply {
        minHeight = ui.dp(42)
        minimumHeight = ui.dp(42)
        textSize = 14f * ui.textScale
        setPadding(ui.dp(16), 0, ui.dp(16), 0)
    }

/** Baglanti durumu cipi (renkli nokta + metin) */
fun Screen.connChip(): TextView = ui.text("", 12f, Palette.muted, ui.fontMedium).apply {
    setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6))
    gravity = Gravity.CENTER_VERTICAL
    isClickable = true
    setOnClickListener { if (!app.connected) app.connect() }
    updateConnChip(this)
}

fun Screen.updateConnChip(tv: TextView) {
    val c = app.connectionColor()
    val dot = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) }
    dot.setBounds(0, 0, ui.dp(8), ui.dp(8))
    tv.setCompoundDrawables(dot, null, null, null)
    tv.compoundDrawablePadding = ui.dp(8)
    tv.text = app.connectionText()
    tv.setTextColor(if (app.connected) Palette.text else Palette.muted)
    tv.background = ui.ripple(ui.rounded(Palette.alpha(c, 0.12f), 16f))
}

fun Screen.avatar(s: Session, sizeDp: Int = 46): TextView {
    val initials = (if (s.name.isNotBlank()) s.name.split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }
    else s.patientId.take(2)).uppercase()
    return ui.text(initials, 15f, Palette.onAccent, ui.fontMedium).apply {
        gravity = Gravity.CENTER
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Palette.accent, Palette.accent2)).apply {
            shape = GradientDrawable.OVAL
        }
        minWidth = ui.dp(sizeDp)
        minHeight = ui.dp(sizeDp)
    }
}

fun qualityColor(q: Int?): Int = when {
    q == null -> Palette.faint
    q >= 70 -> Palette.accent
    q >= 45 -> Palette.amber
    else -> Palette.coral
}

/** Muayene karti (ana sayfa ve kayitlar listesi) */
fun Screen.sessionCard(s: Session, onClick: () -> Unit): View {
    val card = ui.hbox().apply {
        background = ui.ripple(ui.rounded(Palette.surface, 22f, Palette.stroke))
        setPadding(ui.dp(14), ui.dp(14), ui.dp(16), ui.dp(14))
        isClickable = true
        setOnClickListener { onClick() }
    }
    card.addView(avatar(s), ui.lp(ui.dp(46), ui.dp(46), end = 14))
    val mid = ui.vbox()
    mid.addView(ui.text(if (s.name.isNotBlank()) s.name else "Hasta ${s.patientId}", 16f, Palette.text, ui.fontMedium))
    val n = s.doneSites().size
    mid.addView(ui.text("ID ${s.patientId} · ${s.dateText()} · $n/5 nokta", 12f, Palette.muted))
    card.addView(mid, ui.lp(0, WRAP_CONTENT, 1f))
    val right = ui.vbox().apply { gravity = Gravity.END }
    val hr = s.medianHr()
    right.addView(ui.text(if (hr != null) "♥ $hr" else "♥ --", 16f, Palette.text, ui.fontMedium).apply { gravity = Gravity.END })
    val q = s.meanQuality()
    right.addView(ui.badge(when {
        q == null -> "kayıt yok"
        q >= 70 -> "iyi sinyal"
        q >= 45 -> "orta sinyal"
        else -> "zayıf sinyal"
    }, qualityColor(q)), ui.lp(WRAP_CONTENT, WRAP_CONTENT, top = 4))
    card.addView(right, ui.lp(WRAP_CONTENT, WRAP_CONTENT, start = 8))
    return card
}

fun Screen.statTile(value: String, label: String, color: Int = Palette.text): LinearLayout = ui.card(14).apply {
    addView(ui.text(value, 22f, color, ui.fontLight))
    addView(ui.text(label, 12f, Palette.muted), ui.lp(top = 2))
}

fun Screen.emptyState(icon: Icon, title: String, sub: String): View = ui.card(24).apply {
    gravity = Gravity.CENTER_HORIZONTAL
    addView(ui.iconCircle(icon, Palette.muted, Palette.surface2, 56, 26), ui.lp(ui.dp(56), ui.dp(56), bottom = 12))
    addView(ui.text(title, 16f, Palette.text, ui.fontMedium).apply { gravity = Gravity.CENTER })
    addView(ui.text(sub, 13f, Palette.muted).apply { gravity = Gravity.CENTER }, ui.lp(top = 4))
}

// =====================================================================
// ANA SAYFA
// =====================================================================

class HomeScreen(app: MainActivity) : Screen(app) {
    override val navTab = 0

    private lateinit var devIcon: android.widget.FrameLayout
    private lateinit var devStatus: TextView
    private lateinit var devBtn: PillButton
    private var tick = 0

    private fun greeting(): String {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (h) {
            in 5..11 -> "Günaydın"
            in 12..17 -> "İyi günler"
            else -> "İyi akşamlar"
        }
    }

    override fun build(): View {
        val pad = app.pagePad()
        val sessions = app.store.list()

        val header = ui.hbox().apply {
            val t = ui.vbox()
            t.addView(ui.text(greeting(), 14f, Palette.muted))
            t.addView(ui.text("Pulsatio", 30f, Palette.text, ui.fontMedium))
            addView(t, ui.lp(0, WRAP_CONTENT, 1f))
            addView(ui.iconCircle(Icon.STETHO, Palette.accent, Palette.alpha(Palette.accent, 0.12f), 50, 26),
                ui.lp(ui.dp(50), ui.dp(50)))
        }

        val left = ui.vbox().apply {
            addView(header, ui.lp(bottom = 20))
            addView(deviceCard(), ui.lp(bottom = 14))
            addView(hero(), ui.lp(bottom = 16))
            addView(statsRow(sessions), ui.lp(bottom = 20))
        }
        val recent = ui.vbox().apply {
            val h = ui.hbox()
            h.addView(ui.section("Son muayeneler"), ui.lp(0, WRAP_CONTENT, 1f))
            if (sessions.isNotEmpty()) {
                h.addView(ui.text("Tümü  ›", 13f, Palette.accent, ui.fontMedium).apply {
                    isClickable = true
                    setPadding(ui.dp(8), ui.dp(6), 0, ui.dp(6))
                    setOnClickListener { app.setRoot(HistoryScreen(app)) }
                })
            }
            addView(h, ui.lp(bottom = 10))
            if (sessions.isEmpty()) {
                addView(emptyState(Icon.HEART, "Henüz muayene yok",
                    "Yeni muayene başlatın; kayıtlar ve sonuçlar burada listelenir."))
            } else {
                for (s in sessions.take(if (app.isWide()) 7 else 4)) {
                    addView(sessionCard(s) { app.push(ResultScreen(app, s, false)) }, ui.lp(bottom = 10))
                }
            }
        }

        val body: View = if (app.isWide()) {
            ui.hbox().apply {
                gravity = Gravity.TOP
                setPadding(pad, pad, pad, app.navSpace())
                addView(left, ui.lp(0, WRAP_CONTENT, 1f, end = 20))
                addView(recent, ui.lp(0, WRAP_CONTENT, 1f, top = 70))
            }
        } else {
            ui.vbox().apply {
                setPadding(pad, pad, pad, app.navSpace())
                addView(left)
                addView(recent)
            }
        }
        refreshDevice()
        return ScrollView(app).apply { isVerticalScrollBarEnabled = false; addView(body) }
    }

    private fun deviceCard(): View = ui.card(14).apply {
        val row = ui.hbox()
        devIcon = ui.iconCircle(Icon.BLUETOOTH, Palette.accent, Palette.alpha(Palette.accent, 0.12f), 46, 22)
        row.addView(devIcon, ui.lp(ui.dp(46), ui.dp(46), end = 14))
        val t = ui.vbox()
        t.addView(ui.text("Dijital steteskop", 16f, Palette.text, ui.fontMedium))
        devStatus = ui.text("", 13f, Palette.muted)
        t.addView(devStatus)
        row.addView(t, ui.lp(0, WRAP_CONTENT, 1f))
        devBtn = smallPill("Bağlan", PillButton.Style.PRIMARY) { app.toggleConnection() }
        row.addView(devBtn, ui.lp(WRAP_CONTENT, WRAP_CONTENT, start = 8))
        addView(row)
    }

    private fun refreshDevice() {
        if (!::devStatus.isInitialized) return
        devStatus.text = when {
            app.connected -> "Bağlı · MTU ${app.mtu}" + if (app.mtu < 175) " (düşük)" else ""
            app.wantConnected -> "Aranıyor…"
            else -> "Bağlı değil"
        }
        devStatus.setTextColor(app.connectionColor())
        devBtn.text = if (app.wantConnected) "Kes" else "Bağlan"
        devBtn.style = if (app.wantConnected) PillButton.Style.GHOST else PillButton.Style.PRIMARY
        devIcon.alpha = if (app.connected) 1f else 0.6f
    }

    private fun hero(): View = ui.vbox().apply {
        background = ui.ripple(ui.gradient(Palette.accent, Palette.accent2, 28f), 0x33FFFFFF)
        setPadding(ui.dp(22), ui.dp(22), ui.dp(22), ui.dp(20))
        isClickable = true
        setOnClickListener { app.push(PatientScreen(app)) }
        val top = ui.hbox()
        val t = ui.vbox()
        t.addView(ui.text("Yeni muayene", 24f, Palette.onAccent, ui.fontMedium))
        t.addView(ui.text("Hasta bilgisini girin, odak noktalarında kayıt alın, analiz sonucunu görün.",
            14f, Palette.alpha(Palette.onAccent, 0.75f)), ui.lp(top = 4))
        top.addView(t, ui.lp(0, WRAP_CONTENT, 1f))
        top.addView(ui.iconCircle(Icon.HEART, Palette.coral, 0xF2FFFFFF.toInt(), 58, 30, true),
            ui.lp(ui.dp(58), ui.dp(58), start = 12))
        addView(top)
        addView(ui.text("Başla  →", 15f, Palette.onAccent, ui.fontMedium).apply {
            background = ui.rounded(0x26FFFFFF, 18f)
            setPadding(ui.dp(16), ui.dp(9), ui.dp(16), ui.dp(9))
        }, ui.lp(WRAP_CONTENT, WRAP_CONTENT, top = 16))
    }

    private fun statsRow(sessions: List<Session>): View = ui.hbox().apply {
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
        }.timeInMillis
        val todayRec = sessions.filter { it.createdAt >= today }.sumOf { it.recordings.size }
        val patients = sessions.map { it.patientId }.distinct().size
        val lastHr = sessions.firstOrNull { it.medianHr() != null }?.medianHr()
        addView(statTile("$todayRec", "bugünkü kayıt"), ui.lp(0, WRAP_CONTENT, 1f, end = 6))
        addView(statTile("$patients", "hasta"), ui.lp(0, WRAP_CONTENT, 1f, start = 3, end = 3))
        addView(statTile(if (lastHr != null) "$lastHr" else "--", "son nabız", Palette.coral), ui.lp(0, WRAP_CONTENT, 1f, start = 6))
    }

    override fun onConnectionChanged() = refreshDevice()

    override fun onTick() {
        if (++tick % 12 == 0) refreshDevice()
    }
}

// =====================================================================
// HASTA BILGISI
// =====================================================================

class PatientScreen(app: MainActivity) : Screen(app) {
    private lateinit var idField: EditText
    private lateinit var nameField: EditText
    private lateinit var ageField: EditText
    private lateinit var noteField: EditText
    private lateinit var sexPill: SegmentedPill
    private lateinit var cta: PillButton
    private lateinit var chip: TextView
    private val sexes = arrayOf("Kadın", "Erkek", "Belirtilmedi")
    private var sexIdx = 2
    // dondurmede degerleri koru
    private var vId = ""
    private var vName = ""
    private var vAge = ""
    private var vNote = ""

    override fun build(): View {
        val pad = app.pagePad()
        chip = connChip()
        val top = ui.topBar("Yeni muayene", "Hasta bilgileri", onBack = { app.pop() }, right = chip)

        val form = ui.card(18).apply {
            addView(ui.fieldLabel("Hasta ID *"), ui.lp(bottom = 6))
            idField = ui.field("ör. 2026-00125", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
            idField.setText(vId)
            addView(idField, ui.lp(bottom = 14))
            addView(ui.fieldLabel("Ad Soyad"), ui.lp(bottom = 6))
            nameField = ui.field("isteğe bağlı", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
            nameField.setText(vName)
            addView(nameField, ui.lp(bottom = 14))
            val row = ui.hbox()
            val ageBox = ui.vbox()
            ageBox.addView(ui.fieldLabel("Yaş"), ui.lp(bottom = 6))
            ageField = ui.field("--", InputType.TYPE_CLASS_NUMBER)
            ageField.setText(vAge)
            ageBox.addView(ageField)
            row.addView(ageBox, ui.lp(ui.dp(96), WRAP_CONTENT, end = 12))
            val sexBox = ui.vbox()
            sexBox.addView(ui.fieldLabel("Cinsiyet"), ui.lp(bottom = 6))
            sexPill = SegmentedPill(app, ui, sexes, sexIdx) { sexIdx = it }
            sexBox.addView(sexPill, ui.lp(bottom = 3, top = 3))
            row.addView(sexBox, ui.lp(0, WRAP_CONTENT, 1f))
            addView(row, ui.lp(bottom = 14))
            addView(ui.fieldLabel("Not / şikâyet"), ui.lp(bottom = 6))
            noteField = ui.field("ör. efor dispnesi, üfürüm takibi", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, true)
            noteField.setText(vNote)
            addView(noteField)
        }

        val recent = app.store.recentPatients()
        val recentBox = ui.vbox()
        if (recent.isNotEmpty()) {
            recentBox.addView(ui.section("Son hastalar"), ui.lp(bottom = 8))
            val chips = ui.hbox()
            for (s in recent) {
                chips.addView(ui.text(if (s.name.isNotBlank()) "${s.patientId} · ${s.name}" else s.patientId,
                    13f, Palette.text, ui.fontMedium).apply {
                    background = ui.ripple(ui.rounded(Palette.surface2, 16f, Palette.stroke))
                    setPadding(ui.dp(14), ui.dp(9), ui.dp(14), ui.dp(9))
                    isClickable = true
                    setOnClickListener {
                        idField.setText(s.patientId)
                        nameField.setText(s.name)
                        ageField.setText(s.age)
                        val i = sexes.indexOf(s.sex)
                        if (i >= 0) { sexIdx = i; sexPill.select(i) }
                    }
                }, ui.lp(WRAP_CONTENT, WRAP_CONTENT, end = 8))
            }
            recentBox.addView(HorizontalScrollView(app).apply {
                isHorizontalScrollBarEnabled = false
                addView(chips)
            })
        }

        cta = ui.pill("Kayda başla", PillButton.Style.PRIMARY, Icon.STETHO) { start() }
        idField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { cta.isEnabled = !s.isNullOrBlank() }
        })
        cta.isEnabled = vId.isNotBlank()

        val steps = ui.card(18).apply {
            addView(ui.section("Muayene akışı"), ui.lp(bottom = 12))
            val items = listOf(
                Triple(Icon.PERSON, "Hasta", "Kimlik ve kısa not"),
                Triple(Icon.STETHO, "Kayıt", "Aort, Pulmoner, Erb, Triküspit, Mitral – her nokta için kısa kayıt"),
                Triple(Icon.ECG, "Sonuç", "Nabız, ritim, S1/S2, sinyal kalitesi ve uyarılar")
            )
            for ((ic, t, d) in items) {
                val r = ui.hbox().apply { gravity = Gravity.TOP }
                r.addView(ui.iconCircle(ic, Palette.accent, Palette.alpha(Palette.accent, 0.12f), 38, 18),
                    ui.lp(ui.dp(38), ui.dp(38), end = 12))
                val tv = ui.vbox()
                tv.addView(ui.text(t, 15f, Palette.text, ui.fontMedium))
                tv.addView(ui.text(d, 13f, Palette.muted))
                r.addView(tv, ui.lp(0, WRAP_CONTENT, 1f))
                addView(r, ui.lp(bottom = 12))
            }
        }

        val body: View = if (app.isWide()) {
            ui.vbox().apply {
                setPadding(pad, pad, pad, pad)
                addView(top, ui.lp(bottom = 14))
                addView(ui.stepper(0), ui.lp(bottom = 18))
                val cols = ui.hbox().apply { gravity = Gravity.TOP }
                val l = ui.vbox().apply {
                    addView(form, ui.lp(bottom = 14))
                    addView(recentBox, ui.lp(bottom = 18))
                    addView(cta)
                }
                cols.addView(l, ui.lp(0, WRAP_CONTENT, 1.3f, end = 20))
                cols.addView(steps, ui.lp(0, WRAP_CONTENT, 1f))
                addView(cols)
            }
        } else {
            ui.vbox().apply {
                setPadding(pad, pad, pad, pad)
                addView(top, ui.lp(bottom = 14))
                addView(ui.stepper(0), ui.lp(bottom = 18))
                addView(form, ui.lp(bottom = 14))
                addView(recentBox, ui.lp(bottom = 18))
                addView(cta, ui.lp(bottom = 18))
                addView(steps)
            }
        }
        return ScrollView(app).apply { isVerticalScrollBarEnabled = false; addView(body) }
    }

    private fun keep() {
        vId = idField.text.toString()
        vName = nameField.text.toString()
        vAge = ageField.text.toString()
        vNote = noteField.text.toString()
    }

    private fun start() {
        keep()
        if (vId.isBlank()) return
        val s = app.store.create(vId, vName, vAge, if (sexIdx == 2) "" else sexes[sexIdx], vNote)
        if (!app.connected && !app.wantConnected) app.connect()
        app.replace(ListenScreen(app, s))
    }

    override fun onHide() { if (::idField.isInitialized) keep() }
    override fun onConnectionChanged() { if (::chip.isInitialized) updateConnChip(chip) }
}

// =====================================================================
// KAYITLAR
// =====================================================================

class HistoryScreen(app: MainActivity) : Screen(app) {
    override val navTab = 1
    private var query = ""
    private lateinit var list: LinearLayout
    private var all: List<Session> = emptyList()

    override fun build(): View {
        val pad = app.pagePad()
        all = app.store.list()
        val col = ui.vbox().apply { setPadding(pad, pad, pad, app.navSpace()) }
        col.addView(ui.title("Kayıtlar", 28f))
        col.addView(ui.text("${all.size} muayene · ${all.map { it.patientId }.distinct().size} hasta", 13f, Palette.muted),
            ui.lp(bottom = 16))
        val search = ui.field("Hasta ID veya ad ile ara").apply {
            val d = IconDrawable(Icon.SEARCH, Palette.muted, ui.dp(20))
            d.setBounds(0, 0, ui.dp(20), ui.dp(20))
            setCompoundDrawables(d, null, null, null)
            compoundDrawablePadding = ui.dp(10)
            setText(query)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { query = s?.toString() ?: ""; fill() }
            })
        }
        col.addView(search, ui.lp(bottom = 16))
        list = ui.vbox()
        col.addView(list)
        fill()
        return ScrollView(app).apply { isVerticalScrollBarEnabled = false; addView(col) }
    }

    private fun fill() {
        list.removeAllViews()
        val q = query.trim().lowercase()
        val items = if (q.isEmpty()) all else all.filter {
            it.patientId.lowercase().contains(q) || it.name.lowercase().contains(q)
        }
        if (items.isEmpty()) {
            list.addView(emptyState(Icon.SEARCH, if (all.isEmpty()) "Henüz kayıt yok" else "Sonuç bulunamadı",
                if (all.isEmpty()) "Ana sayfadan yeni muayene başlatın." else "Farklı bir ID deneyin."))
            return
        }
        if (app.isWide()) {
            // tablet: iki sutun
            var row: LinearLayout? = null
            items.forEachIndexed { i, s ->
                if (i % 2 == 0) {
                    row = ui.hbox().apply { gravity = Gravity.TOP }
                    list.addView(row, ui.lp(bottom = 10))
                }
                row?.addView(sessionCard(s) { app.push(ResultScreen(app, s, false)) },
                    ui.lp(0, WRAP_CONTENT, 1f, start = if (i % 2 == 1) 5 else 0, end = if (i % 2 == 0) 5 else 0))
            }
            if (items.size % 2 == 1) row?.addView(View(app), ui.lp(0, 1, 1f, start = 5))
        } else {
            for (s in items) list.addView(sessionCard(s) { app.push(ResultScreen(app, s, false)) }, ui.lp(bottom = 10))
        }
    }
}
