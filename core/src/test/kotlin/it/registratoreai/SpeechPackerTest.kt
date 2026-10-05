package it.registratoreai

import it.registratoreai.audio.SAMPLE_RATE
import it.registratoreai.audio.WavReader
import it.registratoreai.audio.WavWriter
import it.registratoreai.transcription.Chunker
import it.registratoreai.transcription.SpeechDetector
import it.registratoreai.transcription.SpeechPacker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

class SpeechPackerTest {
    /** Audio sintetico: "voce" (tono) negli intervalli indicati, silenzio altrove. */
    private fun wav(totalS: Int, speech: List<Pair<Double, Double>>): File {
        val n = totalS * SAMPLE_RATE
        val x = ShortArray(n) { i ->
            val t = i.toDouble() / SAMPLE_RATE
            if (speech.any { t >= it.first && t < it.second }) (sin(2 * PI * 300 * t) * 8000).toInt().toShort() else 0
        }
        return File.createTempFile("packer", ".wav").also { f -> WavWriter(f).use { it.write(x, x.size) } }
    }

    /** VAD finto basato sull'energia, per testare l'impacchettamento senza librerie native. */
    private val energyVad = SpeechDetector { samples ->
        val out = mutableListOf<LongRange>()
        val win = SAMPLE_RATE / 100
        var start = -1L
        var i = 0
        while (i + win <= samples.size) {
            var e = 0f
            for (k in i until i + win) e += samples[k] * samples[k]
            val ms = i * 1000L / SAMPLE_RATE
            if (e / win > 1e-4f) { if (start < 0) start = ms } else if (start >= 0) { out += start..ms; start = -1 }
            i += win
        }
        if (start >= 0) out += start..(samples.size * 1000L / SAMPLE_RATE)
        out
    }

    private fun run(f: File): List<Chunker.Window> {
        val packer = SpeechPacker(energyVad)
        val windows = mutableListOf<Chunker.Window>()
        var offset = 0L
        WavReader(f).use { r ->
            var guard = 0
            while (guard++ < 1000) {
                when (val c = packer.next(r, offset, live = false)) {
                    is Chunker.Chunk.Audio -> { windows += c.window; assertTrue(c.window.endMs > offset); offset = c.window.endMs }
                    is Chunker.Chunk.Skip -> { assertTrue(c.endMs > offset); offset = c.endMs }
                    Chunker.Chunk.End -> return windows
                    Chunker.Chunk.Wait -> error("non deve aspettare")
                }
            }
        }
        error("ciclo infinito")
    }

    @Test
    fun packsSpeechAndSkipsSilence() {
        // 10 frasi da 5 s separate da 10 s di silenzio: 150 s di audio, 50 s di parlato
        val speech = (0 until 10).map { 10.0 + it * 15.0 to 15.0 + it * 15.0 }
        val windows = run(wav(160, speech))
        // Senza VAD servirebbero ~6 finestre da 30 s; con l'impacchettamento ne bastano 2
        assertEquals(2, windows.size)
        windows.forEach { assertTrue(it.samples.size <= SAMPLE_RATE * 29) }
        // I tempi tornano sull'audio originale: la prima frase della seconda finestra
        val firstOfSecond = windows[1].pieces.first()
        assertEquals(windows[1].toSourceMs(firstOfSecond.winStartMs + 1000), firstOfSecond.srcStartMs + 1000)
        assertTrue(windows[0].toSourceMs(0) in 9_500L..10_500L)
    }

    @Test
    fun splitsContinuousSpeech() {
        // 70 s di parlato ininterrotto: finestre piene, nessuna oltre il limite
        val windows = run(wav(75, listOf(2.0 to 72.0)))
        assertTrue(windows.size in 3..4)
        windows.forEach { assertTrue(it.samples.size <= SAMPLE_RATE * 29) }
    }

    @Test
    fun onlySilenceIsSkipped() {
        assertEquals(0, run(wav(200, emptyList())).size)
    }

    @Test
    fun withoutVadFallsBackToChunker() {
        val f = wav(70, listOf(0.0 to 70.0))
        val c = WavReader(f).use { SpeechPacker { null }.next(it, 0, live = false) }
        assertTrue(c is Chunker.Chunk.Audio)
        assertEquals(1, (c as Chunker.Chunk.Audio).window.pieces.size)
    }
}
