package com.pulsatio.stetho

import android.app.AlertDialog
import android.content.Intent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import java.io.File

/** Muayene sonucu: ozet, uyarilar, nokta bazinda dalga formu + olcumler + dinleme */
class ResultScreen(app: MainActivity, private val session: Session, private val fromFlow: Boolean) : Screen(app) {

    private val player = WavPlayer()
    private var playingIdx = -1
    private val waveViews = ArrayList<RecordingWaveView>()
    private val playBtns = ArrayList<PillButton>()
    private val pcmCache = java.util.concurrent.ConcurrentHashMap<String, ShortArray>()

    private fun recs(): List<Recording> = session.recordings.sortedBy { it.site.ordinal }

    override fun build(): View {
        val pad = app.pagePad()
        waveViews.clear()
        playBtns.clear()
        val top = ui.topBar("Muayene sonucu", session.dateText(),
            onBack = { onBack() },
            actions = listOf(Pair(Icon.SHARE, { export() }), Pair(Icon.TRASH, { confirmDelete() })))

        val summary = ui.vbox().apply {
            addView(patientCard(), ui.lp(bottom = 12))
            addView(tiles(), ui.lp(bottom = 12))
            addView(findings(), ui.lp(bottom = 14))
            if (fromFlow) {
                addView(ui.pill("Muayeneyi bitir", PillButton.Style.PRIMARY, Icon.CHECK) { app.popToRoot() }, ui.lp(bottom = 10))
                if (session.doneSites().size < 5) {
                    addView(ui.pill("Eksik noktaları kaydet", PillButton.Style.GHOST, Icon.STETHO) {
                        app.replace(ListenScreen(app, session))
                    }, ui.lp(bottom = 10))
                }
            }
        }
        val list = ui.vbox().apply {
            addView(ui.section("Odak noktaları"), ui.lp(bottom = 10))
            val r = recs()
            if (r.isEmpty()) addView(emptyState(Icon.STETHO, "Kayıt yok", "Bu muayenede kaydedilmiş nokta bulunmuyor."))
            r.forEachIndexed { i, rec -> addView(recordingCard(i, rec), ui.lp(bottom = 12)) }
            addView(ui.text("Bu sonuçlar sinyal işleme tabanlı göstergelerdir; tanı yerine geçmez. " +
                "Klinik karar için hekim değerlendirmesi gerekir.", 11f, Palette.faint), ui.lp(top = 4))
        }

        val content: View = if (app.isWide()) {
            ui.vbox().apply {
                setPadding(pad, pad, pad, 0)
                addView(top, ui.lp(bottom = 12))
                if (fromFlow) addView(ui.stepper(2), ui.lp(bottom = 16))
                val cols = ui.hbox().apply { gravity = Gravity.TOP }
                cols.addView(ScrollView(app).apply { isVerticalScrollBarEnabled = false; addView(summary) },
                    ui.lp(0, MATCH_PARENT, 0.42f, end = 20))
                cols.addView(ScrollView(app).apply {
                    isVerticalScrollBarEnabled = false
                    addView(list.apply { setPadding(0, 0, 0, pad) })
                }, ui.lp(0, MATCH_PARENT, 0.58f))
                addView(cols, ui.lp(MATCH_PARENT, 0, 1f))
            }
        } else {
            ScrollView(app).apply {
                isVerticalScrollBarEnabled = false
                addView(ui.vbox().apply {
                    setPadding(pad, pad, pad, pad)
                    addView(top, ui.lp(bottom = 12))
                    if (fromFlow) addView(ui.stepper(2), ui.lp(bottom = 16))
                    addView(summary)
                    addView(list)
                })
            }
        }
        loadWaves()
        return content
    }

    private fun patientCard(): View = ui.card(16).apply {
        val row = ui.hbox()
        row.addView(avatar(session, 54), ui.lp(ui.dp(54), ui.dp(54), end = 14))
        val t = ui.vbox()
        t.addView(ui.text(if (session.name.isNotBlank()) session.name else "Hasta ${session.patientId}", 18f, Palette.text, ui.fontMedium))
        val extra = listOf("ID ${session.patientId}", session.age.takeIf { it.isNotBlank() }?.let { "$it yaş" }, session.sex.ifBlank { null })
            .filterNotNull().joinToString(" · ")
        t.addView(ui.text(extra, 13f, Palette.muted))
        row.addView(t, ui.lp(0, WRAP_CONTENT, 1f))
        addView(row)
        if (session.note.isNotBlank()) {
            addView(ui.text("“${session.note}”", 13f, Palette.muted).apply {
                background = ui.rounded(Palette.surface2, 14f)
                setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
            }, ui.lp(top = 12))
        }
    }

    private fun tiles(): View = ui.hbox().apply {
        val hr = session.medianHr()
        val rhythms = session.recordings.mapNotNull { it.analysis?.rhythm }.filter { it != "Belirsiz" }
        val rhythm = rhythms.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: "Belirsiz"
        val q = session.meanQuality()
        addView(statTile(if (hr != null) "$hr" else "--", "nabız (bpm)", Palette.coral), ui.lp(0, WRAP_CONTENT, 1f, end = 6))
        addView(statTile(rhythm, "ritim", if (rhythm == "Düzenli") Palette.accent else Palette.amber),
            ui.lp(0, WRAP_CONTENT, 1.3f, start = 3, end = 3))
        addView(statTile(if (q != null) "$q" else "--", "kalite / 100", qualityColor(q)), ui.lp(0, WRAP_CONTENT, 1f, start = 6))
    }

    private fun findings(): View = ui.card(16).apply {
        addView(ui.section("Bulgu göstergeleri"), ui.lp(bottom = 10))
        val items = ArrayList<Pair<Site, String>>()
        for (r in recs()) r.analysis?.flags?.forEach { items.add(Pair(r.site, it)) }
        if (items.isEmpty()) {
            val row = ui.hbox()
            row.addView(ui.iconCircle(Icon.CHECK, Palette.accent, Palette.alpha(Palette.accent, 0.12f), 34, 18),
                ui.lp(ui.dp(34), ui.dp(34), end = 12))
            row.addView(ui.text(if (session.recordings.isEmpty()) "Henüz analiz edilecek kayıt yok."
                else "Belirgin bir uyarı göstergesi yok.", 14f, Palette.text), ui.lp(0, WRAP_CONTENT, 1f))
            addView(row)
        } else {
            for ((site, msg) in items) {
                val row = ui.hbox().apply { gravity = Gravity.TOP }
                row.addView(ui.icon(Icon.WARN, Palette.amber, 18), ui.lp(ui.dp(18), ui.dp(18), top = 2, end = 10))
                val t = ui.vbox()
                t.addView(ui.text(site.label, 12f, Palette.amber, ui.fontMedium))
                t.addView(ui.text(msg, 14f, Palette.text))
                row.addView(t, ui.lp(0, WRAP_CONTENT, 1f))
                addView(row, ui.lp(bottom = 10))
            }
        }
    }

    private fun recordingCard(i: Int, r: Recording): View = ui.card(14).apply {
        val a = r.analysis
        val head = ui.hbox()
        head.addView(ui.text(r.site.short, 13f, Palette.onAccent, ui.fontMedium).apply {
            gravity = Gravity.CENTER
            background = ui.gradient(Palette.accent, Palette.accent2, 14f)
        }, ui.lp(ui.dp(28), ui.dp(28), end = 10))
        val t = ui.vbox()
        t.addView(ui.text(r.site.label, 16f, Palette.text, ui.fontMedium))
        t.addView(ui.text("${r.mode} · ${"%.0f".format(r.durationSec)} sn" +
            if (r.lossPct > 0.5f) " · kayıp %${"%.1f".format(r.lossPct)}" else "", 12f, Palette.muted))
        head.addView(t, ui.lp(0, WRAP_CONTENT, 1f))
        if (a != null) head.addView(ui.badge("${a.qualityLabel} · ${a.quality}", qualityColor(a.quality)))
        addView(head, ui.lp(bottom = 10))

        val wv = RecordingWaveView(app, ui)
        waveViews.add(wv)
        addView(wv, ui.lp(MATCH_PARENT, ui.dp(130)).apply { setMargins(0, 0, 0, ui.dp(6)) })
        val legend = ui.hbox()
        legend.addView(ui.text("● S1", 11f, Palette.s1, ui.fontMedium), ui.lp(WRAP_CONTENT, WRAP_CONTENT, end = 12))
        legend.addView(ui.text("● S2", 11f, Palette.s2, ui.fontMedium), ui.lp(WRAP_CONTENT, WRAP_CONTENT, end = 12))
        legend.addView(ui.text("kaydırarak gezinin", 11f, Palette.faint), ui.lp(0, WRAP_CONTENT, 1f))
        addView(legend, ui.lp(bottom = 12))

        if (a != null) {
            val grid = ui.hbox()
            fun metric(v: String, l: String, w: Float) = grid.addView(ui.vbox().apply {
                background = ui.rounded(Palette.surface2, 14f)
                setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
                addView(ui.text(v, 17f, Palette.text, ui.fontMedium))
                addView(ui.text(l, 11f, Palette.muted))
            }, ui.lp(0, WRAP_CONTENT, w, end = 6))
            metric(a.hr?.toString() ?: "--", "nabız", 1f)
            metric(a.systoleMs?.let { "$it" } ?: "--", "sistol ms", 1f)
            metric(a.diastoleMs?.let { "$it" } ?: "--", "diyastol ms", 1.1f)
            metric(a.rhythm, "ritim", 1.4f)
            addView(grid, ui.lp(bottom = 10))
            for (f in a.flags) {
                addView(ui.text("• $f", 13f, Palette.amber), ui.lp(bottom = 4))
            }
        } else {
            addView(ui.text("Analiz bulunamadı", 13f, Palette.muted), ui.lp(bottom = 8))
        }

        val pb = smallPill("Dinle", PillButton.Style.TONAL, Icon.PLAY) { togglePlay(i) }
        playBtns.add(pb)
        addView(pb, ui.lp(WRAP_CONTENT, WRAP_CONTENT, top = 6))
    }

    private fun loadWaves() {
        val list = recs()
        Thread {
            for ((i, r) in list.withIndex()) {
                val f = app.store.file(session, r)
                val pcm = pcmCache[r.file] ?: (if (f.exists()) WavIO.read(f) else ShortArray(0)).also { pcmCache[r.file] = it }
                app.handler.post {
                    if (i < waveViews.size) waveViews[i].setData(pcm, r.analysis?.s1Times ?: FloatArray(0), r.analysis?.s2Times ?: FloatArray(0))
                }
            }
        }.start()
    }

    private fun togglePlay(i: Int) {
        if (playingIdx == i) {
            player.stop()
            setPlaying(-1)
            return
        }
        val r = recs().getOrNull(i) ?: return
        val pcm = pcmCache[r.file] ?: return
        player.play(pcm, app.audio.gain) { app.handler.post { if (playingIdx == i) setPlaying(-1) } }
        setPlaying(i)
    }

    private fun setPlaying(i: Int) {
        playingIdx = i
        playBtns.forEachIndexed { k, b ->
            b.text = if (k == i) "Durdur" else "Dinle"
            b.setIcon(if (k == i) Icon.PAUSE else Icon.PLAY)
        }
        if (i < 0) waveViews.forEach { it.playheadSec = -1f }
    }

    private fun export() {
        app.toast("Dışa aktarılıyor…", Palette.accent2, Icon.SHARE)
        Thread {
            val uris = try { Exporter.export(app, app.store, session) } catch (e: Exception) { ArrayList() }
            app.handler.post {
                if (uris.isEmpty()) {
                    app.toast("Dışa aktarılamadı", Palette.coral, Icon.WARN)
                } else {
                    app.toast("Download/Pulsatio klasörüne kaydedildi", Palette.accent, Icon.CHECK)
                    try {
                        app.startActivity(Intent.createChooser(Exporter.shareIntent(uris), "Muayeneyi paylaş"))
                    } catch (_: Exception) { }
                }
            }
        }.start()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(app, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Muayene silinsin mi?")
            .setMessage("Hasta ${session.patientId} için tüm kayıtlar ve analizler silinecek.")
            .setPositiveButton("Sil") { _, _ ->
                player.stop()
                app.store.delete(session)
                app.toast("Muayene silindi", Palette.coral, Icon.TRASH)
                if (fromFlow) app.popToRoot() else app.pop()
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    override fun onBack(): Boolean {
        player.stop()
        if (fromFlow) app.popToRoot() else app.pop()
        return true
    }

    override fun onHide() {
        player.stop()
    }

    override fun onTick() {
        if (playingIdx >= 0 && playingIdx < waveViews.size) {
            waveViews[playingIdx].playheadSec = player.positionSec
        }
    }
}
