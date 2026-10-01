package it.registratoreai.desktop

import it.registratoreai.transcription.WhisperNative
import java.io.File

/** Carica la libreria nativa whisper_jni distribuita insieme all'app. */
object Native {
    var error: String? = null
        private set

    fun load(): Boolean {
        val name = System.mapLibraryName("whisper_jni")
        val candidates = listOfNotNull(
            System.getProperty("compose.application.resources.dir"),
            System.getenv("WHISPER_JNI_DIR"),
            File(System.getProperty("user.dir"), "resources").path,
        ).map { File(it, name) }
        val lib = candidates.firstOrNull { it.isFile }
        if (lib == null) {
            error = "Libreria di trascrizione non trovata ($name)"
            return false
        }
        return try {
            System.load(lib.absolutePath)
            WhisperNative.preloaded = true
            true
        } catch (e: Throwable) {
            error = "Impossibile caricare la libreria di trascrizione: ${e.message}"
            false
        }
    }
}
