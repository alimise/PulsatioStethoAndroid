package com.pulsatio.stetho

import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import java.util.Locale

/** Cihaz & hata ayiklama: baglanti, canli istatistik, ESP ayarlari, ses, sensor kanallari, log */
class DeviceScreen(app: MainActivity) : Screen(app) {
    override val navTab = 2

    private lateinit var status: TextView
    private lateinit var statusSub: TextView
    private lateinit var connBtn: PillButton
    private lateinit var testBtn: PillButton
    private lateinit var wave: LiveWaveView
    private lateinit var stats: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var espView: TextView
    private var lastLogSize = -1
    private var tick = 0

    override fun build(): View {
        val pad = app.pagePad()
        val header = ui.vbox().apply {
            addView(ui.title("Cihaz", 28f))
            addView(ui.text("Bağlantı, ayarlar ve hata ayıklama", 13f, Palette.muted))
        }

        val conn = ui.card(16).apply {
            val row = ui.hbox()
            row.addView(ui.iconCircle(Icon.BLUETOOTH, Palette.accent, Palette.alpha(Palette.accent, 0.12f), 46, 22),
                ui.lp(ui.dp(46), ui.dp(46), end = 14))
            val t = ui.vbox()
            status = ui.text("", 17f, Palette.text, ui.fontMedium)
            statusSub = ui.text("", 12f, Palette.muted)
            t.addView(status)
            t.addView(statusSub)
            row.addView(t, ui.lp(0, WRAP_CONTENT, 1f))
            addView(row, ui.lp(bottom = 12))
            val btns = ui.hbox()
            connBtn = smallPill("Bağlan", PillButton.Style.PRIMARY) { app.toggleConnection() }
            testBtn = smallPill("Test dinleme", PillButton.Style.TONAL, Icon.PLAY) {
                if (app.streaming) app.stopStreaming() else app.startStreaming()
                refresh()
            }
            btns.addView(connBtn, ui.lp(0, WRAP_CONTENT, 1f, end = 6))
            btns.addView(testBtn, ui.lp(0, WRAP_CONTENT, 1f, start = 6))
            addView(btns)
        }

        val live = ui.card(14).apply {
            addView(ui.section("Canlı akış"), ui.lp(bottom = 8))
            wave = LiveWaveView(app, ui, app.audio).apply { seconds = 2 }
            addView(wave, ui.lp(MATCH_PARENT, ui.dp(110), bottom = 10))
            stats = ui.text("", 12f, Palette.muted, ui.fontMono)
            addView(stats)
        }

        val esp = ui.card(16).apply {
            addView(ui.section("ESP ayarları"), ui.lp(bottom = 10))
            addView(ui.fieldLabel("Dinleme modu (filtre bandı)"), ui.lp(bottom = 6))
            addView(SegmentedPill(app, ui, app.modeNames, app.modeIdx) { app.setMode(it) }, ui.lp(bottom = 4))
            addView(ui.text("Bell 20–200 · Diyafram 20–1000 · Geniş 20–1600 · Akciğer 100–1600 Hz", 11f, Palette.faint), ui.lp(bottom = 12))
            addView(ui.fieldLabel("Gürültü profili"), ui.lp(bottom = 6))
            addView(SegmentedPill(app, ui, arrayOf("Comfort", "Diagnostic"), app.profileIdx) { app.setProfile(it) }, ui.lp(bottom = 4))
            addView(ui.text("Comfort: sürtünme bastırma + gürültü kapısı · Diagnostic: ham, bastırmasız", 11f, Palette.faint), ui.lp(bottom = 12))
            addView(ui.fieldLabel("BLE ses kaynağı"), ui.lp(bottom = 6))
            addView(SegmentedPill(app, ui, arrayOf("İşlenmiş", "Ham", "Odaklı"), app.sourceIdx) { app.setSource(it) }, ui.lp(bottom = 4))
            addView(ui.text("Analiz ve kayıt için Ham önerilir", 11f, Palette.faint), ui.lp(bottom = 12))
            addView(ui.fieldLabel("Kulaklık odağı (ESP segmentasyonu)"), ui.lp(bottom = 6))
            addView(SegmentedPill(app, ui, arrayOf("Tümü", "S1", "S2", "S3", "S4", "Sis", "Diy"), app.focusIdx) { app.setFocus(it) })
        }

        val audio = ui.card(16).apply {
            addView(ui.section("Ses ve kayıt"), ui.lp(bottom = 10))
            val gl = ui.text("Telefon ses kazancı  x${app.audio.gain.toInt()}", 13f, Palette.muted, ui.fontMedium)
            addView(gl, ui.lp(bottom = 4))
            addView(SeekBar(app).apply {
                max = 29
                progress = app.audio.gain.toInt() - 1
                progressTintList = ColorStateList.valueOf(Palette.accent)
                thumbTintList = ColorStateList.valueOf(Palette.accent)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        app.audio.gain = (p + 1).toFloat()
                        gl.text = "Telefon ses kazancı  x${p + 1}"
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) { app.saveAudioPrefs() }
                })
            }, ui.lp(bottom = 8))
            addView(switchRow("Hoparlör modu (sanal bas)", "Telefon hoparlöründe açın, kulaklıkta kapatın",
                app.audio.speakerMode) { app.audio.speakerMode = it; app.saveAudioPrefs() })
        }

        val sensors = ui.card(16).apply {
            addView(ui.section("Sensör kanalları"), ui.lp(bottom = 10))
            addView(sensorRow(Icon.STETHO, "Kalp sesi (PCG)", "IM72D128 · 8 kHz · BLE", "Aktif", Palette.accent))
            addView(sensorRow(Icon.ECG, "EKG", "Muayeneye ek kanal olarak eklenecek (ör. ADS1292R)", "Yakında", Palette.violet))
            addView(sensorRow(Icon.HEART, "SpO₂ / nabız oksimetre", "Planlanan kanal", "Plan", Palette.faint))
        }

        val logs = ui.card(14).apply {
            addView(ui.section("Kayıt defteri"), ui.lp(bottom = 8))
            logView = ui.text("", 11f, Palette.muted, ui.fontMono)
            logScroll = ScrollView(app).apply {
                background = ui.rounded(Palette.surface2, 14f)
                setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8))
                addView(logView)
            }
            addView(logScroll, ui.lp(MATCH_PARENT, ui.dp(220), bottom = 10))
            addView(ui.fieldLabel("ESP durum satırı"), ui.lp(bottom = 4))
            espView = ui.text("", 11f, Palette.faint, ui.fontMono)
            addView(espView)
        }

        val about = ui.text("Pulsatio Stetho 0.2 · prototip / mühendislik sürümü – tanı aracı değildir", 11f, Palette.faint)

        val body: View = if (app.isWide()) {
            ui.vbox().apply {
                setPadding(pad, pad, pad, app.navSpace())
                addView(header, ui.lp(bottom = 16))
                val cols = ui.hbox().apply { gravity = Gravity.TOP }
                val l = ui.vbox().apply {
                    addView(conn, ui.lp(bottom = 12)); addView(live, ui.lp(bottom = 12)); addView(logs)
                }
                val r = ui.vbox().apply {
                    addView(esp, ui.lp(bottom = 12)); addView(audio, ui.lp(bottom = 12)); addView(sensors, ui.lp(bottom = 12)); addView(about)
                }
                cols.addView(l, ui.lp(0, WRAP_CONTENT, 1f, end = 10))
                cols.addView(r, ui.lp(0, WRAP_CONTENT, 1f, start = 10))
                addView(cols)
            }
        } else {
            ui.vbox().apply {
                setPadding(pad, pad, pad, app.navSpace())
                addView(header, ui.lp(bottom = 16))
                for (v in listOf(conn, live, esp, audio, sensors, logs)) addView(v, ui.lp(bottom = 12))
                addView(about)
            }
        }
        refresh()
        return ScrollView(app).apply { isVerticalScrollBarEnabled = false; addView(body) }
    }

    private fun switchRow(title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit): View = ui.hbox().apply {
        val t = ui.vbox()
        t.addView(ui.text(title, 14f, Palette.text, ui.fontMedium))
        t.addView(ui.text(sub, 11f, Palette.faint))
        addView(t, ui.lp(0, WRAP_CONTENT, 1f))
        addView(Switch(app).apply {
            isChecked = on
            thumbTintList = ColorStateList.valueOf(Palette.accent)
            trackTintList = ColorStateList.valueOf(Palette.alpha(Palette.accent, 0.35f))
            setOnCheckedChangeListener { _, c -> onChange(c) }
        })
    }

    private fun sensorRow(icon: Icon, title: String, sub: String, badge: String, color: Int): View = ui.hbox().apply {
        setPadding(0, ui.dp(6), 0, ui.dp(6))
        addView(ui.iconCircle(icon, color, Palette.alpha(color, 0.12f), 38, 18), ui.lp(ui.dp(38), ui.dp(38), end = 12))
        val t = ui.vbox()
        t.addView(ui.text(title, 14f, Palette.text, ui.fontMedium))
        t.addView(ui.text(sub, 11f, Palette.muted))
        addView(t, ui.lp(0, WRAP_CONTENT, 1f))
        addView(ui.badge(badge, color))
    }

    private fun refresh() {
        if (!::status.isInitialized) return
        status.text = app.connectionText()
        status.setTextColor(app.connectionColor())
        val pkt = app.audio.lastPacketSamples
        statusSub.text = when {
            app.connected -> "Paket: ${if (pkt > 0) "$pkt örnek" else "-"} · " +
                (if (app.mtu >= 495) "en iyi aktarım" else if (app.mtu >= 175) "normal aktarım" else "düşük MTU (ADPCM)")
            app.wantConnected -> "Pulsatio-Stetho-Test aranıyor"
            else -> "Bağlanmak için dokunun"
        }
        connBtn.text = if (app.wantConnected) "Bağlantıyı kes" else "Bağlan"
        connBtn.style = if (app.wantConnected) PillButton.Style.GHOST else PillButton.Style.PRIMARY
        testBtn.isEnabled = app.connected
        testBtn.text = if (app.streaming) "Durdur" else "Test dinleme"
        testBtn.setIcon(if (app.streaming) Icon.PAUSE else Icon.PLAY)
    }

    override fun onConnectionChanged() = refresh()

    override fun onTick() {
        tick++
        wave.live = app.connected && app.streaming
        wave.message = if (app.connected) "Test dinlemeyi başlatın" else "Bağlı değil"
        wave.invalidate()
        if (tick % 6 == 0) {
            val a = app.audio
            val rx = a.rxPackets
            val lost = a.lostPackets
            val pct = if (rx + lost > 0) 100.0 * lost / (rx + lost) else 0.0
            stats.text = String.format(Locale.US,
                "MTU %-4d paket %-3d örnek  tampon %d ms\nalınan %-7d kayıp %d (%%%.1f)  bozuk %d\ngiriş tepe %.0f dBFS",
                app.mtu, a.lastPacketSamples, a.queuedMs(), rx, lost, pct, a.badPackets, a.peakDb)
            stats.setTextColor(if (pct > 5) Palette.amber else Palette.muted)
            if (app.logCount != lastLogSize) {
                lastLogSize = app.logCount
                logView.text = app.logLines.joinToString("\n")
                logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
            }
            espView.text = app.espStatus.ifEmpty { "–" }
            refresh()
        }
    }

    override fun onHide() {
        // test dinleme bu sayfaya ozel
        app.stopStreaming()
    }
}
