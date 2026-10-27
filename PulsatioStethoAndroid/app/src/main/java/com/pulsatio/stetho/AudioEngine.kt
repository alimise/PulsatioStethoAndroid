package com.pulsatio.stetho

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.tanh

/**
 * BLE paketlerini cozer, kayip paketleri telafi eder, dinleme filtresini
 * uygular ve AudioTrack ile 8 kHz'de calar (Android donusumu kendisi yapar).
 *
 * Kayit (WAV) ve dalga formu HAM sinyali kullanir; filtre/kazanc sadece
 * dinlenen sese uygulanir.
 */
class AudioEngine {

    companion object {
        const val FS = 8000
        private const val RAMP = 16                      // 2 ms yumusak gecis
        private const val PREBUFFER = FS * 200 / 1000    // 200 ms jitter tamponu
        private const val MAX_LATENCY = FS * 600 / 1000  // en fazla 600 ms gecikme
    }

    private class Decoded(val counter: Long, val mask: Long, val pcm: ShortArray)

    // --- kullanici ayarlari ---
    @Volatile var gain = 6f
    @Volatile var speakerMode = false
    @Volatile var recorder: WavRecorder? = null

    // --- istatistikler ---
    @Volatile var rxPackets = 0L
        private set
    @Volatile var lostPackets = 0L
        private set
    @Volatile var badPackets = 0L
        private set
    @Volatile var peakDb = -99f
        private set
    @Volatile var lastPacketSamples = 0
        private set

    // --- cozme / telafi durumu (BLE thread) ---
    private var lastCounter: Long? = null
    private var lastSample = 0f
    private val adpcm = ImaAdpcm()

    // --- dinleme filtresi ---
    private val hp1 = Biquad()     // 30 Hz yuksek geciren, 4. derece (2 biquad)
    private val hp2 = Biquad()
    private val bpHp = Biquad()    // sanal bas: 30-150 Hz bandi
    private val bpLp = Biquad()
    private val hbHp = Biquad()    // harmonikler: 120-800 Hz
    private val hbLp = Biquad()

    // --- dalga formu (son 2 sn, ham) ---
    private val waveLock = Any()
    private val wave = FloatArray(FS * 2)
    private var wavePos = 0

    // --- calma kuyrugu ---
    private val lock = Any()
    private val queue = ArrayDeque<ShortArray>()
    private var queued = 0
    private var started = false

    @Volatile private var running = false
    private var thread: Thread? = null
    private var track: AudioTrack? = null

    init {
        configureFilters()
    }

    private fun configureFilters() {
        hp1.highpass(30f, 0.5412f)
        hp2.highpass(30f, 1.3066f)
        bpHp.highpass(30f, 0.7071f)
        bpLp.lowpass(150f, 0.7071f)
        hbHp.highpass(120f, 0.7071f)
        hbLp.lowpass(800f, 0.7071f)
    }

    // ------------------------------------------------------------------
    // Calma
    // ------------------------------------------------------------------

    fun start() {
        if (running) return
        val minBuf = AudioTrack.getMinBufferSize(
            FS, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(FS)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(max(minBuf, FS / 10 * 2))   // ~100 ms
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        t.play()
        running = true
        thread = Thread({ playLoop(t) }, "stetho-audio").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        track?.let {
            try { it.stop() } catch (_: Exception) { }
            it.release()
        }
        track = null
    }

    private fun playLoop(t: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val silence = ShortArray(FS / 100)   // 10 ms
        while (running) {
            val block: ShortArray? = synchronized(lock) {
                if (!started && queued >= PREBUFFER) started = true
                if (!started) {
                    null
                } else {
                    val b = queue.removeFirstOrNull()
                    if (b == null) started = false else queued -= b.size
                    b
                }
            }
            // write() bloklar -> calma hizi ses kartinin saatine kilitlenir
            if (block != null) t.write(block, 0, block.size) else t.write(silence, 0, silence.size)
        }
    }

    fun queuedMs(): Int = synchronized(lock) { queued * 1000 / FS }

    // ------------------------------------------------------------------
    // Akis sifirlama (Baslat'a basildiginda)
    // ------------------------------------------------------------------

    fun resetStream() {
        synchronized(this) {
            lastCounter = null
            lastSample = 0f
            rxPackets = 0
            lostPackets = 0
            badPackets = 0
            configureFilters()
        }
        synchronized(lock) {
            queue.clear()
            queued = 0
            started = false
        }
    }

    // ------------------------------------------------------------------
    // Paket isleme (BLE thread'inden cagrilir)
    // ------------------------------------------------------------------

    fun onPacket(data: ByteArray) {
        synchronized(this) { handlePacket(data) }
    }

    private fun handlePacket(data: ByteArray) {
        val pkt = decode(data)
        if (pkt == null) {
            badPackets++
            return
        }

        // Kayip tespiti: ESP her pakette sayaci 1 artirir
        var gap = 0L
        val lc = lastCounter
        if (lc != null) {
            val expected = (lc + 1) and pkt.mask
            if (pkt.counter != expected) {
                gap = (pkt.counter - expected) and pkt.mask
                if (gap > 1000) gap = 0   // sayac sifirlandi
                lostPackets += gap
            }
        }
        lastCounter = pkt.counter
        rxPackets++
        lastPacketSamples = pkt.pcm.size

        // Kayip paketlerin yerine ayni surede sessizlik (zaman ekseni korunur)
        val gapSamples = (min(gap, 50L) * pkt.pcm.size).toInt()
        val n = gapSamples + pkt.pcm.size
        val raw = FloatArray(n)
        if (gapSamples > 0) {
            val r = min(RAMP, gapSamples)
            for (i in 0 until r) raw[i] = lastSample * (1f - i.toFloat() / r)
        }
        var peak = 0
        for (i in pkt.pcm.indices) {
            val s = pkt.pcm[i].toInt()
            if (abs(s) > peak) peak = abs(s)
            var v = s / 32768f
            if (gapSamples > 0 && i < RAMP) v *= i.toFloat() / RAMP
            raw[gapSamples + i] = v
        }
        if (n > 0) lastSample = raw[n - 1]
        peakDb = (20.0 * log10(peak / 32768.0 + 1e-9)).toFloat()

        // 1) Kayit: ham sinyal
        recorder?.write(ShortArray(n) { i ->
            (raw[i] * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
        })

        // 2) Dalga formu: ham sinyal
        synchronized(waveLock) {
            for (v in raw) {
                wave[wavePos] = v
                wavePos = (wavePos + 1) % wave.size
            }
        }

        // 3) Dinleme: 30 Hz HP + kazanc + yumusak sinirlama (+ istege bagli sanal bas)
        val g = gain
        val spk = speakerMode
        val out = ShortArray(n)
        for (i in 0 until n) {
            val x = raw[i]
            val clean = hp2.process(hp1.process(x))
            val y = if (spk) {
                val low = bpLp.process(bpHp.process(x))
                val h = hbLp.process(hbHp.process(tanh(abs(low * g) * 2f)))
                tanh(clean * g * 0.5f + h * g)
            } else {
                tanh(clean * g)
            }
            out[i] = (y * 32767f).toInt().toShort()
        }

        synchronized(lock) {
            queue.addLast(out)
            queued += out.size
            while (queued > MAX_LATENCY && queue.size > 1) {
                queued -= queue.removeFirst().size
            }
        }
    }

    private fun decode(d: ByteArray): Decoded? {
        // Standart PCM paketi: 'S''T' 1 flags | counter u32 | fs u16 | n u8 | 0 | int16 x n
        if (d.size >= 12 && d[0] == 'S'.code.toByte() && d[1] == 'T'.code.toByte()) {
            if (d[2].toInt() != 1) return null
            val counter = u32(d, 4)
            val n = d[10].toInt() and 0xFF
            if (d.size < 12 + n * 2) return null
            val pcm = ShortArray(n) { i ->
                ((d[12 + 2 * i].toInt() and 0xFF) or (d[13 + 2 * i].toInt() shl 8)).toShort()
            }
            return Decoded(counter, 0xFFFFFFFFL, pcm)
        }
        // MTU=23 acil durum: 'B' blok ADPCM, 20 bayt, 29 ornek
        if (d.size == 20 && d[0] == 'B'.code.toByte()) {
            val counter = ((d[2].toInt() and 0xFF) or ((d[3].toInt() and 0xFF) shl 8)).toLong()
            val first = ((d[4].toInt() and 0xFF) or (d[5].toInt() shl 8)).toShort()
            return Decoded(counter, 0xFFFFL, adpcm.decodeBlock(first, d, 6, 14))
        }
        return null
    }

    private fun u32(d: ByteArray, o: Int): Long =
        (d[o].toLong() and 0xFF) or
            ((d[o + 1].toLong() and 0xFF) shl 8) or
            ((d[o + 2].toLong() and 0xFF) shl 16) or
            ((d[o + 3].toLong() and 0xFF) shl 24)

    /** Son 2 saniyelik ham sinyali (eskiden yeniye) dst'ye kopyalar */
    fun copyWaveform(dst: FloatArray) {
        synchronized(waveLock) {
            val n = min(dst.size, wave.size)
            for (i in 0 until n) dst[i] = wave[(wavePos + i) % wave.size]
        }
    }
}
