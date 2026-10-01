package it.registratoreai.transcription

import android.content.Context
import android.net.Uri
import android.util.Log
import it.registratoreai.audio.AudioCodec
import it.registratoreai.audio.AudioMath
import it.registratoreai.audio.SAMPLE_RATE
import it.registratoreai.audio.WavReader
import it.registratoreai.audio.msToSamples
import it.registratoreai.audio.samplesToMs
import it.registratoreai.data.RecordingDao
import it.registratoreai.data.Segment
import it.registratoreai.data.Settings
import it.registratoreai.data.TxState
import it.registratoreai.export.Exporter
import it.registratoreai.service.ServiceState
import it.registratoreai.service.TxProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Trascrive una registrazione a blocchi di ~30 secondi (la finestra nativa di Whisper).
 *
 * Lo stesso ciclo funziona sia in tempo reale (il file WAV cresce mentre si registra e
 * il trascrittore "insegue" l'audio) sia dopo, su un file completo. Dopo ogni blocco il
 * punto raggiunto viene salvato nel database, quindi la trascrizione può essere
 * interrotta e ripresa in qualunque momento senza perdere lavoro.
 */
class Transcriber(
    private val context: Context,
    private val dao: RecordingDao,
    private val settings: Settings,
    private val models: ModelManager,
    private val engine: WhisperEngine,
    private val exporter: Exporter,
) {
    companion object {
        private const val TAG = "Transcriber"
        private const val MAX_CHUNK = SAMPLE_RATE * 29          // whisper elabora al massimo 30 s
        private const val MIN_CHUNK = SAMPLE_RATE * 22          // cerca una pausa tra 22 e 29 s
        private const val MIN_TAIL = SAMPLE_RATE / 2            // ignora code < 0,5 s
        private const val SILENCE_RMS = 0.0025f                 // blocchi silenziosi: niente whisper (evita "allucinazioni")
        private const val PROMPT_CHARS = 200

        /**
         * Sceglie il prossimo blocco da trascrivere a partire da [offsetMs]:
         * blocchi pieni da ~22-29 s tagliati nel punto più silenzioso; la coda finale
         * solo quando la registrazione è terminata.
         */
        fun nextChunk(reader: WavReader, offsetMs: Long, live: Boolean): Chunk {
            val start = msToSamples(offsetMs)
            val remaining = reader.availableSamples() - start
            return when {
                remaining >= MAX_CHUNK -> {
                    val x = reader.readFloats(start, MAX_CHUNK)
                    Chunk.Audio(x.copyOf(AudioMath.quietestCut(x, MIN_CHUNK, x.size)))
                }
                live -> Chunk.Wait
                remaining > MIN_TAIL -> Chunk.Audio(reader.readFloats(start, remaining.toInt()))
                else -> Chunk.End
            }
        }
    }

    suspend fun run(id: Long) = withContext(Dispatchers.Default) {
        val rec = dao.get(id) ?: return@withContext
        val s = settings.current
        val modelPath = models.pathFor(s.modelId)
        if (modelPath == null) {
            dao.setTranscription(id, TxState.ERROR, "Nessun modello di trascrizione scaricato. Vai in Impostazioni.")
            return@withContext
        }
        if (!engine.ensureLoaded(modelPath)) {
            dao.setTranscription(id, TxState.ERROR, "Impossibile caricare il modello: riscaricalo dalle Impostazioni.")
            return@withContext
        }
        dao.setTranscription(id, TxState.RUNNING)
        dao.setModel(id, s.modelId, s.language)

        // Whisper lavora su PCM 16 kHz: se l'audio è stato compresso lo decodifichiamo in un file temporaneo.
        var tempWav: File? = null
        val wav = if (rec.audioPath.endsWith(".wav", ignoreCase = true)) {
            File(rec.audioPath)
        } else {
            File(context.cacheDir, "decode_$id.wav").also {
                AudioCodec.decodeToWav(context, Uri.fromFile(File(rec.audioPath)), it)
                tempWav = it
            }
        }

        var offsetMs = rec.transcribedUntilMs
        // Se l'app si era chiusa a metà di un blocco, eliminiamo i segmenti oltre l'ultimo punto salvato.
        dao.deleteSegmentsFrom(id, offsetMs)
        val previous = dao.lastSegments(id, 6).reversed()
        var promptTail = previous.joinToString(" ") { it.text }.takeLast(PROMPT_CHARS)
        var lastText = previous.lastOrNull()?.text

        try {
            WavReader(wav).use { reader ->
                while (true) {
                    coroutineContext.ensureActive()
                    val live = ServiceState.recording.value?.recordingId == id
                    val samples = when (val next = nextChunk(reader, offsetMs, live)) {
                        is Chunk.Audio -> next.samples
                        Chunk.End -> break
                        Chunk.Wait -> {
                            // In registrazione: aspettiamo che arrivi abbastanza audio.
                            publish(id, offsetMs, samplesToMs(reader.availableSamples()), true)
                            delay(2_000)
                            continue
                        }
                    }
                    if (samples.isEmpty()) break

                    val chunkStartMs = offsetMs
                    val chunkLenMs = samplesToMs(samples.size.toLong())
                    if (AudioMath.rms(samples) > SILENCE_RMS) {
                        val prompt = listOf(rec.course.takeIf { it.isNotBlank() }, promptTail.takeIf { it.isNotBlank() })
                            .filterNotNull().joinToString(". ")
                        val raw = engine.transcribe(samples, s.language, prompt.ifBlank { null }, s.threads)
                        coroutineContext.ensureActive()
                        if (raw == null) error("Errore durante la trascrizione")

                        val segments = mutableListOf<Segment>()
                        for (r in raw) {
                            val text = TextCleaner.clean(r.text) ?: continue
                            if (lastText != null && text.equals(lastText, ignoreCase = true)) continue
                            lastText = text
                            segments += Segment(
                                recordingId = id,
                                startMs = chunkStartMs + r.startMs.coerceIn(0, chunkLenMs),
                                endMs = chunkStartMs + r.endMs.coerceIn(0, chunkLenMs),
                                text = text,
                            )
                        }
                        if (segments.isNotEmpty()) {
                            dao.insertSegments(segments)
                            promptTail = (promptTail + " " + segments.joinToString(" ") { it.text }).takeLast(PROMPT_CHARS)
                        }
                    }
                    offsetMs += chunkLenMs
                    dao.setTranscribedUntil(id, offsetMs)
                    publish(id, offsetMs, samplesToMs(reader.availableSamples()), live)
                    exporter.autoExport(id)
                }
            }
            dao.setTranscription(id, TxState.DONE)
            exporter.autoExport(id)
            if (s.compressAudio && tempWav == null) compress(id, wav)
        } finally {
            tempWav?.delete()
            ServiceState.transcription.value = null
        }
    }

    sealed interface Chunk {
        class Audio(val samples: FloatArray) : Chunk
        data object Wait : Chunk
        data object End : Chunk
    }

    private fun publish(id: Long, processed: Long, total: Long, live: Boolean) {
        ServiceState.transcription.value = TxProgress(id, processed, total, live)
    }

    private suspend fun compress(id: Long, wav: File) {
        try {
            val m4a = File(wav.parentFile, wav.nameWithoutExtension + ".m4a")
            AudioCodec.wavToM4a(wav, m4a)
            if (m4a.length() > 0) {
                dao.setAudioPath(id, m4a.absolutePath)
                wav.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Compressione fallita", e)
        }
    }
}

/** Pulizia del testo e filtro delle tipiche "allucinazioni" di Whisper sul silenzio. */
object TextCleaner {
    private val hallucinations = listOf(
        Regex("sottotitoli", RegexOption.IGNORE_CASE) to Regex("amara|qtss|a cura di|creati|realizzati|revisione", RegexOption.IGNORE_CASE),
        Regex("grazie (a tutti )?per (la|l'|aver) (visione|guardato|ascoltato)", RegexOption.IGNORE_CASE) to null,
        Regex("iscriviti al canale|iscrivetevi al canale", RegexOption.IGNORE_CASE) to null,
        Regex("thank(s| you) for watching|subtitles by|amara\\.org", RegexOption.IGNORE_CASE) to null,
    )

    fun clean(raw: String): String? {
        val t = raw.trim().replace(Regex("\\s+"), " ")
        if (t.isEmpty()) return null
        // Solo annotazioni tipo "[Musica]", "(applausi)", "*rumore*"
        if (Regex("^[\\[(*♪].*[\\])*♪]$").matches(t)) return null
        if (!t.any { it.isLetterOrDigit() }) return null
        for ((a, b) in hallucinations) {
            if (a.containsMatchIn(t) && (b == null || b.containsMatchIn(t))) return null
        }
        return t
    }
}
