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

// Tutti in formato Q8_0: su CPU è veloce quanto il Q4_0 (routine ottimizzate ARM/x86) ma
// resta affidabile sull'audio difficile, dove il Q4_0 perdeva intere frasi (misurato su una
// lezione reale registrata da lontano).
val MODELS = listOf(
    WhisperModel("tiny-q8_0", "Tiny", "Velocissimo, qualità base. Solo per telefoni molto datati.", "ggml-tiny-q8_0.bin", 43_537_433),
    WhisperModel("base-q8_0", "Base", "Leggero: anteprima in tempo reale su qualsiasi telefono.", "ggml-base-q8_0.bin", 81_768_585),
    WhisperModel("small-q8_0", "Small", "Consigliato per il tempo reale: buona precisione in italiano.", "ggml-small-q8_0.bin", 264_464_607),
    WhisperModel("large-v3-turbo-q8_0", "Large v3 Turbo", "Massima precisione, anche con audio difficile. Ideale per la trascrizione finale.", "ggml-large-v3-turbo-q8_0.bin", 874_188_075),
)

/** Modello consigliato per la trascrizione finale dopo la lezione. */
const val FINAL_MODEL_ID = "large-v3-turbo-q8_0"

/** Modelli sostituiti da versioni migliori: id vecchio -> id nuovo. */
private val REPLACED = mapOf(
    "tiny-q5_1" to "tiny-q8_0",
    "base-q5_1" to "base-q8_0",
    "small-q5_1" to "small-q8_0",
    "large-v3-turbo-q5_0" to "large-v3-turbo-q8_0",
    "large-v3-turbo-q4_0" to "large-v3-turbo-q8_0",
)

/** File delle versioni precedenti, ancora usati finché non si scarica quella nuova. */
private val LEGACY_FILES = mapOf(
    "tiny-q8_0" to "ggml-tiny-q5_1.bin",
    "base-q8_0" to "ggml-base-q5_1.bin",
    "small-q8_0" to "ggml-small-q5_1.bin",
    "large-v3-turbo-q8_0" to "ggml-large-v3-turbo-q5_0.bin",
)

/** Da eliminare subito: il Q4_0 di Large v3 Turbo sbaglia troppo sull'audio difficile. */
private val OBSOLETE_FILES = listOf("ggml-large-v3-turbo-q4_0.bin")

fun canonicalModelId(id: String): String = REPLACED[id] ?: id

fun modelById(id: String): WhisperModel = canonicalModelId(id).let { c -> MODELS.firstOrNull { it.id == c } } ?: MODELS[1]

/**
 * Beam search per il modello grande: più robusto sull'audio difficile e quasi gratuito,
 * perché il decoder di Large v3 Turbo ha solo 4 strati. Per i modelli piccoli (tempo reale)
 * resta la decodifica greedy, più rapida.
 */
fun beamSizeFor(id: String): Int = if (canonicalModelId(id).startsWith("large")) 5 else 1

/** Download e gestione dei modelli Whisper in una cartella locale (Android e desktop). */
class ModelStore(private val dir: File, private val catalog: List<WhisperModel> = MODELS) {
    init {
        dir.mkdirs()
        OBSOLETE_FILES.forEach { File(dir, it).delete(); File(dir, "$it.part").delete() }
    }

    /** id modello -> progresso download (0..1) */
    private val _downloads = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloads: StateFlow<Map<String, Float>> = _downloads.asStateFlow()

    private val _installed = MutableStateFlow(scanInstalled())
    /** Modelli utilizzabili (versione nuova oppure, in attesa dell'aggiornamento, quella precedente). */
    val installed: StateFlow<Set<String>> = _installed.asStateFlow()

    private val _upgradable = MutableStateFlow(scanUpgradable())
    /** Modelli presenti solo nella versione precedente: conviene scaricare quella nuova. */
    val upgradable: StateFlow<Set<String>> = _upgradable.asStateFlow()

    private fun scanInstalled() = catalog.filter { usableFile(it) != null }.map { it.id }.toSet()
    private fun scanUpgradable() = catalog.filter { !file(it).exists() && legacyFile(it)?.exists() == true }.map { it.id }.toSet()
    private fun byId(id: String) = catalog.firstOrNull { it.id == canonicalModelId(id) } ?: modelById(id)
    private fun rescan() {
        _installed.value = scanInstalled()
        _upgradable.value = scanUpgradable()
    }

    fun file(model: WhisperModel) = File(dir, model.fileName)
    private fun legacyFile(model: WhisperModel) = LEGACY_FILES[model.id]?.let { File(dir, it) }
    private fun usableFile(model: WhisperModel): File? =
        file(model).takeIf { it.exists() } ?: legacyFile(model)?.takeIf { it.exists() }

    fun isInstalled(id: String) = usableFile(byId(id)) != null

    fun pathFor(id: String): String? = usableFile(byId(id))?.absolutePath

    fun delete(model: WhisperModel) {
        file(model).delete()
        legacyFile(model)?.delete()
        rescan()
    }

    /** Scarica il modello (riprende i download interrotti grazie all'header Range). */
    suspend fun download(model: WhisperModel) = withContext(Dispatchers.IO) {
        if (_downloads.value.containsKey(model.id)) return@withContext
        _downloads.update { it + (model.id to 0f) }
        val part = File(dir, model.fileName + ".part")
        try {
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
            if (code == 416 && part.length() > 0) {
                // Il file parziale era già completo
                part.renameTo(file(model))
                legacyFile(model)?.delete()
                return@withContext
            }
            val append = code == HttpURLConnection.HTTP_PARTIAL
            if (code != HttpURLConnection.HTTP_OK && !append) error("HTTP $code")
            var done = if (append) part.length() else 0L
            // La dimensione reale viene dal server; quella in elenco serve solo come riferimento
            val total = conn.contentLengthLong.takeIf { it > 0 }?.let { it + done } ?: model.sizeBytes
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
                // La versione precedente non serve più
                legacyFile(model)?.delete()
            } else {
                error("Download incompleto")
            }
        } finally {
            _downloads.update { it - model.id }
            rescan()
        }
    }
}
