package it.registratoreai.desktop

import it.registratoreai.transcription.WhisperNative
import java.io.File

/** Carica la libreria nativa whisper_jni (e le sue dipendenze) distribuita insieme all'app. */
object Native {
    var error: String? = null
        private set

    fun load(): Boolean {
        val name = System.mapLibraryName("whisper_jni")
        val dir = listOfNotNull(
            System.getProperty("compose.application.resources.dir"),
            System.getenv("WHISPER_JNI_DIR"),
            File(System.getProperty("user.dir"), "resources").path,
        ).map { File(it) }.firstOrNull { File(it, name).isFile }
        if (dir == null) {
            error = "Libreria di trascrizione non trovata ($name)"
            return false
        }
        return try {
            // Su Windows le DLL dipendenti non vengono cercate nella cartella della libreria:
            // le carichiamo esplicitamente nell'ordine giusto (se presenti).
            for (dep in listOf("ggml-base", "ggml", "whisper")) {
                val f = File(dir, System.mapLibraryName(dep))
                if (f.isFile) System.load(f.absolutePath)
            }
            System.load(File(dir, name).absolutePath)
            WhisperNative.preloaded = true
            // Le varianti del backend CPU (ggml-cpu-*.dll / libggml-cpu-*.so) stanno nella stessa cartella
            WhisperNative.backendsDir = dir.absolutePath
            true
        } catch (e: Throwable) {
            error = "Impossibile caricare la libreria di trascrizione: ${e.message}"
            false
        }
    }
}
