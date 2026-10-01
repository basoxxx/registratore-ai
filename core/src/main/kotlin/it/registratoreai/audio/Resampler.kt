package it.registratoreai.audio

/** Ricampionamento lineare verso 16 kHz che mantiene lo stato tra blocchi successivi. */
class Resampler {
    private var pos = 0.0
    private var last = 0f

    fun process(input: FloatArray, srcRate: Int): FloatArray {
        if (srcRate == SAMPLE_RATE) return input
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
