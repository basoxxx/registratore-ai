package it.registratoreai.transcription

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

data class WhisperModel(
    val id: String,
    val name: String,
    val description: String,
    val fileName: String,
    val sizeBytes: Long,
) {
    /** I modelli sono pubblicati come file della release "models" di questo repository. */
    val url get() = "$MODELS_BASE_URL/$fileName"
    val sizeMb get() = sizeBytes / 1_000_000
}

const val MODELS_BASE_URL = "https://github.com/basoxxx/registratore-ai/releases/download/models"

val MODELS = listOf(
    WhisperModel("tiny-q5_1", "Tiny", "Velocissimo, qualità base. Per telefoni datati.", "ggml-tiny-q5_1.bin", 32_152_673),
    WhisperModel("base-q5_1", "Base", "Buon compromesso: adatto alla trascrizione in tempo reale.", "ggml-base-q5_1.bin", 59_707_625),
    WhisperModel("small-q5_1", "Small", "Consigliato per l'italiano: molto più preciso, più lento.", "ggml-small-q5_1.bin", 190_085_487),
    WhisperModel("large-v3-turbo-q5_0", "Large v3 Turbo", "Massima qualità. Lento: ideale per trascrivere dopo la lezione.", "ggml-large-v3-turbo-q5_0.bin", 574_041_195),
)

fun modelById(id: String): WhisperModel = MODELS.firstOrNull { it.id == id } ?: MODELS[1]

/** Download e gestione dei modelli Whisper in una cartella locale (Android e desktop). */
class ModelStore(private val dir: File) {
    init {
        dir.mkdirs()
    }

    /** id modello -> progresso download (0..1) */
    private val _downloads = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloads: StateFlow<Map<String, Float>> = _downloads.asStateFlow()

    private val _installed = MutableStateFlow(scanInstalled())
    val installed: StateFlow<Set<String>> = _installed.asStateFlow()

    private fun scanInstalled() = MODELS.filter { file(it).exists() }.map { it.id }.toSet()

    fun file(model: WhisperModel) = File(dir, model.fileName)

    fun isInstalled(id: String) = file(modelById(id)).exists()

    fun pathFor(id: String): String? = file(modelById(id)).takeIf { it.exists() }?.absolutePath

    fun delete(model: WhisperModel) {
        file(model).delete()
        _installed.value = scanInstalled()
    }

    /** Scarica il modello (riprende i download interrotti grazie all'header Range). */
    suspend fun download(model: WhisperModel) = withContext(Dispatchers.IO) {
        if (_downloads.value.containsKey(model.id)) return@withContext
        _downloads.update { it + (model.id to 0f) }
        val part = File(dir, model.fileName + ".part")
        try {
            if (part.length() >= model.sizeBytes) {
                part.renameTo(file(model))
                return@withContext
            }
            var url = URL(model.url)
            var conn: HttpURLConnection
            var redirects = 0
            while (true) {
                conn = url.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 20_000
                conn.readTimeout = 30_000
                if (part.exists()) conn.setRequestProperty("Range", "bytes=${part.length()}-")
                val code = conn.responseCode
                if (code in 300..399 && redirects < 10) {
                    url = URL(url, conn.getHeaderField("Location"))
                    conn.disconnect()
                    redirects++
                    continue
                }
                break
            }
            val code = conn.responseCode
            val append = code == HttpURLConnection.HTTP_PARTIAL
            if (code != HttpURLConnection.HTTP_OK && !append) error("HTTP $code")
            var done = if (append) part.length() else 0L
            val total = model.sizeBytes
            conn.inputStream.use { input ->
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var lastEmit = 0L
                    while (coroutineContext.isActive) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastEmit > 512 * 1024) {
                            lastEmit = done
                            _downloads.update { it + (model.id to (done.toFloat() / total).coerceIn(0f, 1f)) }
                        }
                    }
                }
            }
            if (part.length() >= total) {
                part.renameTo(file(model))
            }
        } finally {
            _downloads.update { it - model.id }
            _installed.value = scanInstalled()
        }
    }
}
