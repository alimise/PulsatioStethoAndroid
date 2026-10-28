package com.pulsatio.stetho

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.tanh

/** 8 kHz / 16 bit mono WAV kaydi (uygulama klasorune; izin gerekmez) */
class WavRecorder(private val file: File, private val fs: Int = AudioEngine.FS) {

    private var out: FileOutputStream? = null
    private var channel: FileChannel? = null
    private var dataBytes = 0L

    val samples: Long get() = dataBytes / 2

    @Synchronized
    fun start(): Boolean = try {
        file.parentFile?.mkdirs()
        val o = FileOutputStream(file)
        val ch = o.channel
        ch.write(WavIO.header(0, fs))
        out = o
        channel = ch
        dataBytes = 0
        true
    } catch (e: Exception) {
        false
    }

    @Synchronized
    fun write(pcm: ShortArray) {
        val ch = channel ?: return
        try {
            val buf = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            buf.asShortBuffer().put(pcm)
            while (buf.hasRemaining()) ch.write(buf)
            dataBytes += pcm.size * 2L
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun stop() {
        val ch = channel ?: return
        try {
            ch.write(WavIO.header(dataBytes, fs), 0)
            ch.force(true)
            ch.close()
            out?.close()
        } catch (_: Exception) {
        }
        channel = null
        out = null
    }
}

object WavIO {
    fun header(dataLen: Long, fs: Int): ByteBuffer {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray(Charsets.US_ASCII))
        b.putInt((36 + dataLen).toInt())
        b.put("WAVE".toByteArray(Charsets.US_ASCII))
        b.put("fmt ".toByteArray(Charsets.US_ASCII))
        b.putInt(16)
        b.putShort(1.toShort())
        b.putShort(1.toShort())
        b.putInt(fs)
        b.putInt(fs * 2)
        b.putShort(2.toShort())
        b.putShort(16.toShort())
        b.put("data".toByteArray(Charsets.US_ASCII))
        b.putInt(dataLen.toInt())
        b.flip()
        return b
    }

    /** PCM16 mono WAV okur ('data' blogunu arar) */
    fun read(file: File): ShortArray {
        val bytes = file.readBytes()
        if (bytes.size < 44) return ShortArray(0)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var dataStart = 44
        var dataLen = bytes.size - 44
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val len = bb.getInt(pos + 4)
            if (id == "data") {
                dataStart = pos + 8
                dataLen = if (len <= 0 || dataStart + len > bytes.size) bytes.size - dataStart else len
                break
            }
            pos += 8 + len + (len and 1)
        }
        val n = dataLen / 2
        val out = ShortArray(n)
        bb.position(dataStart)
        bb.asShortBuffer().get(out, 0, n)
        return out
    }
}

/** Kaydi dinlemek icin basit oynatici (30 Hz HP + kazanc, ayni dinleme zinciri) */
class WavPlayer {
    @Volatile var positionSec = -1f
        private set
    @Volatile var playing = false
        private set
    private var thread: Thread? = null

    fun play(pcm: ShortArray, gain: Float, onEnd: () -> Unit) {
        stop()
        playing = true
        thread = Thread({
            val fs = AudioEngine.FS
            val minBuf = AudioTrack.getMinBufferSize(fs, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(fs)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(minBuf, fs / 5))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            val hp1 = Biquad().apply { highpass(30f, 0.5412f) }
            val hp2 = Biquad().apply { highpass(30f, 1.3066f) }
            track.play()
            val chunk = ShortArray(fs / 20)
            var i = 0
            while (playing && i < pcm.size) {
                val n = minOf(chunk.size, pcm.size - i)
                for (k in 0 until n) {
                    val y = tanh(hp2.process(hp1.process(pcm[i + k] / 32768f)) * gain)
                    chunk[k] = (y * 32767f).toInt().toShort()
                }
                track.write(chunk, 0, n)
                i += n
                positionSec = i / fs.toFloat()
            }
            try { track.stop() } catch (_: Exception) { }
            track.release()
            playing = false
            positionSec = -1f
            onEnd()
        }, "wav-player").also { it.start() }
    }

    fun stop() {
        playing = false
        thread?.join(500)
        thread = null
        positionSec = -1f
    }
}

/** Muayeneyi Download/Pulsatio/<hasta>_<tarih>/ altina aktarir ve paylasir */
object Exporter {
    fun export(context: Context, store: SessionStore, s: Session): ArrayList<Uri> {
        val folder = "Pulsatio/" + safe(s.patientId) + "_" +
            SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date(s.createdAt))
        val uris = ArrayList<Uri>()
        for (r in s.recordings) {
            val f = store.file(s, r)
            if (!f.exists()) continue
            val name = "${safe(s.patientId)}_${r.site.name}.wav"
            insert(context, folder, name, "audio/x-wav", f.readBytes())?.let { uris.add(it) }
        }
        insert(context, folder, "rapor.json", "application/json",
            s.toJson().toString(2).toByteArray(Charsets.UTF_8))?.let { uris.add(it) }
        insert(context, folder, "rapor.txt", "text/plain",
            Report.text(s).toByteArray(Charsets.UTF_8))?.let { uris.add(it) }
        return uris
    }

    fun shareIntent(uris: ArrayList<Uri>): Intent =
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9_-]"), "_").ifEmpty { "hasta" }

    private fun insert(context: Context, folder: String, name: String, mime: String, data: ByteArray): Uri? = try {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + folder)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            context.contentResolver.openOutputStream(uri)?.use { it.write(data) }
        }
        uri
    } catch (e: Exception) {
        null
    }
}

/** Insan okuyabilir kisa rapor */
object Report {
    fun text(s: Session): String {
        val sb = StringBuilder()
        sb.append("PULSATIO – Oskultasyon raporu (prototip, tanı amaçlı değildir)\n")
        sb.append("==============================================================\n")
        sb.append("Hasta ID : ${s.patientId}\n")
        if (s.name.isNotBlank()) sb.append("Ad Soyad : ${s.name}\n")
        if (s.age.isNotBlank() || s.sex.isNotBlank()) sb.append("Yaş/Cins : ${s.age} ${s.sex}\n")
        sb.append("Tarih    : ${s.dateText()}\n")
        if (s.note.isNotBlank()) sb.append("Not      : ${s.note}\n")
        sb.append("\n")
        for (r in s.recordings.sortedBy { it.site.ordinal }) {
            val a = r.analysis
            sb.append("• ${r.site.label} (${"%.0f".format(r.durationSec)} sn, ${r.mode})\n")
            if (a == null) { sb.append("   analiz yok\n"); continue }
            sb.append("   Nabız ${a.hr ?: "-"} bpm · Ritim ${a.rhythm} · Kalite ${a.qualityLabel} (${a.quality})\n")
            sb.append("   Sistol ${a.systoleMs ?: "-"} ms · Diyastol ${a.diastoleMs ?: "-"} ms · Döngü ${a.cycles}\n")
            for (f in a.flags) sb.append("   ! $f\n")
        }
        sb.append("\nBu rapor mühendislik prototipi çıktısıdır; klinik karar için hekim değerlendirmesi gerekir.\n")
        return sb.toString()
    }
}
