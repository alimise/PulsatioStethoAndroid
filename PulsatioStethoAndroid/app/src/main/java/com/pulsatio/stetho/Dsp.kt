package com.pulsatio.stetho

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** 2. derece IIR filtre (RBJ), ESP'deki Biquad ile ayni formuller. */
class Biquad(private val fs: Float = AudioEngine.FS.toFloat()) {
    private var b0 = 1f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f
    private var z1 = 0f
    private var z2 = 0f

    fun lowpass(fc: Float, q: Float) = design(fc, q, false)
    fun highpass(fc: Float, q: Float) = design(fc, q, true)

    private fun design(fc: Float, q: Float, high: Boolean) {
        val w0 = 2.0 * PI * fc / fs
        val c = cos(w0)
        val s = sin(w0)
        val alpha = s / (2.0 * q)
        val a0 = 1.0 + alpha
        val n0: Double
        val n1: Double
        val n2: Double
        if (high) {
            n0 = (1.0 + c) / 2.0; n1 = -(1.0 + c); n2 = (1.0 + c) / 2.0
        } else {
            n0 = (1.0 - c) / 2.0; n1 = 1.0 - c; n2 = (1.0 - c) / 2.0
        }
        b0 = (n0 / a0).toFloat()
        b1 = (n1 / a0).toFloat()
        b2 = (n2 / a0).toFloat()
        a1 = (-2.0 * c / a0).toFloat()
        a2 = ((1.0 - alpha) / a0).toFloat()
        reset()
    }

    fun reset() {
        z1 = 0f
        z2 = 0f
    }

    fun process(x: Float): Float {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }
}

/** ESP'nin MTU=23 acil durum modundaki 'B' blok ADPCM paketlerini cozer. */
class ImaAdpcm {
    private val indexTable = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8)
    private val stepTable = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17,
        19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
        50, 55, 60, 66, 73, 80, 88, 97, 107, 118,
        130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
        337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
        876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
        2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358,
        5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
        15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
    )

    fun decodeBlock(first: Short, d: ByteArray, off: Int, nBytes: Int): ShortArray {
        var pred = first.toInt()
        var index = 0
        val out = ShortArray(1 + nBytes * 2)
        out[0] = first
        var j = 1
        for (b in 0 until nBytes) {
            val byte = d[off + b].toInt() and 0xFF
            for (k in 0 until 2) {
                val code = if (k == 0) byte and 0x0F else (byte shr 4) and 0x0F
                val step = stepTable[index]
                var diff = step shr 3
                if (code and 4 != 0) diff += step
                if (code and 2 != 0) diff += step shr 1
                if (code and 1 != 0) diff += step shr 2
                pred = if (code and 8 != 0) pred - diff else pred + diff
                pred = pred.coerceIn(-32768, 32767)
                index = (index + indexTable[code]).coerceIn(0, 88)
                out[j++] = pred.toShort()
            }
        }
        return out
    }
}
