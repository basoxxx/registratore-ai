package it.registratoreai.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    val modelId: String,
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
    private val prefs: SharedPreferences =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppSettings> = _state.asStateFlow()
    val current: AppSettings get() = _state.value

    private fun read() = AppSettings(
        modelId = prefs.getString("modelId", "base-q5_1")!!,
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

    var lastUpdateCheck: Long
        get() = prefs.getLong("lastUpdateCheck", 0)
        set(v) = prefs.edit().putLong("lastUpdateCheck", v).apply()

    companion object {
        fun defaultThreads(): Int =
            Runtime.getRuntime().availableProcessors().coerceIn(2, 8).let { if (it > 4) 4 else it }
    }
}
