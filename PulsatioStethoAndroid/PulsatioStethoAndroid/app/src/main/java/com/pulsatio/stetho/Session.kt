package com.pulsatio.stetho

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Oskultasyon noktalari. x/y: govde kutusunda normalize konum, KARSIDAN bakis
 * (hastanin sagi ekranin solunda).
 */
enum class Site(val label: String, val short: String, val x: Float, val y: Float, val hint: String) {
    AORT("Aort", "A", 0.42f, 0.30f, "Sağ 2. interkostal aralık, sternum kenarı"),
    PULMONER("Pulmoner", "P", 0.58f, 0.30f, "Sol 2. interkostal aralık, sternum kenarı"),
    ERB("Erb noktası", "E", 0.58f, 0.41f, "Sol 3. interkostal aralık"),
    TRIKUSPIT("Triküspit", "T", 0.54f, 0.53f, "Sol 4. interkostal aralık, sternum kenarı"),
    MITRAL("Mitral (apeks)", "M", 0.69f, 0.60f, "Sol 5. interkostal, orta klaviküler hat");

    companion object {
        fun of(name: String): Site = values().firstOrNull { it.name == name } ?: AORT
    }
}

/**
 * Kanal tipi. Simdilik sadece PCG (kalp sesi). EKG / SpO2 eklendiginde ayni
 * muayeneye yeni kanal olarak kaydedilir; dosya ve analiz ayni yapida tutulur.
 */
enum class ChannelType(val label: String) { PCG("Kalp sesi (PCG)"), ECG("EKG"), SPO2("SpO₂") }

class Analysis(
    val hr: Int?,
    val rhythm: String,
    val rrCv: Float,
    val systoleMs: Int?,
    val diastoleMs: Int?,
    val cycles: Int,
    val quality: Int,
    val qualityLabel: String,
    val s1s2Ratio: Float?,
    val sysHf: Float?,
    val diaHf: Float?,
    val clipPct: Float,
    val flags: List<String>,
    val s1Times: FloatArray,
    val s2Times: FloatArray
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("hr", hr ?: JSONObject.NULL)
        put("rhythm", rhythm)
        put("rrCv", rrCv.toDouble())
        put("systoleMs", systoleMs ?: JSONObject.NULL)
        put("diastoleMs", diastoleMs ?: JSONObject.NULL)
        put("cycles", cycles)
        put("quality", quality)
        put("qualityLabel", qualityLabel)
        put("s1s2Ratio", s1s2Ratio?.toDouble() ?: JSONObject.NULL)
        put("sysHf", sysHf?.toDouble() ?: JSONObject.NULL)
        put("diaHf", diaHf?.toDouble() ?: JSONObject.NULL)
        put("clipPct", clipPct.toDouble())
        put("flags", JSONArray().apply { flags.forEach { put(it) } })
        put("s1", JSONArray().apply { s1Times.forEach { put(it.toDouble()) } })
        put("s2", JSONArray().apply { s2Times.forEach { put(it.toDouble()) } })
    }

    companion object {
        private fun JSONObject.optIntOrNull(k: String): Int? = if (isNull(k)) null else optInt(k)
        private fun JSONObject.optFloatOrNull(k: String): Float? = if (isNull(k)) null else optDouble(k).toFloat()
        private fun floats(a: JSONArray?): FloatArray =
            if (a == null) FloatArray(0) else FloatArray(a.length()) { a.optDouble(it).toFloat() }

        fun fromJson(o: JSONObject): Analysis = Analysis(
            hr = o.optIntOrNull("hr"),
            rhythm = o.optString("rhythm"),
            rrCv = o.optDouble("rrCv", 0.0).toFloat(),
            systoleMs = o.optIntOrNull("systoleMs"),
            diastoleMs = o.optIntOrNull("diastoleMs"),
            cycles = o.optInt("cycles"),
            quality = o.optInt("quality"),
            qualityLabel = o.optString("qualityLabel"),
            s1s2Ratio = o.optFloatOrNull("s1s2Ratio"),
            sysHf = o.optFloatOrNull("sysHf"),
            diaHf = o.optFloatOrNull("diaHf"),
            clipPct = o.optDouble("clipPct", 0.0).toFloat(),
            flags = o.optJSONArray("flags")?.let { a -> List(a.length()) { a.optString(it) } } ?: emptyList(),
            s1Times = floats(o.optJSONArray("s1")),
            s2Times = floats(o.optJSONArray("s2"))
        )
    }
}

class Recording(
    val site: Site,
    val channel: ChannelType,
    val file: String,
    val fs: Int,
    val durationSec: Float,
    val mode: String,
    val lossPct: Float,
    val createdAt: Long,
    var analysis: Analysis?
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("site", site.name)
        put("channel", channel.name)
        put("file", file)
        put("fs", fs)
        put("durationSec", durationSec.toDouble())
        put("mode", mode)
        put("lossPct", lossPct.toDouble())
        put("createdAt", createdAt)
        put("analysis", analysis?.toJson() ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(o: JSONObject): Recording = Recording(
            site = Site.of(o.optString("site")),
            channel = try { ChannelType.valueOf(o.optString("channel", "PCG")) } catch (e: Exception) { ChannelType.PCG },
            file = o.optString("file"),
            fs = o.optInt("fs", AudioEngine.FS),
            durationSec = o.optDouble("durationSec", 0.0).toFloat(),
            mode = o.optString("mode"),
            lossPct = o.optDouble("lossPct", 0.0).toFloat(),
            createdAt = o.optLong("createdAt"),
            analysis = o.optJSONObject("analysis")?.let { Analysis.fromJson(it) }
        )
    }
}

class Session(
    val id: String,
    var patientId: String,
    var name: String,
    var age: String,
    var sex: String,
    var note: String,
    val createdAt: Long,
    val recordings: MutableList<Recording> = ArrayList()
) {
    /** Ayni nokta + kanal icin son kaydi tut (yeniden kayit eskisinin yerine gecer) */
    fun put(r: Recording): Recording? {
        val i = recordings.indexOfFirst { it.site == r.site && it.channel == r.channel }
        return if (i >= 0) {
            val old = recordings[i]
            recordings[i] = r
            old
        } else {
            recordings.add(r)
            null
        }
    }

    fun doneSites(): Set<Site> = recordings.filter { it.channel == ChannelType.PCG }.map { it.site }.toSet()

    fun medianHr(): Int? {
        val v = recordings.mapNotNull { it.analysis?.hr }.sorted()
        return if (v.isEmpty()) null else v[v.size / 2]
    }

    fun meanQuality(): Int? {
        val v = recordings.mapNotNull { it.analysis?.quality }
        return if (v.isEmpty()) null else v.sum() / v.size
    }

    fun dateText(): String = SimpleDateFormat("d MMM yyyy · HH:mm", Locale("tr")).format(Date(createdAt))

    fun toJson(): JSONObject = JSONObject().apply {
        put("format", "pulsatio-session/1")
        put("id", id)
        put("patientId", patientId)
        put("name", name)
        put("age", age)
        put("sex", sex)
        put("note", note)
        put("createdAt", createdAt)
        put("channels", JSONArray().apply { recordings.map { it.channel.name }.distinct().forEach { put(it) } })
        put("recordings", JSONArray().apply { recordings.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): Session {
            val s = Session(
                id = o.optString("id"),
                patientId = o.optString("patientId"),
                name = o.optString("name"),
                age = o.optString("age"),
                sex = o.optString("sex"),
                note = o.optString("note"),
                createdAt = o.optLong("createdAt")
            )
            val a = o.optJSONArray("recordings")
            if (a != null) for (i in 0 until a.length()) {
                val r = a.optJSONObject(i) ?: continue
                s.recordings.add(Recording.fromJson(r))
            }
            return s
        }
    }
}

/** Muayeneler uygulamanin kendi klasorunde: files/sessions/<id>/session.json + WAV'lar */
class SessionStore(context: Context) {
    private val root = File(context.filesDir, "sessions").apply { mkdirs() }

    fun dir(s: Session): File = File(root, s.id).apply { mkdirs() }
    fun file(s: Session, r: Recording): File = File(dir(s), r.file)

    fun create(patientId: String, name: String, age: String, sex: String, note: String): Session {
        val now = System.currentTimeMillis()
        val id = "S" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))
        val s = Session(id, patientId.trim(), name.trim(), age.trim(), sex, note.trim(), now)
        save(s)
        return s
    }

    fun newRecordingFile(s: Session, site: Site): String =
        "${site.name}_${SimpleDateFormat("HHmmss", Locale.US).format(Date())}.wav"

    fun save(s: Session) {
        val f = File(dir(s), "session.json")
        val tmp = File(dir(s), "session.json.tmp")
        tmp.writeText(s.toJson().toString(2))
        if (!tmp.renameTo(f)) {
            f.writeText(tmp.readText())
            tmp.delete()
        }
    }

    fun list(): List<Session> {
        val out = ArrayList<Session>()
        val dirs = root.listFiles() ?: return out
        for (d in dirs) {
            val f = File(d, "session.json")
            if (!f.exists()) continue
            try { out.add(Session.fromJson(JSONObject(f.readText()))) } catch (_: Exception) { }
        }
        out.sortByDescending { it.createdAt }
        return out
    }

    fun delete(s: Session) {
        dir(s).deleteRecursively()
    }

    fun recentPatients(limit: Int = 8): List<Session> =
        list().distinctBy { it.patientId }.take(limit)
}
