package it.registratoreai.desktop

import it.registratoreai.audio.Resampler
import it.registratoreai.audio.SAMPLE_RATE
import it.registratoreai.audio.WavWriter
import it.registratoreai.audio.samplesToMs
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Registra dal microfono in un WAV 16 kHz mono. Se la scheda audio non supporta
 * direttamente 16 kHz, registra a 44,1/48 kHz e ricampiona in software.
 * L'header del WAV viene aggiornato ogni 5 secondi: il file è sempre integro.
 */
class Recorder(
    private val file: File,
    private val onProgress: (elapsedMs: Long, level: Float) -> Unit,
    private val onCheckpoint: (elapsedMs: Long) -> Unit,
    private val onError: (String) -> Unit,
) {
    @Volatile var paused = false
    @Volatile private var stopRequested = false
    private var worker: Thread? = null
    var elapsedMs: Long = 0
        private set

    fun start() {
        val line = openLine() ?: run {
            onError("Nessun microfono disponibile (controlla i permessi del microfono nelle impostazioni di sistema).")
            return
        }
        worker = thread(name = "recorder", isDaemon = true) { loop(line) }
    }

    fun stop() {
        stopRequested = true
        worker?.join(5_000)
    }

    private fun openLine(): TargetDataLine? {
        val rates = listOf(16_000f, 48_000f, 44_100f)
        for (rate in rates) for (channels in listOf(1, 2)) {
            val fmt = AudioFormat(rate, 16, channels, true, false)
            val info = DataLine.Info(TargetDataLine::class.java, fmt)
            if (!AudioSystem.isLineSupported(info)) continue
            try {
                val line = AudioSystem.getLine(info) as TargetDataLine
                line.open(fmt, (rate * channels * 2 / 5).toInt()) // buffer ~200 ms
                return line
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun loop(line: TargetDataLine) {
        val fmt = line.format
        val channels = fmt.channels
        val rate = fmt.sampleRate.toInt()
        val resampler = Resampler()
        val bytes = ByteArray(rate * channels * 2 / 10) // 100 ms
        val writer = WavWriter(file)
        var lastSync = 0L
        try {
            line.start()
            while (!stopRequested) {
                val n = line.read(bytes, 0, bytes.size)
                if (n <= 0) continue
                if (paused) continue
                val frames = n / (2 * channels)
                val mono = FloatArray(frames)
                for (f in 0 until frames) {
                    var s = 0
                    for (c in 0 until channels) {
                        val i = (f * channels + c) * 2
                        s += (bytes[i].toInt() and 0xff) or (bytes[i + 1].toInt() shl 8)
                    }
                    mono[f] = s.toFloat() / channels
                }
                val res = resampler.process(mono, rate)
                val shorts = ShortArray(res.size) { res[it].toInt().coerceIn(-32768, 32767).toShort() }
                writer.write(shorts, shorts.size)
                var peak = 0
                for (s in shorts) peak = maxOf(peak, abs(s.toInt()))
                elapsedMs = samplesToMs(writer.samplesWritten)
                onProgress(elapsedMs, peak / 32768f)
                if (elapsedMs - lastSync >= 5_000) {
                    lastSync = elapsedMs
                    writer.syncHeader()
                    onCheckpoint(elapsedMs)
                }
            }
        } catch (e: Exception) {
            onError("Errore del microfono: ${e.message}")
        } finally {
            runCatching { line.stop(); line.close() }
            writer.close()
            elapsedMs = samplesToMs(writer.samplesWritten)
        }
    }
}

/** Riceve l'audio decodificato: [frames] campioni per canale, interlacciati. */
fun interface PcmSink {
    fun pcm(data: FloatArray, frames: Int, channels: Int, rate: Int)
}

/** Decodifica nativa (AVFoundation su macOS, Media Foundation su Windows) dentro whisper_jni. */
object NativeAudio {
    /** 0 se ok, negativo in caso di errore (-100: non disponibile su questo sistema). */
    @JvmStatic
    external fun decode(path: String, sink: PcmSink): Int
}

/**
 * Importa un file audio o video (wav, mp3, m4a, aac, mp4, mov, …) convertendolo in WAV 16 kHz mono.
 * Prova Java Sound (WAV/AIFF), poi il decoder del sistema operativo, infine ffmpeg se installato.
 */
object AudioImport {
    /** Decoder usato dall'ultima importazione riuscita (per i test). */
    @Volatile
    var lastDecoder = ""
        private set

    fun toWav16k(source: File, out: File): Long {
        val errors = mutableListOf<String>()
        for ((name, decoder) in listOf<Pair<String, (File, PcmSink) -> Unit>>(
            "Java Sound" to ::javaSound, "sistema" to ::system, "ffmpeg" to ::ffmpeg,
        )) {
            try {
                return write(out) { sink -> decoder(source, sink) }.also { lastDecoder = name }
            } catch (e: Throwable) {
                errors += "$name: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        out.delete()
        throw IllegalArgumentException(errors.joinToString("; "))
    }

    /** Scrive in [out] l'audio prodotto da [decode] (mescolato in mono e portato a 16 kHz). */
    private fun write(out: File, decode: (PcmSink) -> Unit): Long {
        val resampler = Resampler()
        WavWriter(out).use { w ->
            decode { data, frames, channels, rate ->
                val mono = FloatArray(frames) { f ->
                    var s = 0f
                    for (c in 0 until channels) s += data[f * channels + c]
                    s / channels * 32768f
                }
                val res = resampler.process(mono, rate)
                val shorts = ShortArray(res.size) { res[it].toInt().coerceIn(-32768, 32767).toShort() }
                w.write(shorts, shorts.size)
            }
            check(w.samplesWritten > 0) { "nessun audio" }
            return samplesToMs(w.samplesWritten)
        }
    }

    private fun javaSound(source: File, sink: PcmSink) {
        val input = AudioSystem.getAudioInputStream(source)
        val pcmFormat = AudioFormat(input.format.sampleRate, 16, input.format.channels, true, false)
        AudioSystem.getAudioInputStream(pcmFormat, input).use { pcm ->
            val channels = pcmFormat.channels
            val rate = pcmFormat.sampleRate.toInt()
            val buf = ByteArray(rate * channels * 2)
            while (true) {
                val n = pcm.read(buf)
                if (n <= 0) break
                val frames = n / (2 * channels)
                val data = FloatArray(frames * channels) { i ->
                    ((buf[2 * i].toInt() and 0xff) or (buf[2 * i + 1].toInt() shl 8)) / 32768f
                }
                sink.pcm(data, frames, channels, rate)
            }
        }
    }

    private fun system(source: File, sink: PcmSink) {
        val r = NativeAudio.decode(source.absolutePath, sink)
        check(r == 0) { if (r == -100) "non disponibile" else "errore $r" }
    }

    private fun ffmpeg(source: File, sink: PcmSink) {
        val p = ProcessBuilder("ffmpeg", "-nostdin", "-v", "error", "-i", source.absolutePath,
            "-vn", "-ac", "1", "-ar", "$SAMPLE_RATE", "-f", "s16le", "-")
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val buf = ByteArray(SAMPLE_RATE * 2)
        var carry = -1
        p.inputStream.use { input ->
            while (true) {
                var n = input.read(buf, if (carry >= 0) 1 else 0, buf.size - 1)
                if (n <= 0) break
                if (carry >= 0) { buf[0] = carry.toByte(); n++ }
                val frames = n / 2
                carry = if (n % 2 == 1) buf[n - 1].toInt() else -1
                sink.pcm(FloatArray(frames) { i ->
                    ((buf[2 * i].toInt() and 0xff) or (buf[2 * i + 1].toInt() shl 8)) / 32768f
                }, frames, 1, SAMPLE_RATE)
            }
        }
        check(p.waitFor() == 0) { "codice ${p.exitValue()}" }
    }
}
