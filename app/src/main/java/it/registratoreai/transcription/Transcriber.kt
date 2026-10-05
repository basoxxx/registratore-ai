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
import it.registratoreai.transcription.EtaEstimator
import it.registratoreai.transcription.SpeechPacker
import it.registratoreai.transcription.WhisperVad
import it.registratoreai.transcription.SpeedStore
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
        private const val PROMPT_CHARS = 200
    }

    private val speeds = SpeedStore(File(context.filesDir, "speed.properties"))
    private var eta = EtaEstimator()
    private var vadInstance: WhisperVad? = null

    private fun vad(): WhisperVad? {
        vadInstance?.let { return it }
        val model = WhisperVad.extractModel(File(context.filesDir, "vad")) ?: return null
        return WhisperVad(model.absolutePath).takeIf { it.ok }?.also { vadInstance = it }
    }

    /** Velocità nota del modello (ms di audio per ms di calcolo), per stimare la coda. */
    fun speedFor(modelId: String): Float? = speeds.get(modelId)

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

        eta = EtaEstimator(speeds.get(s.modelId))
        ServiceState.speed.value = eta.speed
        // Dopo la lezione: VAD + impacchettamento del parlato (vedi SpeechPacker)
        val packer = vad()?.let { SpeechPacker(it) } ?: SpeechPacker { null }
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
                    val window = when (val next = packer.next(reader, offsetMs, live)) {
                        is Chunker.Chunk.Audio -> next.window
                        is Chunker.Chunk.Skip -> {
                            // Solo silenzio: si avanza senza chiamare Whisper
                            offsetMs = next.endMs
                            dao.setTranscribedUntil(id, offsetMs)
                            publish(id, offsetMs, samplesToMs(reader.availableSamples()), live)
                            continue
                        }
                        Chunker.Chunk.End -> break
                        Chunker.Chunk.Wait -> {
                            // In registrazione: aspettiamo che arrivi abbastanza audio.
                            publish(id, offsetMs, samplesToMs(reader.availableSamples()), true)
                            delay(2_000)
                            continue
                        }
                    }
                    val samples = window.samples
                    if (samples.isEmpty()) break

                    val chunkStartMs = offsetMs
                    if (AudioMath.rms(samples) > Chunker.SILENCE_RMS) {
                        val prompt = listOf(rec.course.takeIf { it.isNotBlank() }, promptTail.takeIf { it.isNotBlank() })
                            .filterNotNull().joinToString(". ")
                        val t0 = System.currentTimeMillis()
                        val raw = engine.transcribe(samples, s.language, prompt.ifBlank { null }, s.threads)
                        // Velocità misurata sull'audio originale coperto (silenzi saltati inclusi)
                        eta.record(window.endMs - chunkStartMs, System.currentTimeMillis() - t0)
                        speeds.put(s.modelId, eta.speed)
                        ServiceState.speed.value = eta.speed
                        coroutineContext.ensureActive()
                        if (raw == null) error("Errore durante la trascrizione")

                        val segments = mutableListOf<Segment>()
                        for (r in raw) {
                            val text = TextCleaner.clean(r.text) ?: continue
                            if (lastText != null && text.equals(lastText, ignoreCase = true)) continue
                            lastText = text
                            segments += Segment(
                                recordingId = id,
                                startMs = window.toSourceMs(r.startMs),
                                endMs = window.toSourceMs(r.endMs),
                                text = text,
                            )
                        }
                        if (segments.isNotEmpty()) {
                            dao.insertSegments(segments)
                            promptTail = (promptTail + " " + segments.joinToString(" ") { it.text }).takeLast(PROMPT_CHARS)
                        }
                    }
                    offsetMs = window.endMs
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

    private fun publish(id: Long, processed: Long, total: Long, live: Boolean) {
        ServiceState.transcription.value = TxProgress(id, processed, total, live, eta.etaMs(total - processed))
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
