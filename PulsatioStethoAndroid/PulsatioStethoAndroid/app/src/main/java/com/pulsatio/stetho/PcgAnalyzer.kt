package com.pulsatio.stetho

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Kayit sonrasi kalp sesi (PCG) analizi – cihazda, birkac milisaniyede.
 *
 *  1) 25-400 Hz bant (ileri-geri filtre: faz kaymasi yok)
 *  2) Zarf (dogrultma + 15 Hz alcak geciren), 100 Hz'e seyreltme
 *  3) Otokorelasyon -> kalp periyodu T ve periyodiklik gucu
 *  4) Zarf tepeleri; T'nin yarisindan kisa araliklar sistol -> (S1, S2) cifti
 *  5) S1-S1 araliklarindan nabiz ve ritim duzeni (degisim katsayisi)
 *  6) 150-400 Hz bandinda sistol/diyastol enerjisinin S1 enerjisine orani
 *     (uzun sureli yuksek frekans aktivitesi = ufurum acisindan "gosterge")
 *  7) Sinyal kalitesi puani
 *
 * Bu bir tani algoritmasi DEGILDIR; prototip / tarama gostergesidir.
 */
object PcgAnalyzer {

    private class F(val high: Boolean, val fc: Float, val q: Float)

    private fun filtfilt(x: FloatArray, fs: Int, specs: List<F>): FloatArray {
        val y = x.copyOf()
        fun pass() {
            val bq = specs.map { s ->
                Biquad(fs.toFloat()).apply { if (s.high) highpass(s.fc, s.q) else lowpass(s.fc, s.q) }
            }
            for (i in y.indices) {
                var v = y[i]
                for (b in bq) v = b.process(v)
                y[i] = v
            }
        }
        pass()
        y.reverse()
        pass()
        y.reverse()
        return y
    }

    private fun percentile(a: FloatArray, p: Float): Float {
        if (a.isEmpty()) return 0f
        val s = a.copyOf()
        s.sort()
        return s[((s.size - 1) * p).toInt().coerceIn(0, s.size - 1)]
    }

    private fun median(v: List<Float>): Float? {
        if (v.isEmpty()) return null
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f
    }

    private fun energy(x: FloatArray, a: Int, b: Int): Float {
        val i0 = a.coerceIn(0, x.size)
        val i1 = b.coerceIn(0, x.size)
        if (i1 - i0 < 8) return 0f
        var e = 0.0
        for (i in i0 until i1) e += (x[i] * x[i]).toDouble()
        return (e / (i1 - i0)).toFloat()
    }

    fun analyze(pcm: ShortArray, fs: Int, lossPct: Float): Analysis {
        val n = pcm.size
        val flags = ArrayList<String>()
        var clip = 0
        for (v in pcm) if (abs(v.toInt()) >= 32000) clip++
        val clipPct = 100f * clip / max(1, n)
        val durSec = n / fs.toFloat()

        if (durSec < 3f) {
            return Analysis(null, "Belirsiz", 0f, null, null, 0, 0, "Yetersiz", null, null, null,
                clipPct, listOf("Kayıt çok kısa (en az 5 sn önerilir)"), FloatArray(0), FloatArray(0))
        }

        val x = FloatArray(n) { pcm[it] / 32768f }
        val band = filtfilt(x, fs, listOf(F(true, 25f, 0.5412f), F(true, 25f, 1.3066f),
            F(false, 400f, 0.5412f), F(false, 400f, 1.3066f)))
        val hf = filtfilt(x, fs, listOf(F(true, 150f, 0.5412f), F(true, 150f, 1.3066f),
            F(false, 400f, 0.7071f)))
        for (i in band.indices) band[i] = abs(band[i])
        val envFull = filtfilt(band, fs, listOf(F(false, 15f, 0.7071f)))

        // 100 Hz zarf
        val dec = fs / 100
        val m = n / dec
        val env = FloatArray(m) { i ->
            var s = 0f
            for (k in 0 until dec) s += envFull[i * dec + k]
            s / dec
        }
        val p95 = max(1e-9f, percentile(env, 0.95f))
        for (i in env.indices) env[i] = env[i] / p95

        // ---- periyot (otokorelasyon) ----
        var mean = 0f
        for (v in env) mean += v
        mean /= m
        val e0 = FloatArray(m) { env[it] - mean }
        var r0 = 0f
        for (v in e0) r0 += v * v
        val minLag = 40      // 0.4 sn (150 bpm)
        val maxLag = min(180, m / 2)
        var bestLag = -1
        var bestR = 0f
        val r = FloatArray(maxLag + 2)
        if (r0 > 0f && maxLag > minLag) {
            for (lag in minLag - 1..maxLag + 1) {
                var acc = 0f
                for (i in 0 until m - lag) acc += e0[i] * e0[i + lag]
                r[lag] = acc / r0
            }
            for (lag in minLag..maxLag) if (r[lag] > bestR) { bestR = r[lag]; bestLag = lag }
            // iki atimlik tepe yerine ilk guclu tepe
            for (lag in minLag..maxLag) {
                if (r[lag] >= 0.85f * bestR && r[lag] >= r[lag - 1] && r[lag] >= r[lag + 1]) { bestLag = lag; break }
            }
        }
        val periodic = bestLag > 0 && bestR >= 0.25f
        val T = if (periodic) bestLag else 0

        // ---- aday tepeler (dusuk esik, en az 100 ms aralik) ----
        val cand = ArrayList<Int>()
        for (i in 1 until m - 1) {
            if (env[i] > 0.10f && env[i] >= env[i - 1] && env[i] > env[i + 1]) {
                val last = cand.lastOrNull()
                if (last != null && i - last < 10) {
                    if (env[i] > env[last]) cand[cand.size - 1] = i
                } else {
                    cand.add(i)
                }
            }
        }

        // ---- atim takibi: her dongude bir baskin ses, araliklar ~T olacak sekilde ----
        // score[i] = env[i] + max_j ( score[j] - L * ln(d/T)^2 ),  d = 0.65T..1.4T
        val beats = ArrayList<Int>()
        if (T > 0 && cand.size >= 3) {
            val nC = cand.size
            val score = FloatArray(nC)
            val prev = IntArray(nC) { -1 }
            val lam = 6f
            for (i in 0 until nC) {
                score[i] = env[cand[i]]
                var bestS = Float.NEGATIVE_INFINITY
                var bestJ = -1
                for (j in i - 1 downTo 0) {
                    val d = (cand[i] - cand[j]).toFloat()
                    if (d > 2.6f * T) break
                    if (d < 0.65f * T) continue
                    val sc = if (d <= 1.4f * T) {
                        val lr = kotlin.math.ln(d / T)
                        score[j] - lam * lr * lr
                    } else {
                        // bir atim kacirilmis (gurultu/hareket): cezali bosluk gecisi
                        val lr = kotlin.math.ln(d / (2f * T))
                        score[j] - lam * lr * lr - 1.5f
                    }
                    if (sc > bestS) { bestS = sc; bestJ = j }
                }
                if (bestJ >= 0) { score[i] += bestS; prev[i] = bestJ }
            }
            var end = -1
            var endS = Float.NEGATIVE_INFINITY
            for (i in 0 until nC) if (score[i] > endS) { endS = score[i]; end = i }   // puan birikimli: en iyi zincirin sonu
            var k = end
            while (k >= 0) { beats.add(cand[k]); k = prev[k] }
            beats.reverse()
        }

        // ---- ikinci ses ----
        // Iki atim arasindaki en guclu ses aranir; ancak beklenen konumlara
        // (atimdan ~sistol sonra veya sonraki atimdan ~sistol once) yakin olanlar
        // tercih edilir. Boylece ufurum tumsegi S2 sanilmaz.
        // Beklenen sistol: S1-S2 ~ 500 - 2.1*nabiz ms.
        val nB = beats.size
        val hrT = if (T > 0) 6000f / T else 75f
        val expSys = ((500f - 2.1f * hrT) / 10f).coerceIn(20f, 45f)
        val partner = IntArray(max(0, nB - 1)) { -1 }
        val frac = ArrayList<Float>()
        for (k in 0 until nB - 1) {
            val a = beats[k]
            val b = beats[k + 1]
            if (b - a > 1.5f * T) continue
            val guard = (0.15f * (b - a)).toInt()
            var best = -1
            var bestW = 0f
            for (c in cand) {
                if (c < a + guard || c > b - guard) continue
                val d1 = c - (a + expSys)
                val d2 = c - (b - expSys)
                val prior = max(exp(-d1 * d1 / 128f), exp(-d2 * d2 / 128f))   // sigma ~80 ms
                val wv = env[c] * (0.25f + 0.75f * prior)
                if (wv > bestW) { bestW = wv; best = c }
            }
            if (best >= 0 && env[best] < 0.12f * min(env[a], env[b])) best = -1
            partner[k] = best
            if (best >= 0) frac.add((best - a).toFloat() / (b - a))
        }

        // Karar: ikinci ses dongunun ilk yarisindaysa atimlar S1 (sistol < diyastol).
        // Belirsiz bolgede (0.46-0.54) ses perdesine bak: S2 daha tizdir.
        fun pitch(idx: Int): Float {
            val c = idx * dec
            val eh = energy(hf, c - 30 * (fs / 1000), c + 50 * (fs / 1000))
            var eb = 0f
            val i0 = (c - 30 * (fs / 1000)).coerceIn(0, n)
            val i1 = (c + 50 * (fs / 1000)).coerceIn(0, n)
            for (i in i0 until i1) eb += envFull[i] * envFull[i]
            eb /= max(1, i1 - i0)
            return if (eb > 0f) eh / eb else 0f
        }
        val fm = median(frac) ?: 0.4f
        val beatsAreS1 = when {
            fm < 0.46f -> true
            fm > 0.54f -> false
            else -> {
                val pb = median(beats.take(40).map { pitch(it) }) ?: 0f
                val pp = median(partner.filter { it >= 0 }.take(40).map { pitch(it) }) ?: 0f
                pb <= pp
            }
        }

        val s1 = ArrayList<Int>()
        val s2 = ArrayList<Int>()
        val sys = ArrayList<Float>()
        val dia = ArrayList<Float>()
        val rr = ArrayList<Float>()
        for (k in 0 until nB - 1) {
            val d = (beats[k + 1] - beats[k]).toFloat()
            if (d <= 1.5f * T) rr.add(d)
            val pk = partner[k]
            if (pk < 0) continue
            if (beatsAreS1) {
                // dongu: S1 = atim, S2 = ikinci ses, sonraki S1 = sonraki atim
                s1.add(beats[k]); s2.add(pk)
                sys.add((pk - beats[k]) * 10f)
                dia.add((beats[k + 1] - pk) * 10f)
            } else {
                // dongu: S1 = ikinci ses, S2 = sonraki atim, sonraki S1 = bir sonraki ikinci ses
                s1.add(pk); s2.add(beats[k + 1])
                sys.add((beats[k + 1] - pk) * 10f)
                if (k + 1 < nB - 1 && partner[k + 1] >= 0) dia.add((partner[k + 1] - beats[k + 1]) * 10f)
            }
        }
        val cycles = rr.size

        val hr: Int? = when {
            rr.size >= 3 -> (6000f / median(rr)!!).roundToInt()
            T > 0 -> (6000f / T).roundToInt()
            else -> null
        }
        var cv = 0f
        if (rr.size >= 3) {
            val mu = rr.average().toFloat()
            var v = 0f
            for (x0 in rr) v += (x0 - mu) * (x0 - mu)
            cv = sqrt(v / rr.size) / mu
        }
        val rhythm = when {
            rr.size < 3 -> "Belirsiz"
            cv < 0.08f -> "Düzenli"
            cv < 0.15f -> "Hafif düzensiz"
            else -> "Düzensiz"
        }

        // S1/S2 genlik orani
        val s1s2 = if (s1.size >= 3) {
            val a = median(s1.map { env[it] })
            val b = median(s2.map { env[it] })
            if (a != null && b != null && b > 0f) a / b else null
        } else null

        // ---- yuksek frekans gostergesi ----
        val sysR = ArrayList<Float>()
        val diaR = ArrayList<Float>()
        val ms = fs / 1000
        // 150-400 Hz gurultu tabani: 20 ms pencere enerjilerinin %20'lik dilimi.
        // Mikrofon hisirtisi sistol ve diyastolde ayni oldugu icin cikarilir.
        val winE = FloatArray(n / (20 * ms)) { w -> energy(hf, w * 20 * ms, (w + 1) * 20 * ms) }
        val floorE = percentile(winE, 0.20f)
        fun excess(e: Float) = max(0f, e - floorE)
        for (i in s1.indices) {
            val a = s1[i] * dec
            val b = s2[i] * dec
            val ref = excess(energy(hf, a - 40 * ms, a + 60 * ms))
            if (ref <= 1e-12f) continue
            if (b - a > 140 * ms) sysR.add(excess(energy(hf, a + 70 * ms, b - 40 * ms)) / ref)
            if (i + 1 < s1.size) {
                val c = s1[i + 1] * dec
                if (c - b > 220 * ms && c - b < 2000 * ms) diaR.add(excess(energy(hf, b + 80 * ms, c - 80 * ms)) / ref)
            }
        }
        val sysHf = median(sysR)
        val diaHf = median(diaR)

        // ---- kalite ----
        val coverage = if (T > 0) min(1f, cycles * T / (m.toFloat())) else 0f
        var q = 45f * min(1f, bestR / 0.65f) + 30f * coverage +
            15f * (1f - min(1f, clipPct / 1f)) + 10f * (1f - min(1f, lossPct / 10f))
        if (!periodic) q = min(q, 35f)
        val quality = q.roundToInt().coerceIn(0, 100)
        val qLabel = when {
            quality >= 70 -> "İyi"
            quality >= 45 -> "Orta"
            else -> "Zayıf"
        }

        // ---- uyarilar ----
        if (quality < 45) flags.add("Sinyal kalitesi düşük – göğüs başlığını sabitleyip tekrar kaydedin")
        if (clipPct > 0.3f) flags.add("Kırpılma var (%${"%.1f".format(clipPct)}) – kazancı azaltın")
        if (lossPct > 3f) flags.add("Bluetooth paket kaybı %${"%.1f".format(lossPct)}")
        if (hr != null && quality >= 45) {
            if (hr > 100) flags.add("Nabız 100 bpm üzerinde")
            if (hr < 50) flags.add("Nabız 50 bpm altında")
        }
        if (rhythm == "Düzensiz") flags.add("Kalp döngüleri arasında belirgin düzensizlik")
        if (sysHf != null && sysHf > 1.0f && quality >= 45)
            flags.add("Sistolde uzun süreli yüksek frekanslı aktivite – üfürüm açısından değerlendirilmeli")
        if (diaHf != null && diaHf > 1.0f && quality >= 45)
            flags.add("Diyastolde yüksek frekanslı aktivite – değerlendirilmeli")

        return Analysis(
            hr = hr,
            rhythm = rhythm,
            rrCv = cv,
            systoleMs = median(sys)?.roundToInt(),
            diastoleMs = median(dia)?.roundToInt(),
            cycles = cycles,
            quality = quality,
            qualityLabel = qLabel,
            s1s2Ratio = s1s2,
            sysHf = sysHf,
            diaHf = diaHf,
            clipPct = clipPct,
            flags = flags,
            s1Times = FloatArray(s1.size) { s1[it] / 100f },
            s2Times = FloatArray(s2.size) { s2[it] / 100f }
        )
    }
}
