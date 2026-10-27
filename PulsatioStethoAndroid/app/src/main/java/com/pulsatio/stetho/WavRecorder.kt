package com.pulsatio.stetho

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 8 kHz / 16 bit mono WAV kaydi. Dosya telefonda
 * Download/Pulsatio/ klasorune yazilir (ek izin gerekmez).
 * Bu dosyalar PC'deki Python HSMM raporlayicisiyla dogrudan analiz edilebilir.
 */
class WavRecorder(private val context: Context) {

    private var pfd: ParcelFileDescriptor? = null
    private var channel: FileChannel? = null
    private var dataBytes = 0L

    var displayName = ""
        private set

    @Synchronized
    fun start(): Boolean {
        return try {
            val name = "stetho_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".wav"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/x-wav")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Pulsatio")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return false
            val p = context.contentResolver.openFileDescriptor(uri, "rw") ?: return false
            val ch = FileOutputStream(p.fileDescriptor).channel
            ch.write(header(0))
            pfd = p
            channel = ch
            dataBytes = 0
            displayName = name
            true
        } catch (e: Exception) {
            false
        }
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
            ch.write(header(dataBytes), 0)   // gercek uzunluklarla basligi yeniden yaz
            ch.force(true)
            ch.close()
        } catch (_: Exception) {
        }
        try { pfd?.close() } catch (_: Exception) { }
        channel = null
        pfd = null
    }

    private fun header(data: Long): ByteBuffer {
        val fs = AudioEngine.FS
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray(Charsets.US_ASCII))
        b.putInt((36 + data).toInt())
        b.put("WAVE".toByteArray(Charsets.US_ASCII))
        b.put("fmt ".toByteArray(Charsets.US_ASCII))
        b.putInt(16)                 // fmt chunk boyu
        b.putShort(1.toShort())      // PCM
        b.putShort(1.toShort())      // mono
        b.putInt(fs)
        b.putInt(fs * 2)             // byte rate
        b.putShort(2.toShort())      // block align
        b.putShort(16.toShort())     // bit
        b.put("data".toByteArray(Charsets.US_ASCII))
        b.putInt(data.toInt())
        b.flip()
        return b
    }
}
