package com.pulsatio.stetho

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Her sayfa bu siniftan turer; MainActivity sayfalar arasi gecisi yonetir. */
abstract class Screen(val app: MainActivity) {
    val ui: Ui get() = app.ui
    /** 0 Ana sayfa, 1 Kayitlar, 2 Cihaz; -1 = alt menu gizli (akis sayfalari) */
    open val navTab: Int = -1
    abstract fun build(): View
    open fun onShow() {}
    open fun onHide() {}
    open fun onTick() {}
    open fun onConnectionChanged() {}
    /** true donerse geri tusu sayfa tarafindan islendi */
    open fun onBack(): Boolean = false
}

class MainActivity : Activity(), BleClient.Listener {

    lateinit var ui: Ui
    lateinit var ble: BleClient
    lateinit var store: SessionStore
    lateinit var prefs: SharedPreferences
    val audio = AudioEngine()
    val handler = Handler(Looper.getMainLooper())

    // ---------------- baglanti durumu ----------------
    var wantConnected = false
        private set
    var connected = false
        private set
    var mtu = 23
        private set
    var espStatus = ""
        private set
    var streaming = false
        private set
    val logLines = ArrayDeque<String>()
    var logCount = 0
        private set

    // ESP ayarlari (Cihaz sayfasindan)
    var modeIdx = 1          // 0 Bell 1 Diyafram 2 Genis 3 Akciger
    var profileIdx = 0       // 0 Comfort 1 Diagnostic
    var sourceIdx = 1        // 0 Islenmis 1 Ham (analiz icin varsayilan) 2 Odakli
    var focusIdx = 0         // 0 Tumu ... 6 Diyastol
    val modeNames = arrayOf("Bell", "Diyafram", "Geniş", "Akciğer")
    private val modeCmds = intArrayOf(0x01, 0x02, 0x04, 0x03)
    private val profileCmds = intArrayOf(0x05, 0x06)
    private val sourceCmds = intArrayOf(0x13, 0x12, 0x14)

    // ---------------- gezinme ----------------
    private lateinit var root: FrameLayout
    private lateinit var content: FrameLayout
    private lateinit var nav: LinearLayout
    private val navIcons = ArrayList<IconDrawable>()
    private val navLabels = ArrayList<TextView>()
    private val navItems = ArrayList<View>()
    private val stack = ArrayList<Screen>()
    private var currentView: View? = null
    private var toastView: TextView? = null

    private val ticker = object : Runnable {
        override fun run() {
            stack.lastOrNull()?.onTick()
            handler.postDelayed(this, 40)
        }
    }

    // ==================================================================
    // Yasam dongusu
    // ==================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Palette.bgTop
        window.navigationBarColor = Palette.bgBottom
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        ui = Ui(this)
        applyScale()
        prefs = getSharedPreferences("pulsatio", Context.MODE_PRIVATE)
        audio.gain = prefs.getFloat("gain", 6f)
        audio.speakerMode = prefs.getBoolean("speaker", false)
        modeIdx = prefs.getInt("mode", 1)
        profileIdx = prefs.getInt("profile", 0)
        sourceIdx = prefs.getInt("source", 1)
        store = SessionStore(this)
        ble = BleClient(this, this)
        audio.start()

        root = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Palette.bgTop, Palette.bgBottom))
        }
        content = FrameLayout(this)
        root.addView(content, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        nav = buildNav()
        root.addView(nav, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply {
            setMargins(ui.dp(14), 0, ui.dp(14), ui.dp(12))
        })
        setContentView(root)

        setRoot(HomeScreen(this), animate = false)
        if (!hasPerms()) requestPermissions(neededPerms(), 1)
        log("Uygulama başladı")
        handler.post(ticker)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyScale()
        // Ekran dondu / boyut degisti: mevcut sayfayi yeni olculerle yeniden kur
        val s = stack.lastOrNull() ?: return
        currentView?.let { content.removeView(it) }
        val v = s.build()
        content.addView(v, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        currentView = v
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stack.lastOrNull()?.onHide()
        audio.recorder = null
        ble.close()
        audio.stop()
        super.onDestroy()
    }

    @Deprecated("Activity API")
    override fun onBackPressed() {
        val top = stack.lastOrNull()
        if (top != null && top.onBack()) return
        if (stack.size > 1) { pop(); return }
        if (top !is HomeScreen) { setRoot(HomeScreen(this)); return }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private fun applyScale() {
        ui.textScale = if (resources.configuration.smallestScreenWidthDp >= 600) 1.12f else 1f
    }

    /** Tablet veya yatay genis ekran: iki sutunlu yerlesim */
    fun isWide(): Boolean {
        val c = resources.configuration
        return c.screenWidthDp >= 720 || (c.orientation == Configuration.ORIENTATION_LANDSCAPE && c.screenWidthDp >= 560)
    }

    fun pagePad(): Int = if (resources.configuration.smallestScreenWidthDp >= 600) ui.dp(28) else ui.dp(18)

    // ==================================================================
    // Gezinme
    // ==================================================================

    fun setRoot(s: Screen, animate: Boolean = true) {
        stack.lastOrNull()?.onHide()
        stack.clear()
        stack.add(s)
        show(s, if (animate) 0 else -99)
    }

    fun push(s: Screen) {
        stack.lastOrNull()?.onHide()
        stack.add(s)
        show(s, 1)
    }

    fun replace(s: Screen) {
        stack.lastOrNull()?.onHide()
        if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
        stack.add(s)
        show(s, 1)
    }

    fun pop() {
        if (stack.size <= 1) return
        stack.removeAt(stack.size - 1).onHide()
        show(stack.last(), -1)
    }

    fun popToRoot() {
        stack.lastOrNull()?.onHide()
        while (stack.size > 1) stack.removeAt(stack.size - 1)
        if (stack.isEmpty()) stack.add(HomeScreen(this))
        show(stack.last(), -1)
    }

    /** dir: 1 ileri, -1 geri, 0 solma, -99 animasyonsuz */
    private fun show(s: Screen, dir: Int) {
        val old = currentView
        val v = s.build()
        content.addView(v, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        currentView = v
        val w = if (content.width > 0) content.width.toFloat() else resources.displayMetrics.widthPixels.toFloat()
        if (dir == -99) {
            if (old != null) content.removeView(old)
        } else {
            v.alpha = 0f
            v.translationX = if (dir == 0) 0f else dir * w * 0.22f
            v.animate().alpha(1f).translationX(0f).setDuration(280)
                .setInterpolator(DecelerateInterpolator(2.2f)).start()
            if (old != null) {
                old.animate().alpha(0f).translationX(if (dir == 0) 0f else -dir * w * 0.12f).setDuration(200)
                    .withEndAction { content.removeView(old) }.start()
            }
        }
        s.onShow()
        updateNav(s.navTab)
    }

    private fun buildNav(): LinearLayout {
        val bar = ui.hbox().apply {
            background = ui.rounded(Palette.alpha(Palette.surface, 0.96f), 26f, Palette.stroke)
            setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8))
            elevation = ui.dpf(12f)
        }
        val items = listOf(Pair(Icon.HOME, "Ana sayfa"), Pair(Icon.LIST, "Kayıtlar"), Pair(Icon.TUNE, "Cihaz"))
        items.forEachIndexed { i, (ic, label) ->
            val d = IconDrawable(ic, Palette.muted, ui.dp(22))
            val item = ui.vbox().apply {
                gravity = Gravity.CENTER
                setPadding(0, ui.dp(8), 0, ui.dp(6))
                isClickable = true
                background = ui.ripple(ui.rounded(0x00000000, 18f))
                val iv = android.widget.ImageView(this@MainActivity).apply { setImageDrawable(d) }
                addView(iv, LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)))
                val tv = ui.text(label, 11f, Palette.muted, ui.fontMedium).apply { gravity = Gravity.CENTER }
                addView(tv, ui.lp(WRAP_CONTENT, WRAP_CONTENT, top = 3))
                navLabels.add(tv)
                setOnClickListener { onNav(i) }
            }
            navIcons.add(d)
            navItems.add(item)
            bar.addView(item, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        return bar
    }

    private fun onNav(i: Int) {
        val cur = stack.lastOrNull()
        if (cur != null && cur.navTab == i && stack.size == 1) return
        setRoot(when (i) {
            1 -> HistoryScreen(this)
            2 -> DeviceScreen(this)
            else -> HomeScreen(this)
        })
    }

    private fun updateNav(tab: Int) {
        val visible = tab >= 0
        if (visible && nav.visibility != View.VISIBLE) {
            nav.visibility = View.VISIBLE
            nav.translationY = ui.dpf(90f)
            nav.animate().translationY(0f).setDuration(260).setInterpolator(DecelerateInterpolator(2f)).start()
        } else if (!visible && nav.visibility == View.VISIBLE) {
            nav.animate().translationY(ui.dpf(110f)).setDuration(180)
                .withEndAction { nav.visibility = View.GONE }.start()
        }
        for (i in navItems.indices) {
            val on = i == tab
            navIcons[i].setTint2(if (on) Palette.accent else Palette.muted)
            navLabels[i].setTextColor(if (on) Palette.accent else Palette.muted)
            navItems[i].background = ui.ripple(ui.rounded(if (on) Palette.alpha(Palette.accent, 0.12f) else 0x00000000, 18f))
        }
    }

    /** Alt bosluk: alt menu gorunurken icerik altta kalmasin */
    fun navSpace(): Int = ui.dp(96)

    // ==================================================================
    // Bildirim (ekranin altindan kayan kucuk kart)
    // ==================================================================

    fun toast(msg: String, color: Int = Palette.accent, icon: Icon = Icon.CHECK) {
        toastView?.let { root.removeView(it) }
        val d = IconDrawable(icon, color, ui.dp(20))
        d.setBounds(0, 0, ui.dp(20), ui.dp(20))
        val tv = ui.text(msg, 14f, Palette.text, ui.fontMedium).apply {
            background = ui.rounded(Palette.surface3, 18f, Palette.alpha(color, 0.5f))
            setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12))
            setCompoundDrawables(d, null, null, null)
            compoundDrawablePadding = ui.dp(10)
            elevation = ui.dpf(16f)
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(tv, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            setMargins(ui.dp(16), ui.dp(16), ui.dp(16), 0)
        })
        toastView = tv
        tv.alpha = 0f
        tv.translationY = -ui.dpf(30f)
        tv.animate().alpha(1f).translationY(0f).setDuration(240).start()
        handler.postDelayed({
            tv.animate().alpha(0f).translationY(-ui.dpf(20f)).setDuration(220)
                .withEndAction { root.removeView(tv); if (toastView === tv) toastView = null }.start()
        }, 2600)
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
            if (wantConnected) ble.startScan()
        } else {
            toast("Bluetooth izni gerekli", Palette.amber, Icon.WARN)
        }
    }

    // ==================================================================
    // Baglanti ve ses akisi
    // ==================================================================

    fun connect() {
        wantConnected = true
        if (!hasPerms()) { requestPermissions(neededPerms(), 1); return }
        if (!ble.isBluetoothOn()) {
            toast("Bluetooth kapalı – lütfen açın", Palette.amber, Icon.BLUETOOTH)
            wantConnected = false
            notifyConn()
            return
        }
        ble.startScan()
        notifyConn()
    }

    fun disconnect() {
        wantConnected = false
        if (streaming) stopStreaming()
        ble.disconnect()
        notifyConn()
    }

    fun toggleConnection() { if (wantConnected) disconnect() else connect() }

    fun connectionText(): String = when {
        connected -> "Bağlı · MTU $mtu"
        wantConnected -> "Bağlanıyor…"
        else -> "Bağlı değil"
    }

    fun connectionColor(): Int = when {
        connected -> if (mtu >= 175) Palette.accent else Palette.amber
        wantConnected -> Palette.amber
        else -> Palette.faint
    }

    fun startStreaming() {
        if (!connected || streaming) return
        audio.resetStream()
        cmd(0x10)
        streaming = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        log("Ses akışı başladı")
    }

    fun stopStreaming() {
        if (!streaming) return
        cmd(0x11)
        streaming = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        log("Ses akışı durdu")
    }

    fun cmd(b: Int, extra: ByteArray? = null) {
        if (!connected) return
        val bytes = if (extra == null) byteArrayOf(b.toByte()) else byteArrayOf(b.toByte()) + extra
        ble.sendCommand(bytes)
    }

    fun setMode(i: Int) { modeIdx = i; cmd(modeCmds[i]); prefs.edit().putInt("mode", i).apply() }
    fun setProfile(i: Int) { profileIdx = i; cmd(profileCmds[i]); prefs.edit().putInt("profile", i).apply() }
    fun setSource(i: Int) { sourceIdx = i; cmd(sourceCmds[i]); prefs.edit().putInt("source", i).apply() }
    fun setFocus(i: Int) { focusIdx = i; cmd(0x20 + i) }
    fun saveAudioPrefs() {
        prefs.edit().putFloat("gain", audio.gain).putBoolean("speaker", audio.speakerMode).apply()
    }

    private fun notifyConn() {
        stack.lastOrNull()?.onConnectionChanged()
    }

    fun log(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        logLines.addLast("[$ts] $msg")
        logCount++
        while (logLines.size > 200) logLines.removeFirst()
    }

    // ==================================================================
    // BleClient.Listener (onAudioPacket haric hepsi ana thread'de)
    // ==================================================================

    override fun onLog(msg: String) = log(msg)

    override fun onConnectionChanged(connected: Boolean) {
        this.connected = connected
        if (connected) {
            log("Cihaz hazır")
            cmd(modeCmds[modeIdx])
            cmd(profileCmds[profileIdx])
            cmd(sourceCmds[sourceIdx])
            if (streaming) {            // kopma sonrasi kaldigi yerden
                audio.resetStream()
                cmd(0x10)
            }
            toast("Steteskop bağlandı", Palette.accent, Icon.BLUETOOTH)
        } else {
            mtu = 23
            if (wantConnected) {
                log("Bağlantı koptu – yeniden bağlanılıyor")
                handler.postDelayed({ if (wantConnected && !this.connected) ble.startScan() }, 1500)
            } else if (streaming) {
                streaming = false
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        notifyConn()
    }

    override fun onScanTimeout() {
        if (wantConnected && !connected) handler.postDelayed({ if (wantConnected && !connected) ble.startScan() }, 2500)
    }

    override fun onAudioPacket(data: ByteArray) = audio.onPacket(data)

    override fun onStatus(text: String) {
        espStatus = text
    }

    override fun onMtu(mtu: Int) {
        this.mtu = mtu
        notifyConn()
    }
}
