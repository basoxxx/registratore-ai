package it.registratoreai.transcription

/**
 * Su desktop la libreria nativa viene caricata con System.load() dalla cartella dell'app
 * prima del primo utilizzo (vedi desktop/Native.kt); su Android basta loadLibrary.
 * (Oggetto separato: accedere a WhisperLib ne avvierebbe subito l'inizializzazione.)
 */
object WhisperNative {
    @Volatile
    var preloaded = false

    /** Cartella in cui ggml cerca le varianti del backend CPU (nativeLibraryDir su Android). */
    @Volatile
    var backendsDir: String? = null
}

/** Binding JNI verso whisper.cpp (vedi app/src/main/cpp/whisper_jni.cpp). */
object WhisperLib {
    init {
        if (!WhisperNative.preloaded) System.loadLibrary("whisper_jni")
        initBackends(WhisperNative.backendsDir)
    }

    /** Accedere a questo oggetto carica la libreria nativa (usata anche da LlamaLib). */
    fun ensureLoaded() {}

    external fun initBackends(dir: String?)
    external fun vadInit(modelPath: String, threads: Int): Long
    external fun vadFree(ptr: Long)
    external fun vadSegments(
        ptr: Long, samples: FloatArray, threshold: Float, minSpeechMs: Int, minSilenceMs: Int,
        maxSpeechS: Float, padMs: Int,
    ): LongArray?

    external fun initContext(modelPath: String): Long
    external fun freeContext(ptr: Long)
    external fun requestAbort(value: Boolean)
    external fun transcribe(ptr: Long, samples: FloatArray, language: String, prompt: String?, threads: Int, beamSize: Int): Int
    external fun segmentT0(ptr: Long, i: Int): Long
    external fun segmentT1(ptr: Long, i: Int): Long
    external fun segmentText(ptr: Long, i: Int): ByteArray
    external fun systemInfo(): String
}

data class RawSegment(val startMs: Long, val endMs: Long, val text: String)

/** Mantiene un solo modello caricato in memoria; l'accesso è serializzato dal chiamante. */
class WhisperEngine {
    private var ptr = 0L
    private var loadedPath: String? = null

    @Synchronized
    fun ensureLoaded(path: String): Boolean {
        if (ptr != 0L && loadedPath == path) return true
        release()
        ptr = WhisperLib.initContext(path)
        loadedPath = if (ptr != 0L) path else null
        return ptr != 0L
    }

    @Synchronized
    fun transcribe(samples: FloatArray, language: String, prompt: String?, threads: Int, beamSize: Int = 1): List<RawSegment>? {
        check(ptr != 0L) { "Modello non caricato" }
        val n = WhisperLib.transcribe(ptr, samples, language, prompt, threads, beamSize)
        if (n < 0) return null
        return (0 until n).map {
            RawSegment(
                WhisperLib.segmentT0(ptr, it),
                WhisperLib.segmentT1(ptr, it),
                String(WhisperLib.segmentText(ptr, it), Charsets.UTF_8),
            )
        }
    }

    @Synchronized
    fun release() {
        if (ptr != 0L) WhisperLib.freeContext(ptr)
        ptr = 0L
        loadedPath = null
    }
}
