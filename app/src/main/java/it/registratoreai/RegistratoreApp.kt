package it.registratoreai

import android.app.Application
import android.content.Context
import it.registratoreai.audio.WavWriter
import it.registratoreai.data.AppDatabase
import it.registratoreai.data.RecState
import it.registratoreai.data.Settings
import it.registratoreai.export.Exporter
import it.registratoreai.service.CaptureService
import it.registratoreai.summary.SUMMARY_MODELS
import it.registratoreai.transcription.ModelManager
import it.registratoreai.transcription.ModelStore
import it.registratoreai.transcription.Transcriber
import it.registratoreai.transcription.WhisperEngine
import it.registratoreai.transcription.WhisperLib
import it.registratoreai.transcription.WhisperNative
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class RegistratoreApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var db: AppDatabase
    lateinit var settings: Settings
    lateinit var models: ModelManager
    /** Modelli linguistici per il riassunto (cartella separata). */
    lateinit var summaryModels: ModelStore
    lateinit var exporter: Exporter
    lateinit var transcriber: Transcriber
    private val engine = WhisperEngine()

    override fun onCreate() {
        super.onCreate()
        // ggml sceglie la variante CPU più veloce tra le librerie estratte qui
        WhisperNative.backendsDir = applicationInfo.nativeLibraryDir
        db = AppDatabase.create(this)
        settings = Settings(this)
        models = ModelManager(this)
        summaryModels = ModelStore(File(filesDir, "llm"), SUMMARY_MODELS)
        exporter = Exporter(this, db.recordings(), settings)
        transcriber = Transcriber(this, db.recordings(), settings, models, engine, exporter)
        CaptureService.createChannel(this)
        recoverInterruptedRecordings()
    }

    /**
     * Se il processo era stato terminato durante una registrazione (batteria scarica,
     * crash, ecc.) ripariamo il WAV: tutto l'audio registrato fino a quel momento è salvo.
     */
    private fun recoverInterruptedRecordings() = appScope.launch {
        val dao = db.recordings()
        for (r in dao.unfinishedRecordings()) {
            val f = File(r.audioPath)
            val duration = if (f.extension == "wav") WavWriter.repair(f) else r.durationMs
            dao.updateRecordingState(r.id, duration, RecState.DONE)
        }
    }

    fun engineAbort() = WhisperLib.requestAbort(true)

    /** Libera la memoria del modello Whisper (prima di caricare il modello del riassunto). */
    fun releaseWhisper() = transcriber.releaseEngine()
}

val Context.app: RegistratoreApp get() = applicationContext as RegistratoreApp
