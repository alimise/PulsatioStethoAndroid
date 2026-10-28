package com.pulsatio.stetho

import android.app.AlertDialog
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.Locale

/**
 * Kayit sayfasi: govde haritasinda nokta sec -> kaydet (otomatik durur) ->
 * analiz -> nokta isaretlenir ve siradaki noktaya gecilir.
 */
class ListenScreen(app: MainActivity, private val session: Session) : Screen(app) {

    private var site: Site = Site.values().firstOrNull { it !in session.doneSites() } ?: Site.AORT
    private val durations = intArrayOf(10, 15, 30)
    private var durIdx = app.prefs.getInt("durIdx", 1)

    // kayit durumu
    private var recorder: WavRecorder? = null
    private var recName = ""
    private var recStart = 0L
    private var rx0 = 0L
    private var lost0 = 0L
    private var analyzing = false

    // gorunumler
    private lateinit var chip: TextView
    private lateinit var body: BodyMapView
    private lateinit var siteTitle: TextView
    private lateinit var siteHint: TextView
    private lateinit var progressText: TextView
    private lateinit var hrView: HeartRateView
    private lateinit var wave: LiveWaveView
    private lateinit var modePill: SegmentedPill
    private lateinit var durPill: SegmentedPill
    private lateinit var recBtn: RecordButton
    private lateinit var timer: TextView
    private lateinit var timerSub: TextView
    private lateinit var analyzeBtn: PillButton
    private lateinit var waveCaption: TextView
    private var tick = 0

    override fun build(): View {
        val pad = app.pagePad()
        chip = connChip()
        val top = ui.topBar("Kayıt", "Hasta ${session.patientId}" + if (session.name.isNotBlank()) " · ${session.name}" else "",
            onBack = { onBack() }, right = chip)

        body = BodyMapView(app, ui) { s -> selectSite(s) }.apply {
            selected = site
            done = session.doneSites()
        }
        siteTitle = ui.text("", 22f, Palette.text, ui.fontMedium)
        siteHint = ui.text("", 13f, Palette.muted)
        progressText = ui.text("", 12f, Palette.accent, ui.fontMedium)
        hrView = HeartRateView(app, ui)

        wave = LiveWaveView(app, ui, app.audio).apply { seconds = 3 }
        waveCaption = ui.text("canlı sinyal · 3 sn", 11f, Palette.faint)
        val waveCard = ui.card(10, Palette.surface).apply {
            addView(wave, ui.lp(MATCH_PARENT, 0, 1f))
            addView(waveCaption, ui.lp(top = 4, start = 6))
        }

        modePill = SegmentedPill(app, ui, arrayOf("Bell", "Diyafram", "Geniş"), app.modeIdx.coerceIn(0, 2)) { i ->
            app.setMode(i)
        }
        durPill = SegmentedPill(app, ui, durations.map { "$it sn" }.toTypedArray(), durIdx) { i ->
            durIdx = i
            app.prefs.edit().putInt("durIdx", i).apply()
            timerSub.text = "/ ${durations[durIdx]} sn"
        }

        recBtn = RecordButton(app, ui).apply { setOnClickListener { toggleRec() } }
        timer = ui.text("00:00", 30f, Palette.text, ui.fontLight)
        timerSub = ui.text("/ ${durations[durIdx]} sn", 12f, Palette.muted)
        analyzeBtn = smallPill("Sonuç", PillButton.Style.TONAL, Icon.ECG) { finish() }

        val controls = ui.hbox().apply {
            val l = ui.vbox()
            l.addView(timer)
            l.addView(timerSub)
            addView(l, ui.lp(0, WRAP_CONTENT, 1f))
            addView(recBtn, ui.lp(WRAP_CONTENT, WRAP_CONTENT))
            val r = ui.vbox().apply { gravity = Gravity.END }
            r.addView(analyzeBtn)
            addView(r, ui.lp(0, WRAP_CONTENT, 1f))
        }

        val siteInfo = ui.vbox().apply {
            addView(ui.section("Odak noktası"))
            addView(siteTitle, ui.lp(top = 4))
            addView(siteHint, ui.lp(top = 2))
            addView(progressText, ui.lp(top = 6))
        }

        val settings = ui.vbox().apply {
            addView(ui.section("Dinleme modu"), ui.lp(bottom = 6))
            addView(modePill, ui.lp(bottom = 12))
            addView(ui.section("Kayıt süresi"), ui.lp(bottom = 6))
            addView(durPill)
        }

        val root: View
        if (app.isWide()) {
            val left = ui.vbox().apply {
                addView(ui.card(12).apply { addView(body, ui.lp(MATCH_PARENT, ui.dp(380))) }, ui.lp(bottom = 14))
                addView(siteInfo, ui.lp(bottom = 16))
                addView(settings)
            }
            val right = ui.vbox().apply {
                addView(hrView, ui.lp(bottom = 8))
                addView(waveCard, ui.lp(MATCH_PARENT, 0, 1f, bottom = 14))
                addView(ui.card(16).apply { addView(controls) })
            }
            root = ui.vbox().apply {
                setPadding(pad, pad, pad, pad)
                addView(top, ui.lp(bottom = 12))
                addView(ui.stepper(1), ui.lp(bottom = 16))
                val cols = ui.hbox().apply { gravity = Gravity.TOP }
                cols.addView(ScrollView(app).apply { isVerticalScrollBarEnabled = false; addView(left) },
                    ui.lp(0, MATCH_PARENT, 0.44f, end = 20))
                cols.addView(right, ui.lp(0, MATCH_PARENT, 0.56f))
                addView(cols, ui.lp(MATCH_PARENT, 0, 1f))
            }
        } else {
            val head = ui.hbox().apply {
                gravity = Gravity.TOP
                addView(ui.card(6).apply { addView(body, ui.lp(MATCH_PARENT, ui.dp(200))) },
                    ui.lp(0, WRAP_CONTENT, 0.95f, end = 12))
                val r = ui.vbox()
                r.addView(siteInfo, ui.lp(bottom = 6))
                r.addView(hrView)
                addView(r, ui.lp(0, WRAP_CONTENT, 1.05f))
            }
            val col = ui.vbox().apply {
                setPadding(pad, pad, pad, pad)
                addView(top, ui.lp(bottom = 12))
                addView(ui.stepper(1), ui.lp(bottom = 14))
                addView(head, ui.lp(bottom = 12))
                addView(waveCard, ui.lp(MATCH_PARENT, ui.dp(170), bottom = 12))
                addView(ui.card(16).apply { addView(controls) }, ui.lp(bottom = 14))
                addView(settings)
            }
            root = ScrollView(app).apply { isVerticalScrollBarEnabled = false; addView(col) }
        }
        refreshSite()
        refreshRecUi()
        return root
    }

    // ------------------------------------------------------------------

    private fun selectSite(s: Site) {
        if (recorder != null) return
        site = s
        body.selected = s
        refreshSite()
    }

    private fun refreshSite() {
        siteTitle.text = site.label
        siteHint.text = site.hint
        val done = session.doneSites()
        progressText.text = "${done.size}/5 nokta kaydedildi" + if (site in done) " · bu nokta yeniden kaydedilir" else ""
        analyzeBtn.isEnabled = session.recordings.isNotEmpty() && recorder == null && !analyzing
    }

    private fun refreshRecUi() {
        val rec = recorder != null
        recBtn.recording = rec
        body.locked = rec
        modePill.isEnabled = !rec
        durPill.isEnabled = !rec
        analyzeBtn.isEnabled = !rec && !analyzing && session.recordings.isNotEmpty()
        if (!rec) {
            recBtn.progress = 0f
            timer.text = if (analyzing) "Analiz…" else "00:00"
        }
    }

    private fun toggleRec() {
        if (analyzing) return
        if (recorder != null) stopRec(true) else startRec()
    }

    private fun startRec() {
        if (!app.connected) {
            app.toast("Önce steteskopu bağlayın", Palette.amber, Icon.BLUETOOTH)
            if (!app.wantConnected) app.connect()
            return
        }
        app.startStreaming()
        recName = app.store.newRecordingFile(session, site)
        val r = WavRecorder(File(app.store.dir(session), recName))
        if (!r.start()) {
            app.toast("Kayıt dosyası açılamadı", Palette.coral, Icon.WARN)
            return
        }
        rx0 = app.audio.rxPackets
        lost0 = app.audio.lostPackets
        recStart = SystemClock.elapsedRealtime()
        recorder = r
        app.audio.recorder = r
        app.log("Kayıt başladı: ${site.name}")
        refreshRecUi()
    }

    private fun stopRec(keep: Boolean) {
        val r = recorder ?: return
        app.audio.recorder = null
        r.stop()
        recorder = null
        val f = File(app.store.dir(session), recName)
        val dur = r.samples / AudioEngine.FS.toFloat()
        if (!keep || dur < 4f) {
            f.delete()
            if (keep) app.toast("Kayıt çok kısa (en az 4 sn)", Palette.amber, Icon.WARN)
            refreshRecUi()
            return
        }
        val rx = app.audio.rxPackets - rx0
        val lost = app.audio.lostPackets - lost0
        val loss = if (rx + lost > 0) 100f * lost / (rx + lost) else 0f
        val s0 = site
        val name = recName
        val mode = app.modeNames[app.modeIdx]
        analyzing = true
        refreshRecUi()
        Thread {
            val a = try {
                PcgAnalyzer.analyze(WavIO.read(f), AudioEngine.FS, loss)
            } catch (e: Exception) {
                null
            }
            app.handler.post {
                analyzing = false
                val rec = Recording(s0, ChannelType.PCG, name, AudioEngine.FS, dur, mode, loss,
                    System.currentTimeMillis(), a)
                val old = session.put(rec)
                if (old != null && old.file != rec.file) File(app.store.dir(session), old.file).delete()
                app.store.save(session)
                if (::body.isInitialized) body.done = session.doneSites()
                val ok = (a?.quality ?: 0) >= 45
                app.toast("${s0.label} kaydedildi · ♥ ${a?.hr ?: "--"} · ${a?.qualityLabel ?: "?"}",
                    if (ok) Palette.accent else Palette.amber, if (ok) Icon.CHECK else Icon.WARN)
                val next = Site.values().firstOrNull { it !in session.doneSites() }
                if (next != null && ok) selectSite(next)
                else if (next == null) app.toast("Tüm noktalar tamam – Sonuç'a geçebilirsiniz", Palette.accent, Icon.ECG)
                refreshSite()
                refreshRecUi()
            }
        }.start()
    }

    private fun finish() {
        if (recorder != null) return
        app.replace(ResultScreen(app, session, true))
    }

    // ------------------------------------------------------------------

    override fun onShow() {
        if (app.connected) app.startStreaming()
        else if (!app.wantConnected) app.connect()
    }

    override fun onHide() {
        if (recorder != null) stopRec(false)
        app.stopStreaming()
    }

    override fun onConnectionChanged() {
        if (::chip.isInitialized) updateConnChip(chip)
        if (app.connected) app.startStreaming()
    }

    override fun onBack(): Boolean {
        if (recorder != null) {
            stopRec(false)
            app.toast("Kayıt iptal edildi", Palette.amber, Icon.WARN)
            return true
        }
        if (session.recordings.isEmpty()) {
            app.store.delete(session)
            app.popToRoot()
            return true
        }
        AlertDialog.Builder(app, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Muayeneyi bitir?")
            .setMessage("${session.recordings.size} nokta kaydedildi. Sonuçlara geçebilir veya kayda devam edebilirsiniz.")
            .setPositiveButton("Sonuçları gör") { _, _ -> finish() }
            .setNegativeButton("Kayda devam", null)
            .show()
        return true
    }

    override fun onTick() {
        tick++
        val live = app.connected && app.streaming
        wave.live = live
        wave.message = when {
            app.connected -> "Sinyal bekleniyor…"
            app.wantConnected -> "Steteskop aranıyor…"
            else -> "Steteskop bağlı değil – üstteki duruma dokunun"
        }
        wave.invalidate()
        if (tick % 25 == 0) {
            hrView.active = live
            hrView.hr = if (live) app.audio.estimateHr() else null
            updateConnChip(chip)
        }
        val r = recorder
        if (r != null) {
            val el = (SystemClock.elapsedRealtime() - recStart) / 1000f
            val total = durations[durIdx].toFloat()
            recBtn.progress = el / total
            timer.text = String.format(Locale.US, "%02d:%02d", el.toInt() / 60, el.toInt() % 60)
            if (el >= total) stopRec(true)
        }
    }
}
