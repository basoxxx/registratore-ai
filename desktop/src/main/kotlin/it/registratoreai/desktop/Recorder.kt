package it.registratoreai.desktop

import it.registratoreai.audio.Resampler
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

/** Importa un file WAV/AIFF qualsiasi convertendolo in WAV 16 kHz mono. */
object AudioImport {
    fun toWav16k(source: File, out: File): Long {
        val input = AudioSystem.getAudioInputStream(source)
        val pcmFormat = AudioFormat(input.format.sampleRate, 16, input.format.channels, true, false)
        val pcm = AudioSystem.getAudioInputStream(pcmFormat, input)
        val channels = pcmFormat.channels
        val rate = pcmFormat.sampleRate.toInt()
        val resampler = Resampler()
        val buf = ByteArray(rate * channels * 2)
        WavWriter(out).use { w ->
            while (true) {
                val n = pcm.read(buf)
                if (n <= 0) break
                val frames = n / (2 * channels)
                val mono = FloatArray(frames) { f ->
                    var s = 0
                    for (c in 0 until channels) {
                        val i = (f * channels + c) * 2
                        s += (buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8)
                    }
                    s.toFloat() / channels
                }
                val res = resampler.process(mono, rate)
                val shorts = ShortArray(res.size) { res[it].toInt().coerceIn(-32768, 32767).toShort() }
                w.write(shorts, shorts.size)
            }
            pcm.close()
            return samplesToMs(w.samplesWritten)
        }
    }
}

