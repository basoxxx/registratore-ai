package it.registratoreai.summary

import it.registratoreai.text.LessonInfo
import it.registratoreai.text.TextSegment
import it.registratoreai.text.TranscriptFormatter
import it.registratoreai.text.formatTimestamp
import it.registratoreai.transcription.WhisperLib
import it.registratoreai.transcription.WhisperModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive

/** Binding JNI verso llama.cpp (app/src/main/cpp/llama_jni.cpp), nella stessa libreria di whisper. */
object LlamaLib {
    init {
        WhisperLib.ensureLoaded()
    }

    external fun load(path: String, nCtx: Int, threads: Int): Long
    external fun free(ptr: Long)
    external fun requestAbort(value: Boolean)
    external fun countTokens(ptr: Long, text: String): Int
    external fun contextSize(ptr: Long): Int
    external fun chat(ptr: Long, system: String?, user: String, maxTokens: Int, temperature: Float): ByteArray?
}

/** Modelli linguistici per il riassunto (pubblicati nella release "models" del repository). */
val SUMMARY_MODELS = listOf(
    WhisperModel("qwen3-4b-q4_k_m", "Qwen3 4B", "Consigliato: riassunti accurati e fedeli. Serve un telefono con almeno 6 GB di RAM.", "Qwen3-4B-Q4_K_M.gguf", 2_497_281_312),
    WhisperModel("qwen3-1.7b-q4_k_m", "Qwen3 1.7B", "Leggero e veloce, per telefoni con poca memoria: più impreciso.", "Qwen3-1.7B-Q4_K_M.gguf", 1_107_409_472),
)

fun summaryModelById(id: String) = SUMMARY_MODELS.firstOrNull { it.id == id } ?: SUMMARY_MODELS[0]

/**
 * Riassunto della lezione con un modello linguistico locale, in stile map-reduce:
 * 1) la trascrizione viene divisa in parti (~10-15 minuti) e per ognuna si estraggono titolo e concetti;
 * 2) dagli appunti delle parti si scrive il riassunto finale;
 * 3) la scaletta con i minutaggi viene composta dall'app (niente orari inventati dal modello).
 * Così funziona anche su lezioni di 2 ore con un contesto di soli 4096 token.
 */
class Summarizer(private val modelPath: String, private val threads: Int) : AutoCloseable {
    companion object {
        const val CONTEXT = 4096
        private const val PART_TOKENS = 1700      // trascrizione per parte
        private const val NOTES_MAX_TOKENS = 380  // appunti per parte
        private const val FINAL_MAX_TOKENS = 900  // riassunto finale
        private const val REDUCE_INPUT_TOKENS = 2400

        private const val SYSTEM = "Sei un assistente che prepara appunti universitari chiari, ordinati e fedeli, " +
            "scritti interamente in italiano. Riporti solo ciò che è effettivamente detto nel testo: " +
            "se un'informazione non c'è, non la aggiungi e non la deduci."

        /**
         * Pulizia del Markdown generato: toglie le sezioni "vuote" (es. "Da fare: non ci sono
         * esercizi…") e le righe introduttive fuori dalle sezioni.
         */
        fun cleanSections(md: String): String {
            val sections = Regex("(?m)^## ").split(md).drop(1).map { "## $it".trim() }
            if (sections.isEmpty()) return md.trim()
            val empty = Regex("(?i)^[\\s\\-*•]*(non ci sono|non sono (stati )?(citat|menzionat|indicat)|nessun|non vengono|n/?a\\b)")
            return sections.filter { sec ->
                val body = sec.lines().drop(1).joinToString("\n").trim()
                body.isNotEmpty() && !empty.containsMatchIn(body)
            }.joinToString("\n\n")
        }

        /** Elimina eventuali ragionamenti <think>…</think> dei modelli che li producono. */
        fun stripThinking(s: String): String =
            s.replace(Regex("(?s)<think>.*?</think>"), "").replace(Regex("(?s)^.*</think>"), "").trim()
    }

    data class Part(val startMs: Long, val endMs: Long, val text: String)
    data class Notes(val part: Part, val title: String, val body: String)

    private var ptr = 0L

    fun load(): Boolean {
        if (ptr == 0L) ptr = LlamaLib.load(modelPath, CONTEXT, threads)
        return ptr != 0L
    }

    override fun close() {
        if (ptr != 0L) LlamaLib.free(ptr)
        ptr = 0L
    }

    private fun tokens(text: String) = LlamaLib.countTokens(ptr, text).let { if (it < 0) text.length / 3 else it }

    private suspend fun ask(user: String, maxTokens: Int): String {
        currentCoroutineContext().ensureActive()
        // "/no_think": i modelli Qwen3 rispondono direttamente senza fase di ragionamento
        val bytes = LlamaLib.chat(ptr, SYSTEM, "$user /no_think", maxTokens, 0.3f)
        if (bytes == null) {
            if (!currentCoroutineContext().isActive) throw CancellationException()
            error("Il modello non è riuscito a generare il testo")
        }
        return stripThinking(String(bytes, Charsets.UTF_8))
    }

    /** Divide la trascrizione in parti da ~[PART_TOKENS] token rispettando i paragrafi. */
    fun split(segments: List<TextSegment>): List<Part> {
        val paragraphs = TranscriptFormatter.paragraphs(segments)
        val lastEnd = segments.maxOfOrNull { it.endMs } ?: 0L
        val parts = mutableListOf<Part>()
        val sb = StringBuilder()
        var start = -1L
        var used = 0
        for (p in paragraphs) {
            val t = tokens(p.text)
            if (used + t > PART_TOKENS && sb.isNotEmpty()) {
                // la parte finisce dove inizia il paragrafo successivo
                parts += Part(start, p.startMs, sb.toString())
                sb.clear(); used = 0; start = -1
            }
            if (start < 0) start = p.startMs
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append(p.text)
            used += t
        }
        if (sb.isNotEmpty()) parts += Part(start, lastEnd, sb.toString())
        return parts
    }

    suspend fun summarize(info: LessonInfo, segments: List<TextSegment>, onProgress: (Float) -> Unit = {}): String {
        check(load()) { "Impossibile caricare il modello per il riassunto" }
        val parts = split(segments)
        require(parts.isNotEmpty()) { "La trascrizione è vuota" }
        val topic = listOf(info.course, info.title).filter { it.isNotBlank() }.joinToString(" – ")
        val steps = parts.size + 1
        var done = 0

        // 1) appunti per ogni parte
        val notes = parts.map { part ->
            val out = ask(
                "Questa è una parte della trascrizione automatica di una lezione universitaria" +
                    (if (topic.isNotBlank()) " ($topic)" else "") +
                    ". La trascrizione può contenere errori di riconoscimento: correggili solo se il significato è evidente.\n" +
                    "Scrivi:\n" +
                    "- una prima riga nel formato TITOLO: <argomento di questa parte, massimo 8 parole>\n" +
                    "- poi da 3 a 7 punti elenco (che iniziano con \"- \") con concetti, definizioni, teoremi ed esempi spiegati.\n" +
                    "Usa solo ciò che è detto nel testo.\n\nTRASCRIZIONE:\n${part.text}",
                NOTES_MAX_TOKENS,
            )
            onProgress((++done).toFloat() / steps)
            val title = Regex("(?im)^\\s*\\**TITOLO\\**\\s*:\\s*(.+)$").find(out)?.groupValues?.get(1)?.trim()?.trim('*', ' ')
                ?: "Parte ${parts.indexOf(part) + 1}"
            val body = out.lines().filterNot { Regex("(?i)^\\s*\\**TITOLO").containsMatchIn(it) }.joinToString("\n").trim()
            Notes(part, title, body)
        }

        // 2) se gli appunti sono troppi per un'unica richiesta, si condensano a gruppi
        var material = notes.map { "### ${it.title}\n${it.body}" }
        while (material.size > 1 && tokens(material.joinToString("\n\n")) > REDUCE_INPUT_TOKENS) {
            material = material.chunked(3).map { group ->
                if (group.size == 1) group[0] else ask(
                    "Unisci questi appunti di una lezione in un elenco unico e più breve (al massimo 8 punti elenco), " +
                        "senza perdere definizioni e risultati importanti.\n\n${group.joinToString("\n\n")}",
                    NOTES_MAX_TOKENS,
                )
            }
        }

        // 3) riassunto finale
        val final = ask(
            "Ecco gli appunti di una lezione universitaria" + (if (topic.isNotBlank()) " ($topic)" else "") + ".\n" +
                "Scrivi il riassunto in Markdown con esattamente queste sezioni:\n" +
                "## In breve\n(3-5 frasi che spiegano di cosa tratta la lezione)\n" +
                "## Punti chiave\n(da 5 a 10 punti elenco)\n" +
                "## Concetti e definizioni\n(solo termini davvero definiti nella lezione, nel formato **termine**: spiegazione)\n" +
                "## Da fare\n(SOLO se il docente cita esplicitamente esercizi, compiti, scadenze o avvisi; altrimenti ometti del tutto questa sezione)\n" +
                "Usa solo informazioni presenti negli appunti. Scrivi tutto in italiano. Non aggiungere altro testo prima o dopo.\n\n" +
                "APPUNTI:\n${material.joinToString("\n\n")}",
            FINAL_MAX_TOKENS,
        )
        onProgress(1f)

        val outline = notes.joinToString("\n") {
            "- **${formatTimestamp(it.part.startMs)}–${formatTimestamp(it.part.endMs)}** · ${it.title}"
        }
        return cleanSections(final) + "\n\n## Scaletta della lezione\n" + outline + "\n"
    }
}
