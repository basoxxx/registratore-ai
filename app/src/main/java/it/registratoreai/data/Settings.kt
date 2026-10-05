package it.registratoreai.data

import android.content.Context
import android.content.SharedPreferences
import it.registratoreai.transcription.FINAL_MODEL_ID
import it.registratoreai.transcription.canonicalModelId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    /** Modello per la trascrizione in tempo reale (anteprima). */
    val modelId: String,
    /** Modello per la trascrizione finale dopo la lezione. */
    val finalModelId: String,
    /** Al termine della lezione ritrascrive tutto con il modello finale. */
    val refineAfter: Boolean,
    /** Genera il riassunto con l'IA locale quando la trascrizione è completa. */
    val autoSummary: Boolean,
    /** Modello linguistico per il riassunto. */
    val summaryModelId: String,
    /** Continua trascrizione e riassunto anche a schermo spento / dispositivo in standby. */
    val keepAwake: Boolean,
    val language: String,
    /** Trascrive durante la registrazione (aggiornamenti in tempo reale). */
    val liveTranscription: Boolean,
    /** Avvia la trascrizione automaticamente al termine della registrazione. */
    val autoTranscribe: Boolean,
    val threads: Int,
    /** Cartella (SAF) dove tenere sempre aggiornati i file Markdown. */
    val exportTreeUri: String?,
    val autoExport: Boolean,
    val includeTimestamps: Boolean,
    /** Converte il WAV in M4A dopo la trascrizione per risparmiare spazio. */
    val compressAudio: Boolean,
    val checkUpdates: Boolean,
)

class Settings(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppSettings> = _state.asStateFlow()
    val current: AppSettings get() = _state.value

    private fun read() = AppSettings(
        modelId = canonicalModelId(prefs.getString("modelId", "base-q8_0")!!),
        finalModelId = canonicalModelId(prefs.getString("finalModelId", FINAL_MODEL_ID)!!),
        refineAfter = prefs.getBoolean("refineAfter", true),
        autoSummary = prefs.getBoolean("autoSummary", true),
        summaryModelId = prefs.getString("summaryModelId", defaultSummaryModel())!!,
        keepAwake = prefs.getBoolean("keepAwake", true),
        language = prefs.getString("language", "it")!!,
        liveTranscription = prefs.getBoolean("live", true),
        autoTranscribe = prefs.getBoolean("autoTranscribe", true),
        threads = prefs.getInt("threads", defaultThreads()),
        exportTreeUri = prefs.getString("exportTreeUri", null),
        autoExport = prefs.getBoolean("autoExport", false),
        includeTimestamps = prefs.getBoolean("timestamps", true),
        compressAudio = prefs.getBoolean("compress", false),
        checkUpdates = prefs.getBoolean("checkUpdates", true),
    )

    fun update(block: (AppSettings) -> AppSettings) {
        val s = block(current)
        prefs.edit()
            .putString("modelId", s.modelId)
            .putString("finalModelId", s.finalModelId)
            .putBoolean("refineAfter", s.refineAfter)
            .putBoolean("autoSummary", s.autoSummary)
            .putString("summaryModelId", s.summaryModelId)
            .putBoolean("keepAwake", s.keepAwake)
            .putString("language", s.language)
            .putBoolean("live", s.liveTranscription)
            .putBoolean("autoTranscribe", s.autoTranscribe)
            .putInt("threads", s.threads)
            .putString("exportTreeUri", s.exportTreeUri)
            .putBoolean("autoExport", s.autoExport)
            .putBoolean("timestamps", s.includeTimestamps)
            .putBoolean("compress", s.compressAudio)
            .putBoolean("checkUpdates", s.checkUpdates)
            .apply()
        _state.value = s
    }

    /** Qwen3 4B se il telefono ha almeno 6 GB di RAM, altrimenti il modello leggero. */
    private fun defaultSummaryModel(): String {
        val mi = android.app.ActivityManager.MemoryInfo()
        appContext.getSystemService(android.app.ActivityManager::class.java)?.getMemoryInfo(mi)
        return if (mi.totalMem >= 5_500_000_000L) "qwen3-4b-q4_k_m" else "qwen3-1.7b-q4_k_m"
    }

    var lastUpdateCheck: Long
        get() = prefs.getLong("lastUpdateCheck", 0)
        set(v) = prefs.edit().putLong("lastUpdateCheck", v).apply()

    companion object {
        fun defaultThreads(): Int =
            Runtime.getRuntime().availableProcessors().coerceIn(2, 8).let { if (it > 4) 4 else it }
    }
}
