package it.registratoreai.transcription

import it.registratoreai.audio.AudioMath
import it.registratoreai.audio.SAMPLE_RATE
import it.registratoreai.audio.WavReader
import it.registratoreai.audio.msToSamples
import it.registratoreai.audio.samplesToMs
import java.io.File

/** Rileva gli intervalli di parlato (ms relativi all'inizio dei campioni). */
fun interface SpeechDetector {
    fun detect(samples: FloatArray): List<LongRange>?
}

/** VAD Silero eseguito da whisper.cpp (modello da ~0,9 MB incluso nell'app). */
class WhisperVad(modelPath: String, threads: Int = 2) : SpeechDetector {
    private val ptr = WhisperLib.vadInit(modelPath, threads)
    val ok: Boolean get() = ptr != 0L

    @Synchronized
    override fun detect(samples: FloatArray): List<LongRange>? {
        if (ptr == 0L) return null
        // Parametri "inclusivi": meglio trascrivere un po' di silenzio che perdere parole.
        // Soglia più bassa per le voci lontane, margine ampio per non tagliare inizi e fine frase.
        val flat = WhisperLib.vadSegments(
            ptr, AudioMath.normalized(samples), threshold = 0.35f, minSpeechMs = 200, minSilenceMs = 500,
            maxSpeechS = 25f, padMs = 400,
        ) ?: return null
        return (flat.indices step 2).map { flat[it]..flat[it + 1] }
    }

    companion object {
        private const val RESOURCE = "/vad/ggml-silero-v5.1.2.bin"

        /** Copia il modello VAD (risorsa dell'app) in [dir] e restituisce il file. */
        fun extractModel(dir: File): File? {
            val out = File(dir, "ggml-silero-v5.1.2.bin")
            if (out.isFile && out.length() > 0) return out
            val input = WhisperVad::class.java.getResourceAsStream(RESOURCE) ?: return null
            dir.mkdirs()
            val tmp = File(dir, out.name + ".tmp")
            input.use { i -> tmp.outputStream().use { i.copyTo(it) } }
            tmp.renameTo(out)
            return out
        }
    }
}

/**
 * Ottimizzazione della trascrizione "dopo la lezione": invece di passare a Whisper blocchi
 * di audio così come sono, rileva le parti parlate (VAD), scarta pause e silenzi e
 * impacchetta il parlato in finestre piene da ~28 s. Whisper elabora sempre 30 s alla
 * volta, quindi meno finestre = meno passaggi dell'encoder = trascrizione più veloce
 * (e niente "allucinazioni" sui silenzi). I tempi vengono poi riportati sull'audio originale.
 */
class SpeechPacker(private val vad: SpeechDetector) {
    companion object {
        const val SPAN_MS = 120_000L        // audio analizzato dal VAD in una volta
        const val MAX_WINDOW_MS = 28_000L   // parlato per finestra (+ brevi pause di raccordo)
        const val JOIN_GAP_MS = 120L        // silenzio inserito tra due frammenti
        private const val MIN_TAIL_MS = 500L
    }

    private var cacheStart = -1L
    private var cacheEnd = -1L
    private var cacheFinal = false
    private var cacheSegs: List<LongRange> = emptyList()

    /** Come [Chunker.nextChunk], ma in differita usa il VAD; durante la registrazione delega al Chunker. */
    fun next(reader: WavReader, offsetMs: Long, live: Boolean): Chunker.Chunk {
        if (live) return Chunker.nextChunk(reader, offsetMs, true)
        val totalMs = samplesToMs(reader.availableSamples())
        if (totalMs - offsetMs <= MIN_TAIL_MS) return Chunker.Chunk.End

        val cacheValid = offsetMs in cacheStart until cacheEnd
        if (!cacheValid || (!cacheFinal && cacheStart < offsetMs && speechAfter(offsetMs) < MAX_WINDOW_MS)) {
            if (!detect(reader, offsetMs, totalMs)) return Chunker.nextChunk(reader, offsetMs, false)
        }

        val segs = cacheSegs.filter { it.last > offsetMs }.map { maxOf(it.first, offsetMs)..it.last }
        if (segs.isEmpty()) return Chunker.Chunk.Skip(cacheEnd)

        val pieces = mutableListOf<Chunker.Window.Piece>()
        var winLen = 0L
        var consumedAll = true
        for (s in segs) {
            val len = s.last - s.first
            if (len <= 0) continue
            val gap = if (pieces.isEmpty()) 0 else JOIN_GAP_MS
            if (winLen + gap + len > MAX_WINDOW_MS) {
                if (pieces.isEmpty()) {
                    // Parlato continuo più lungo di una finestra: si taglia nel punto più silenzioso
                    val x = reader.readFloats(msToSamples(s.first), msToSamples(MAX_WINDOW_MS).toInt())
                    val cut = AudioMath.quietestCut(x, (x.size * 0.75).toInt(), x.size)
                    pieces += Chunker.Window.Piece(0, s.first, samplesToMs(cut.toLong()))
                    winLen = samplesToMs(cut.toLong())
                }
                consumedAll = false
                break
            }
            pieces += Chunker.Window.Piece(winLen + gap, s.first, len)
            winLen += gap + len
        }
        val endMs = if (consumedAll) cacheEnd else pieces.last().let { it.srcStartMs + it.lenMs }

        val gapSamples = msToSamples(JOIN_GAP_MS).toInt()
        val total = msToSamples(winLen).toInt() + gapSamples
        val out = FloatArray(total)
        for (p in pieces) {
            val x = reader.readFloats(msToSamples(p.srcStartMs), msToSamples(p.lenMs).toInt())
            val at = msToSamples(p.winStartMs).toInt()
            System.arraycopy(x, 0, out, at, minOf(x.size, out.size - at))
        }
        return Chunker.Chunk.Audio(Chunker.Window(out, pieces, endMs))
    }

    private fun speechAfter(offsetMs: Long) =
        cacheSegs.filter { it.last > offsetMs }.sumOf { it.last - maxOf(it.first, offsetMs) }

    private fun detect(reader: WavReader, startMs: Long, totalMs: Long): Boolean {
        var endMs = minOf(totalMs, startMs + SPAN_MS)
        var x = reader.readFloats(msToSamples(startMs), msToSamples(endMs - startMs).toInt())
        if (endMs < totalMs) {
            // Il confine dell'analisi cade in una pausa, non a metà di una parola
            val cut = AudioMath.quietestCut(x, maxOf(0, x.size - SAMPLE_RATE * 10), x.size)
            x = x.copyOf(cut)
            endMs = startMs + samplesToMs(cut.toLong())
        }
        val segs = vad.detect(x) ?: return false
        cacheStart = startMs
        cacheEnd = endMs
        cacheFinal = endMs >= totalMs
        cacheSegs = segs.map { (startMs + it.first)..minOf(endMs, startMs + it.last) }.filter { it.last > it.first }
        return true
    }
}
