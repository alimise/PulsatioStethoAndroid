package com.pulsatio.stetho

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Telefon (dikey) ve tablet / yatay (iki sutun) icin uyarlanan arayuz.
 * Ekran dondugunde Activity yeniden olusturulmaz (manifest: configChanges);
 * sadece yerlesim yeniden kurulur, BLE baglantisi ve ses kesilmez.
 */
class MainActivity : Activity(), BleClient.Listener {

    // ---------------- renkler ----------------
    private val cBg = Color.rgb(11, 15, 20)
    private val cCard = Color.rgb(21, 27, 35)
    private val cBtn = Color.rgb(34, 42, 54)
    private val cAccent = Color.rgb(46, 196, 182)
    private val cAccentDark = Color.rgb(16, 74, 69)
    private val cDanger = Color.rgb(229, 72, 77)
    private val cWarn = Color.rgb(245, 166, 35)
    private val cText = Color.rgb(230, 234, 240)
    private val cMuted = Color.rgb(140, 150, 165)

    // ---------------- motorlar ----------------
    private lateinit var ble: BleClient
    private val audio = AudioEngine()
    private var recorder: WavRecorder? = null
    private val ui = Handler(Looper.getMainLooper())

    // ---------------- durum (yerlesim yeniden kurulunca korunur) ----------------
    private var wantConnected = false
    private var connected = false
    private var streaming = false
    private var mtu = 23
    private var modeIdx = 1          // 0 Bell, 1 Diyafram, 2 Akciger, 3 Genis
    private var profileIdx = 0       // 0 Comfort, 1 Diagnostic
    private var sourceIdx = 0        // 0 Islenmis, 1 Ham
    private var windowIdx = 1        // 2 / 3 / 6 sn
    private var showDetails = false
    private var recStartMs = 0L
    private var lastHr: Int? = null
    private var espStatus = ""
    private val logLines = ArrayDeque<String>()

    private val modeCmds = intArrayOf(0x01, 0x02, 0x03, 0x04)
    private val modeNames = arrayOf("Bell", "Diyafram", "Akciğer", "Geniş")
    private val profileCmds = intArrayOf(0x05, 0x06)
    private val sourceCmds = intArrayOf(0x13, 0x12)
    private val windowSecs = intArrayOf(2, 3, 6)

    // ---------------- gorunumler (her yerlesimde yeniden) ----------------
    private lateinit var tvChip: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnListen: Button
    private lateinit var btnRec: Button
    private lateinit var tvGain: TextView
    private lateinit var tvStats: TextView
    private lateinit var tvLog: TextView
    private lateinit var logBox: View
    private lateinit var logScroll: ScrollView
    private lateinit var waveView: WaveformView
    private var modeBtns: List<Button> = emptyList()
    private var profileBtns: List<Button> = emptyList()
    private var sourceBtns: List<Button> = emptyList()
    private var windowBtns: List<Button> = emptyList()

    private var scale = 1f
    private var tick = 0

    private val ticker = object : Runnable {
        override fun run() {
            waveView.invalidate()
            tick++
            if (tick % 6 == 0) refreshStats()
            if (tick % 25 == 0) refreshHr()
            ui.postDelayed(this, 40)
        }
    }

    // ==================================================================
    // Yasam dongusu
    // ==================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = cBg
        window.navigationBarColor = cBg
        ble = BleClient(this, this)
        audio.start()
        setContentView(buildUi())
        if (!hasPerms()) requestPermissions(neededPerms(), 1)
        log("Hazır. PC uygulamasını kapatın (ESP tek bağlantı kabul eder) ve Bağlan'a basın.")
        ui.post(ticker)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        setContentView(buildUi())      // yeni ekran boyutuna gore yerlesim
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        audio.recorder = null
        recorder?.stop()
        recorder = null
        ble.close()
        audio.stop()
        super.onDestroy()
    }

    // ==================================================================
    // Izinler
    // ==================================================================

    private fun neededPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasPerms(): Boolean =
        neededPerms().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasPerms()) {
            log("İzinler verildi")
            if (wantConnected) connect()
        } else {
            log("Bluetooth izni verilmedi – Ayarlar > Uygulamalar > Pulsatio Stetho > İzinler")
        }
    }

    // ==================================================================
    // Yardimci gorunum fabrikalari
    // ==================================================================

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()
    private fun dp(v: Int): Int = dp(v.toFloat())

    private fun rounded(color: Int, radiusDp: Float = 12f, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null) setStroke(dp(1.5f), strokeColor)
        }

    private fun styleButton(b: Button, fill: Int, textColor: Int = cText, stroke: Int? = null) {
        b.background = RippleDrawable(
            ColorStateList.valueOf(Color.argb(60, 255, 255, 255)),
            rounded(fill, 12f, stroke),
            null
        )
        b.setTextColor(textColor)
    }

    private fun button(text: String, sizeSp: Float = 15f, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = sizeSp * scale
        minHeight = dp(52)
        minimumHeight = dp(52)
        setPadding(dp(8), 0, dp(8), 0)
        styleButton(this, cBtn)
        setOnClickListener { onClick() }
    }

    private fun text(t: String, sizeSp: Float = 14f, color: Int = cText, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = t
            textSize = sizeSp * scale
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun lp(w: Int = MATCH_PARENT, h: Int = WRAP_CONTENT, weight: Float = 0f, bottom: Int = 10): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h, weight).apply { setMargins(0, 0, 0, dp(bottom)) }

    private fun vbox(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun hbox(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun card(title: String?): LinearLayout = vbox().apply {
        background = rounded(cCard, 16f)
        setPadding(dp(14), dp(12), dp(14), dp(14))
        if (title != null) addView(text(title, 13f, cMuted, true), lp(bottom = 8))
    }

    /** Secili olan vurgulanan dugme grubu */
    private fun segmented(labels: Array<String>, selected: Int, sizeSp: Float = 14f, onSelect: (Int) -> Unit): Pair<LinearLayout, List<Button>> {
        val row = hbox()
        val btns = ArrayList<Button>()
        labels.forEachIndexed { i, label ->
            val b = button(label, sizeSp) { onSelect(i) }
            btns.add(b)
            row.addView(b, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                setMargins(if (i == 0) 0 else dp(6), 0, 0, 0)
            })
        }
        paintSegmented(btns, selected)
        return Pair(row, btns)
    }

    private fun paintSegmented(btns: List<Button>, selected: Int) {
        btns.forEachIndexed { i, b ->
            if (i == selected) styleButton(b, cAccentDark, Color.WHITE, cAccent)
            else styleButton(b, cBtn, cText)
        }
    }

    // ==================================================================
    // Yerlesim
    // ==================================================================

    private fun buildUi(): View {
        val cfg = resources.configuration
        val tablet = cfg.smallestScreenWidthDp >= 600
        val wide = cfg.screenWidthDp >= 720 ||
            (cfg.orientation == Configuration.ORIENTATION_LANDSCAPE && cfg.screenWidthDp >= 560)
        scale = if (tablet) 1.15f else 1f
        val pad = if (tablet) dp(20) else dp(12)

        // --- ortak parcalar ---
        val header = buildHeader()
        btnListen = button("", 18f) { toggleListen() }.apply { minHeight = dp(64); minimumHeight = dp(64) }
        val waveCard = buildWaveCard()
        val recRow = buildRecordRow()
        val modeCard = card("DİNLEME MODU").apply {
            val (row, btns) = segmented(modeNames, modeIdx, 14f) { setMode(it) }
            modeBtns = btns
            addView(row)
            addView(text("Bell 20–200 Hz · Diyafram 20–1000 Hz · Akciğer 100–1600 Hz", 11f, cMuted), lp(bottom = 0).apply { topMargin = dp(8) })
        }
        val profileCard = card("PROFİL VE KAYNAK").apply {
            val (r1, b1) = segmented(arrayOf("Comfort", "Diagnostic"), profileIdx) { setProfile(it) }
            profileBtns = b1
            addView(r1, lp(bottom = 8))
            val (r2, b2) = segmented(arrayOf("İşlenmiş ses", "Ham ses"), sourceIdx) { setSource(it) }
            sourceBtns = b2
            addView(r2, lp(bottom = 0))
        }
        val soundCard = buildSoundCard()
        val details = buildDetails()

        val root: View
        if (wide) {
            // ---- tablet / yatay: sol kontroller, sag dalga formu ----
            val left = vbox().apply {
                addView(header, lp(bottom = 12))
                addView(btnListen, lp(bottom = 12))
                addView(modeCard, lp())
                addView(profileCard, lp())
                addView(soundCard, lp())
                addView(details, lp(bottom = 0))
            }
            val leftScroll = ScrollView(this).apply { addView(left) }
            val right = vbox().apply {
                addView(waveCard, lp(h = 0, weight = 1f))
                addView(recRow, lp(bottom = 0))
            }
            root = hbox().apply {
                gravity = Gravity.TOP
                setPadding(pad, pad, pad, pad)
                addView(leftScroll, LinearLayout.LayoutParams(0, MATCH_PARENT, 0.42f).apply { setMargins(0, 0, pad, 0) })
                addView(right, LinearLayout.LayoutParams(0, MATCH_PARENT, 0.58f))
            }
        } else {
            // ---- telefon dikey: tek sutun ----
            val col = vbox().apply {
                setPadding(pad, pad, pad, pad)
                addView(header, lp(bottom = 12))
                addView(btnListen, lp(bottom = 12))
                addView(waveCard, lp(h = dp(if (tablet) 320 else 230)))
                addView(recRow, lp())
                addView(modeCard, lp())
                addView(profileCard, lp())
                addView(soundCard, lp())
                addView(details, lp(bottom = 0))
            }
            root = ScrollView(this).apply { addView(col) }
        }
        root.setBackgroundColor(cBg)

        refreshConnectionUi()
        refreshListenUi()
        refreshRecordUi()
        refreshStats()
        refreshHr()
        tvLog.text = logLines.joinToString("\n")
        return root
    }

    private fun buildHeader(): View = hbox().apply {
        val titles = vbox().apply {
            addView(text("Pulsatio", 22f, cText, true))
            tvChip = text("", 13f, cMuted)
            addView(tvChip)
        }
        addView(titles, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        btnConnect = button("", 15f) { toggleConnect() }
        addView(btnConnect, LinearLayout.LayoutParams(dp(150), WRAP_CONTENT))
    }

    private fun buildWaveCard(): View = vbox().apply {
        background = rounded(Color.rgb(17, 22, 29), 16f)
        setPadding(dp(6), dp(6), dp(6), dp(8))
        waveView = WaveformView(this@MainActivity, audio).apply { seconds = windowSecs[windowIdx] }
        addView(waveView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        val (row, btns) = segmented(arrayOf("2 sn", "3 sn", "6 sn"), windowIdx, 12f) { i ->
            windowIdx = i
            waveView.seconds = windowSecs[i]
            paintSegmented(windowBtns, i)
        }
        windowBtns = btns
        btns.forEach { it.minHeight = dp(40); it.minimumHeight = dp(40) }
        addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
    }

    private fun buildRecordRow(): View = vbox().apply {
        val row = hbox()
        btnRec = button("", 15f) { toggleRecord() }
        row.addView(btnRec, LinearLayout.LayoutParams(dp(150), WRAP_CONTENT))
        tvStats = text("", 12f, cMuted).apply { setPadding(dp(12), 0, 0, 0) }
        row.addView(tvStats, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(row)
        setPadding(0, dp(10), 0, 0)
    }

    private fun buildSoundCard(): View = card("SES").apply {
        val row = hbox()
        tvGain = text("x${audio.gain.toInt()}", 16f, cText, true)
        row.addView(tvGain, LinearLayout.LayoutParams(dp(56), WRAP_CONTENT))
        val seek = SeekBar(this@MainActivity).apply {
            max = 29
            progress = audio.gain.toInt() - 1
            minimumHeight = dp(44)
            progressTintList = ColorStateList.valueOf(cAccent)
            thumbTintList = ColorStateList.valueOf(cAccent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    audio.gain = (p + 1).toFloat()
                    tvGain.text = "x${p + 1}"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        row.addView(seek, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(row, lp(bottom = 6))
        val sw = Switch(this@MainActivity).apply {
            text = "Hoparlör modu (sanal bas)"
            textSize = 14f * scale
            setTextColor(cText)
            isChecked = audio.speakerMode
            minimumHeight = dp(44)
            setOnCheckedChangeListener { _, checked -> audio.speakerMode = checked }
        }
        addView(sw)
        addView(text("Telefon hoparlöründe açın; kulaklıkla dinlerken kapalı tutun (doğal ses).", 11f, cMuted))
    }

    private fun buildDetails(): View = vbox().apply {
        fun label() = if (showDetails) "Ayrıntıları gizle" else "Ayrıntılar ve kayıt defteri"
        val toggle = button(label(), 13f) { }
        toggle.setOnClickListener {
            showDetails = !showDetails
            logBox.visibility = if (showDetails) View.VISIBLE else View.GONE
            toggle.text = label()
        }
        addView(toggle, lp(bottom = 8))
        tvLog = text("", 11f, cMuted).apply { typeface = Typeface.MONOSPACE }
        logScroll = ScrollView(this@MainActivity).apply {
            background = rounded(cCard, 12f)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            addView(tvLog)
        }
        logBox = logScroll
        logBox.visibility = if (showDetails) View.VISIBLE else View.GONE
        addView(logScroll, LinearLayout.LayoutParams(MATCH_PARENT, dp(200)))
    }

    // ==================================================================
    // Eylemler
    // ==================================================================

    private fun cmd(b: Int) {
        if (connected) ble.sendCommand(byteArrayOf(b.toByte()))
    }

    private fun toggleConnect() {
        if (wantConnected) {
            wantConnected = false
            if (streaming) stopListen()
            ble.disconnect()
            log("Bağlantı kesildi")
        } else {
            wantConnected = true
            connect()
        }
        refreshConnectionUi()
    }

    private fun connect() {
        if (!hasPerms()) {
            requestPermissions(neededPerms(), 1)
            return
        }
        if (!ble.isBluetoothOn()) {
            log("Bluetooth kapalı – lütfen açın")
            wantConnected = false
            refreshConnectionUi()
            return
        }
        ble.startScan()
    }

    private fun toggleListen() {
        if (streaming) stopListen() else startListen()
    }

    private fun startListen() {
        if (!connected) {
            log("Önce bağlanın")
            if (!wantConnected) toggleConnect()
            return
        }
        audio.resetStream()
        cmd(0x10)
        streaming = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        log("Dinleme başladı")
        refreshListenUi()
    }

    private fun stopListen() {
        cmd(0x11)
        streaming = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        log("Dinleme durdu")
        refreshListenUi()
    }

    private fun setMode(i: Int) {
        modeIdx = i
        cmd(modeCmds[i])
        paintSegmented(modeBtns, i)
    }

    private fun setProfile(i: Int) {
        profileIdx = i
        cmd(profileCmds[i])
        paintSegmented(profileBtns, i)
    }

    private fun setSource(i: Int) {
        sourceIdx = i
        cmd(sourceCmds[i])
        paintSegmented(sourceBtns, i)
    }

    private fun toggleRecord() {
        val r = recorder
        if (r == null) {
            val nr = WavRecorder(this)
            if (nr.start()) {
                recorder = nr
                audio.recorder = nr
                recStartMs = SystemClock.elapsedRealtime()
                log("Kayıt: Download/Pulsatio/${nr.displayName}")
            } else {
                log("Kayıt dosyası açılamadı")
            }
        } else {
            audio.recorder = null
            r.stop()
            recorder = null
            log("Kayıt kaydedildi: Download/Pulsatio/${r.displayName}")
        }
        refreshRecordUi()
    }

    // ==================================================================
    // Arayuz guncelleme
    // ==================================================================

    private fun refreshConnectionUi() {
        when {
            connected -> {
                val ok = mtu >= 175
                tvChip.text = "● Bağlı · MTU $mtu" + if (ok) "" else " (düşük)"
                tvChip.setTextColor(if (ok) cAccent else cWarn)
                btnConnect.text = "Bağlantıyı kes"
                styleButton(btnConnect, cBtn)
            }
            wantConnected -> {
                tvChip.text = "● Aranıyor / bağlanıyor…"
                tvChip.setTextColor(cWarn)
                btnConnect.text = "Vazgeç"
                styleButton(btnConnect, cBtn)
            }
            else -> {
                tvChip.text = "● Bağlı değil"
                tvChip.setTextColor(cMuted)
                btnConnect.text = "Bağlan"
                styleButton(btnConnect, cAccentDark, Color.WHITE, cAccent)
            }
        }
    }

    private fun refreshListenUi() {
        if (streaming) {
            btnListen.text = "■  Dinlemeyi durdur"
            styleButton(btnListen, Color.rgb(60, 30, 32), Color.WHITE, cDanger)
        } else {
            btnListen.text = "▶  Dinlemeye başla"
            styleButton(btnListen, if (connected) cAccent else cBtn, if (connected) Color.rgb(5, 30, 28) else cMuted)
        }
    }

    private fun refreshRecordUi() {
        if (recorder != null) {
            val s = (SystemClock.elapsedRealtime() - recStartMs) / 1000
            btnRec.text = String.format(Locale.US, "■  %02d:%02d", s / 60, s % 60)
            styleButton(btnRec, Color.rgb(60, 30, 32), Color.WHITE, cDanger)
        } else {
            btnRec.text = "●  Kaydet"
            styleButton(btnRec, cBtn, cDanger)
        }
    }

    private fun refreshStats() {
        val rx = audio.rxPackets
        val lost = audio.lostPackets
        val pct = if (rx + lost > 0) 100.0 * lost / (rx + lost) else 0.0
        tvStats.text = if (connected) String.format(
            Locale.US,
            "Kayıp %%%.1f · paket %d örnek\nGiriş %.0f dBFS · tampon %d ms",
            pct, audio.lastPacketSamples, audio.peakDb, audio.queuedMs()
        ) else "Bağlı değil"
        tvStats.setTextColor(if (pct > 5.0) cWarn else cMuted)
        if (recorder != null) refreshRecordUi()
    }

    private fun refreshHr() {
        lastHr = if (streaming) audio.estimateHr() else null
        val hr = lastHr
        waveView.hrText = if (hr != null) "♥ $hr" else if (streaming) "♥ --" else ""
        waveView.subText = if (streaming) "tahmini nabız (bpm) · " + modeNames[modeIdx] else ""
    }

    private fun log(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        logLines.addLast("[$ts] $msg")
        while (logLines.size > 120) logLines.removeFirst()
        if (::tvLog.isInitialized) {
            tvLog.text = logLines.joinToString("\n") + if (espStatus.isNotEmpty()) "\n\nESP: $espStatus" else ""
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    // ==================================================================
    // BleClient.Listener (onAudioPacket haric hepsi ana thread'de)
    // ==================================================================

    override fun onLog(msg: String) = log(msg)

    override fun onConnectionChanged(connected: Boolean) {
        this.connected = connected
        if (connected) {
            log("Hazır")
            // Uygulamadaki secimleri ESP'ye uygula
            cmd(modeCmds[modeIdx])
            cmd(profileCmds[profileIdx])
            cmd(sourceCmds[sourceIdx])
            if (streaming) {                 // kopma sonrasi otomatik devam
                audio.resetStream()
                cmd(0x10)
                log("Dinleme kaldığı yerden devam ediyor")
            }
        } else {
            mtu = 23
            if (wantConnected) {
                log("Bağlantı koptu – yeniden bağlanılıyor…")
                ui.postDelayed({ if (wantConnected && !this.connected) connect() }, 1500)
            } else if (streaming) {
                streaming = false
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        refreshConnectionUi()
        refreshListenUi()
    }

    override fun onScanTimeout() {
        if (wantConnected && !connected) {
            ui.postDelayed({ if (wantConnected && !connected) connect() }, 2000)
        }
    }

    override fun onAudioPacket(data: ByteArray) = audio.onPacket(data)

    override fun onStatus(text: String) {
        espStatus = text
    }

    override fun onMtu(mtu: Int) {
        this.mtu = mtu
        refreshConnectionUi()
    }
}
