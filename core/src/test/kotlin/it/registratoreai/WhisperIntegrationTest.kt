package it.registratoreai

import it.registratoreai.audio.SAMPLE_RATE
import it.registratoreai.audio.WavReader
import it.registratoreai.audio.WavWriter
import it.registratoreai.transcription.TextCleaner
import it.registratoreai.transcription.Chunker
import it.registratoreai.transcription.WhisperEngine
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Test end-to-end del motore nativo (eseguito solo se configurato):
 *   WHISPER_JNI_DIR=<cartella con libwhisper_jni.so per l'host>
 *   WHISPER_MODEL=<file ggml-*.bin>  WHISPER_PCM=<audio s16le 16 kHz mono>
 */
class WhisperIntegrationTest {
    @Test
    fun transcribesItalianLectureInChunks() {
        val model = System.getenv("WHISPER_MODEL")
        val pcm = System.getenv("WHISPER_PCM")
        assumeTrue(model != null && pcm != null)

        val bytes = File(pcm!!).readBytes()
        val sb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = ShortArray(sb.remaining()).also { sb.get(it) }
        val wav = File.createTempFile("lezione", ".wav")
        WavWriter(wav).use { it.write(samples, samples.size) }

        val engine = WhisperEngine()
        assertTrue(engine.ensureLoaded(model!!))
        val out = StringBuilder()
        var offsetMs = 0L
        var chunks = 0
        WavReader(wav).use { r ->
            while (true) {
                val c = Chunker.nextChunk(r, offsetMs, live = false)
                if (c !is Chunker.Chunk.Audio) break
                val t0 = System.currentTimeMillis()
                val segs = engine.transcribe(c.samples, "it", "Analisi matematica", 4)!!
                val lenMs = c.samples.size * 1000L / SAMPLE_RATE
                println("Blocco ${++chunks}: ${lenMs} ms di audio in ${System.currentTimeMillis() - t0} ms")
                segs.forEach { s -> TextCleaner.clean(s.text)?.let { println("  [${offsetMs + s.startMs}] $it"); out.append(it).append(' ') } }
                offsetMs += lenMs
            }
        }
        engine.release()
        val text = out.toString().lowercase()
        assertTrue(chunks >= 3)
        assertTrue(text, text.contains("teorema fondamentale"))
        assertTrue(text, text.contains("integral"))
    }
}
