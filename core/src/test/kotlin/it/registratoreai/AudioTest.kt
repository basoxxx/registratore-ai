package it.registratoreai

import it.registratoreai.audio.AudioMath
import it.registratoreai.audio.Resampler
import it.registratoreai.audio.SAMPLE_RATE
import it.registratoreai.audio.WAV_HEADER_SIZE
import it.registratoreai.audio.WavReader
import it.registratoreai.audio.WavWriter
import it.registratoreai.transcription.Chunker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.sin

class AudioTest {
    private fun tone(seconds: Double, amp: Double = 0.3) =
        ShortArray((seconds * SAMPLE_RATE).toInt()) { (sin(2 * PI * 440 * it / SAMPLE_RATE) * amp * 32767).toInt().toShort() }

    private fun silence(seconds: Double) = ShortArray((seconds * SAMPLE_RATE).toInt())

    @Test
    fun wavRoundTripAndHeader() {
        val f = File.createTempFile("test", ".wav")
        val t = tone(1.0)
        WavWriter(f).use { it.write(t, t.size) }
        assertEquals(WAV_HEADER_SIZE + t.size * 2, f.length())
        val header = ByteArray(44).also { RandomAccessFile(f, "r").use { r -> r.readFully(it) } }
        assertEquals("RIFF", String(header, 0, 4))
        assertEquals("data", String(header, 36, 4))
        val dataLen = (header[40].toInt() and 0xff) or ((header[41].toInt() and 0xff) shl 8) or
            ((header[42].toInt() and 0xff) shl 16) or ((header[43].toInt() and 0xff) shl 24)
        assertEquals(t.size * 2, dataLen)
        WavReader(f).use { r ->
            assertEquals(t.size.toLong(), r.availableSamples())
            val x = r.readFloats(100, 10)
            assertEquals(t[100] / 32768f, x[0], 1e-6f)
        }
    }

    @Test
    fun repairFixesTruncatedRecording() {
        val f = File.createTempFile("test", ".wav")
        // Simula un'app chiusa all'improvviso: dati scritti ma header mai aggiornato
        val w = WavWriter(f)
        val t = tone(2.0)
        w.write(t, t.size)
        // niente close()
        val ms = WavWriter.repair(f)
        assertEquals(2000, ms)
        WavReader(f).use { assertEquals(t.size.toLong(), it.availableSamples()) }
    }

    @Test
    fun appendContinuesExistingFile() {
        val f = File.createTempFile("test", ".wav")
        val t = tone(1.0)
        WavWriter(f).use { it.write(t, t.size) }
        WavWriter(f, append = true).use { it.write(t, t.size); assertEquals(2L * t.size, it.samplesWritten) }
        WavReader(f).use { assertEquals(2L * t.size, it.availableSamples()) }
    }

    @Test
    fun quietestCutFindsPause() {
        // 25 s di "voce", 0,5 s di pausa, poi ancora voce
        val x = FloatArray(SAMPLE_RATE * 29) { i ->
            val sec = i.toDouble() / SAMPLE_RATE
            if (sec in 25.0..25.5) 0f else (sin(2 * PI * 300 * sec) * 0.3).toFloat()
        }
        val cut = AudioMath.quietestCut(x, SAMPLE_RATE * 22, x.size)
        val cutSec = cut.toDouble() / SAMPLE_RATE
        assertTrue("taglio a $cutSec s", cutSec in 25.0..25.5)
    }

    @Test
    fun chunkingFollowsGrowingFile() {
        val f = File.createTempFile("test", ".wav")
        val w = WavWriter(f)
        val first = tone(10.0)
        w.write(first, first.size); w.syncHeader()
        WavReader(f).use { r ->
            // Durante la registrazione con solo 10 s di audio: si aspetta
            assertTrue(Chunker.nextChunk(r, 0, live = true) is Chunker.Chunk.Wait)
            val more = tone(13.0)
            val gap = silence(0.6)
            val rest = tone(20.0)
            w.write(more, more.size); w.write(gap, gap.size); w.write(rest, rest.size); w.syncHeader()
            val c = Chunker.nextChunk(r, 0, live = true)
            assertTrue(c is Chunker.Chunk.Audio)
            val lenSec = (c as Chunker.Chunk.Audio).samples.size.toDouble() / SAMPLE_RATE
            assertTrue("blocco di $lenSec s tagliato nella pausa", lenSec in 23.0..23.6)
            // Fine registrazione: la coda viene trascritta, poi End
            val offset = (lenSec * 1000).toLong()
            val tail = Chunker.nextChunk(r, offset, live = false)
            assertTrue(tail is Chunker.Chunk.Audio)
            val total = offset + (tail as Chunker.Chunk.Audio).samples.size * 1000L / SAMPLE_RATE
            assertTrue(Chunker.nextChunk(r, total, live = false) is Chunker.Chunk.End)
        }
        w.close()
    }

    private fun rmsOf(x: FloatArray, from: Int) =
        kotlin.math.sqrt(x.drop(from).sumOf { (it * it).toDouble() } / (x.size - from))

    @Test
    fun resamplerKeepsSpeechAndRemovesAliasing() {
        val rate = 48_000
        fun at(freq: Double) = FloatArray(rate * 2) { (sin(2 * PI * freq * it / rate) * 10_000).toFloat() }
        // a blocchi di 100 ms, come durante la registrazione
        fun resample(x: FloatArray): FloatArray {
            val r = Resampler()
            return x.toList().chunked(rate / 10).map { r.process(it.toFloatArray(), rate) }
                .fold(FloatArray(0)) { a, b -> a + b }
        }
        val speech = resample(at(1_000.0))
        assertTrue(kotlin.math.abs(speech.size - 2 * SAMPLE_RATE) <= 2)
        assertEquals(10_000 / kotlin.math.sqrt(2.0), rmsOf(speech, 1000), 300.0)
        // 12 kHz a 48 kHz si ripiegherebbe a 4 kHz: deve sparire
        assertTrue(rmsOf(resample(at(12_000.0)), 1000) < 50)
        // a blocchi o tutto insieme: stesso risultato
        val whole = Resampler().process(at(1_000.0), rate)
        assertEquals(whole.size, speech.size)
        for (i in whole.indices) assertEquals(whole[i], speech[i], 0.5f)
    }
}
