package com.pulsatio.stetho

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity(), BleClient.Listener {

    private lateinit var ble: BleClient
    private val audio = AudioEngine()
    private var recorder: WavRecorder? = null

    private lateinit var tvConn: TextView
    private lateinit var tvStats: TextView
    private lateinit var tvEsp: TextView
    private lateinit var tvLog: TextView
    private lateinit var tvGain: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var waveView: WaveformView
    private lateinit var btnRec: Button

    private var mtu = 23
    private var connected = false
    private val ui = Handler(Looper.getMainLooper())
    private val logLines = ArrayDeque<String>()
    private var tick = 0

    private val ticker = object : Runnable {
        override fun run() {
            waveView.invalidate()
            if (tick++ % 5 == 0) updateStats()
            ui.postDelayed(this, 40)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ble = BleClient(this, this)
        setContentView(buildUi())
        audio.start()
        if (!hasPerms()) requestPermissions(neededPerms(), 1)
        log("Hazır. PC uygulamasını kapatın (ESP tek bağlantı kabul eder) ve Bağlan'a basın.")
        ui.post(ticker)
    }

    override fun onDestroy() {
        ui.removeCallbacks(ticker)
        audio.recorder = null
        recorder?.stop()
        recorder = null
        ble.close()
        audio.stop()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Izinler
    // ------------------------------------------------------------------

    private fun neededPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasPerms(): Boolean =
        neededPerms().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasPerms()) log("İzinler verildi")
        else log("Bluetooth izni verilmedi – Ayarlar > Uygulamalar > Pulsatio Stetho > İzinler")
    }

    // ------------------------------------------------------------------
    // Arayuz (XML yok, kodla kuruluyor)
    // ------------------------------------------------------------------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun btn(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 13f
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (v in views) addView(v, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    }

    private fun label(t: String, size: Float = 13f): TextView = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(Color.rgb(220, 225, 235))
        setPadding(0, dp(3), 0, dp(3))
    }

    private fun cmd(b: Int) = ble.sendCommand(byteArrayOf(b.toByte()))

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(Color.rgb(8, 10, 15))
        }

        tvConn = label("Bağlı değil", 15f).apply { setTypeface(typeface, Typeface.BOLD) }
        root.addView(tvConn)

        root.addView(row(btn("Bağlan") { connect() }, btn("Bağlantıyı kes") { ble.disconnect() }))

        btnRec = btn("Kaydet") { toggleRecord() }
        root.addView(row(btn("Başlat") { startStream() }, btn("Durdur") { stopStream() }, btnRec))

        root.addView(row(
            btn("Bell") { cmd(0x01); log("Mod: BELL 20-200 Hz") },
            btn("Diyafram") { cmd(0x02); log("Mod: DIAPHRAGM 20-1000 Hz") },
            btn("Akciğer") { cmd(0x03); log("Mod: LUNG") },
            btn("Geniş") { cmd(0x04); log("Mod: WIDE") }
        ))
        root.addView(row(
            btn("Comfort") { cmd(0x05); log("Profil: COMFORT") },
            btn("Diagnostic") { cmd(0x06); log("Profil: DIAGNOSTIC") },
            btn("Ham ses") { cmd(0x12); log("Kaynak: RAW") },
            btn("İşlenmiş") { cmd(0x13); log("Kaynak: PROCESSED") }
        ))

        // Ses seviyesi
        tvGain = label("Ses x${audio.gain.toInt()}")
        val seek = SeekBar(this).apply {
            max = 29
            progress = audio.gain.toInt() - 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    audio.gain = (p + 1).toFloat()
                    tvGain.text = "Ses x${p + 1}"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(tvGain, LinearLayout.LayoutParams(dp(70), WRAP_CONTENT))
            addView(seek, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        })

        root.addView(CheckBox(this).apply {
            text = "Hoparlör modu (sanal bas) – kulaklıkla dinlerken kapalı tutun"
            setTextColor(Color.LTGRAY)
            textSize = 12f
            isChecked = audio.speakerMode
            setOnCheckedChangeListener { _, checked -> audio.speakerMode = checked }
        })

        tvStats = label("", 12f).apply { typeface = Typeface.MONOSPACE }
        tvEsp = label("", 10f).apply { setTextColor(Color.GRAY) }
        root.addView(tvStats)
        root.addView(tvEsp)

        waveView = WaveformView(this, audio)
        root.addView(waveView, LinearLayout.LayoutParams(MATCH_PARENT, dp(170)))

        tvLog = label("", 11f).apply { typeface = Typeface.MONOSPACE }
        logScroll = ScrollView(this).apply { addView(tvLog) }
        root.addView(logScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        return root
    }

    // ------------------------------------------------------------------
    // Eylemler
    // ------------------------------------------------------------------

    private fun connect() {
        if (!hasPerms()) {
            requestPermissions(neededPerms(), 1)
            return
        }
        if (!ble.isBluetoothOn()) {
            log("Bluetooth kapalı – lütfen açın")
            return
        }
        ble.startScan()
    }

    private fun startStream() {
        if (!connected) { log("Önce bağlanın"); return }
        audio.resetStream()
        cmd(0x10)
        log("Ses akışı başladı")
    }

    private fun stopStream() {
        cmd(0x11)
        log("Ses akışı durdu")
    }

    private fun toggleRecord() {
        val r = recorder
        if (r == null) {
            val nr = WavRecorder(this)
            if (nr.start()) {
                recorder = nr
                audio.recorder = nr
                btnRec.text = "Kaydı bitir"
                log("Kayıt: Download/Pulsatio/${nr.displayName}")
            } else {
                log("Kayıt dosyası açılamadı")
            }
        } else {
            audio.recorder = null
            r.stop()
            recorder = null
            btnRec.text = "Kaydet"
            log("Kayıt kaydedildi: ${r.displayName}")
        }
    }

    private fun updateStats() {
        val rx = audio.rxPackets
        val lost = audio.lostPackets
        val pct = if (rx + lost > 0) 100.0 * lost / (rx + lost) else 0.0
        tvStats.text = String.format(
            Locale.US,
            "MTU %d | paket %d örnek | alınan %d | kayıp %%%.1f\nbozuk %d | giriş %.0f dBFS | tampon %d ms",
            mtu, audio.lastPacketSamples, rx, pct,
            audio.badPackets, audio.peakDb, audio.queuedMs()
        )
    }

    private fun log(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        logLines.addLast("[$ts] $msg")
        while (logLines.size > 80) logLines.removeFirst()
        tvLog.text = logLines.joinToString("\n")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ------------------------------------------------------------------
    // BleClient.Listener (onAudioPacket haric hepsi ana thread'de gelir)
    // ------------------------------------------------------------------

    override fun onLog(msg: String) = log(msg)

    override fun onConnectionChanged(connected: Boolean) {
        this.connected = connected
        tvConn.text = if (connected) "BAĞLI" else "Bağlı değil"
        tvConn.setTextColor(if (connected) Color.rgb(80, 220, 120) else Color.rgb(220, 225, 235))
        if (connected) {
            log("Hazır – Başlat'a basın")
        } else {
            mtu = 23
        }
    }

    override fun onAudioPacket(data: ByteArray) = audio.onPacket(data)

    override fun onStatus(text: String) {
        tvEsp.text = text
    }

    override fun onMtu(mtu: Int) {
        this.mtu = mtu
    }
}
