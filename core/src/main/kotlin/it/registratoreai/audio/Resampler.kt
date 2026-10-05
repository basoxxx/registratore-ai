package it.registratoreai.audio

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * Ricampionamento verso 16 kHz che mantiene lo stato tra blocchi successivi.
 * Scendendo di frequenza (44,1/48 kHz → 16 kHz) applica prima un filtro passa-basso
 * (sinc finestrato, taglio ~7 kHz): senza, i suoni acuti (s, z, f) si ripiegano nella banda
 * del parlato come rumore e Whisper sbaglia di più.
 */
class Resampler {
    private var pos = 0.0
    private var last = 0f
    private var rate = 0
    private var taps = FloatArray(0)
    private var hist = FloatArray(0)

    fun process(input: FloatArray, srcRate: Int): FloatArray {
        if (srcRate == SAMPLE_RATE) return input
        if (srcRate < SAMPLE_RATE) return linear(input, srcRate)
        if (srcRate != rate) setup(srcRate)
        val n = taps.size
        // buf = ultimi n campioni del blocco precedente + blocco corrente;
        // l'indice i del blocco corrente corrisponde a buf[i + n]
        val buf = FloatArray(n + input.size)
        hist.copyInto(buf)
        input.copyInto(buf, n)
        val step = srcRate.toDouble() / SAMPLE_RATE
        val out = FloatArray(((input.size - pos) / step).toInt().coerceAtLeast(0) + 2)
        var count = 0
        while (pos < input.size - 1) {
            val i = Math.floor(pos).toInt()
            val frac = (pos - i).toFloat()
            val a = filtered(buf, i + n)
            val b = if (frac == 0f) a else filtered(buf, i + 1 + n)
            out[count++] = a + (b - a) * frac
            pos += step
        }
        pos -= input.size
        buf.copyInto(hist, 0, buf.size - n, buf.size)
        return if (count == out.size) out else out.copyOf(count)
    }

    /** Campione filtrato (causale) all'indice j di buf. */
    private fun filtered(buf: FloatArray, j: Int): Float {
        var s = 0f
        val h = taps
        for (k in h.indices) s += h[k] * buf[j - k]
        return s
    }

    private fun setup(srcRate: Int) {
        rate = srcRate
        val n = 32 * ceil(srcRate / SAMPLE_RATE.toDouble()).toInt() + 1
        val fc = 7_000.0 / srcRate
        val m = (n - 1) / 2.0
        val h = DoubleArray(n) { k ->
            val x = k - m
            val sinc = if (x == 0.0) 2 * fc else sin(2 * PI * fc * x) / (PI * x)
            val w = 0.42 - 0.5 * cos(2 * PI * k / (n - 1)) + 0.08 * cos(4 * PI * k / (n - 1)) // Blackman
            sinc * w
        }
        val sum = h.sum()
        taps = FloatArray(n) { (h[it] / sum).toFloat() }
        hist = FloatArray(n)
        pos = 0.0
    }

    private fun linear(input: FloatArray, srcRate: Int): FloatArray {
        val step = srcRate.toDouble() / SAMPLE_RATE
        val out = ArrayList<Float>((input.size / step).toInt() + 2)
        // pos è relativo all'inizio del blocco corrente; l'indice -1 corrisponde a "last"
        while (pos < input.size - 1) {
            val i = Math.floor(pos).toInt()
            val frac = (pos - i).toFloat()
            val a = if (i < 0) last else input[i]
            val b = input[i + 1]
            out.add(a + (b - a) * frac)
            pos += step
        }
        pos -= input.size
        if (input.isNotEmpty()) last = input[input.size - 1]
        return out.toFloatArray()
    }
}
