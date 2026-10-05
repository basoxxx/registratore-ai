package it.registratoreai.text

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** [pass]: 1 = anteprima (tempo reale), 2 = trascrizione finale con il modello grande. */
data class TextSegment(val startMs: Long, val endMs: Long, val text: String, val pass: Int = 1)

data class LessonInfo(
    val title: String,
    val course: String,
    val createdAt: Long,
    val durationMs: Long,
    /** Nome del modello usato (es. "Small"), vuoto se non ancora trascritta. */
    val modelName: String = "",
    /** Se la trascrizione non è finita: fin dove è arrivata. */
    val partialUntilMs: Long? = null,
)

enum class ExportFormat(val ext: String, val mime: String) {
    MARKDOWN("md", "text/markdown"),
    TEXT("txt", "text/plain"),
}

/**
 * Momenti segnati dallo studente durante la lezione ("⭐ Segna"): salvati come millisecondi
 * dall'inizio, in una stringa "12000,340000".
 */
object Bookmarks {
    /** Il tasto si preme dopo aver sentito la cosa importante: il segno parte 10 s prima. */
    const val LOOKBACK_MS = 10_000L

    fun parse(s: String?): List<Long> =
        s.orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }.distinct().sorted()

    fun format(list: List<Long>): String = list.distinct().sorted().joinToString(",")

    fun add(s: String?, elapsedMs: Long): String = format(parse(s) + (elapsedMs - LOOKBACK_MS).coerceAtLeast(0))

    /** Testo intorno a ogni segno (~[words] parole a partire dal segmento in corso in quel momento). */
    fun excerpts(segments: List<TextSegment>, bookmarks: List<Long>, words: Int = 40): List<Pair<Long, String>> =
        bookmarks.mapNotNull { b ->
            val from = segments.indexOfLast { it.startMs <= b }.coerceAtLeast(0)
            val text = segments.drop(from).asSequence().flatMap { it.text.split(' ').asSequence() }
                .filter { it.isNotBlank() }.take(words).joinToString(" ")
            if (text.isBlank()) null else b to text + if (text.endsWith('.')) "" else "…"
        }
}

/** Generazione dei file Markdown / testo, uguale su Android e desktop. */
object TranscriptFormatter {
    /** Un paragrafo = segmenti consecutivi senza pause lunghe, con il timestamp iniziale. */
    data class Paragraph(val startMs: Long, val text: String)

    fun paragraphs(segments: List<TextSegment>): List<Paragraph> {
        val out = mutableListOf<Paragraph>()
        var start = -1L
        var lastEnd = 0L
        val sb = StringBuilder()
        for (s in segments) {
            val newPara = start < 0 || s.startMs - lastEnd > 2_500 || (sb.length > 500 && sb.endsWith('.')) || sb.length > 900
            if (newPara && sb.isNotEmpty()) {
                out += Paragraph(start, sb.toString())
                sb.clear()
            }
            if (sb.isEmpty()) start = s.startMs
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(s.text)
            lastEnd = s.endMs
        }
        if (sb.isNotEmpty()) out += Paragraph(start, sb.toString())
        return out
    }

    fun build(
        info: LessonInfo, segments: List<TextSegment>, format: ExportFormat, timestamps: Boolean = true,
        summary: String? = null, bookmarks: List<Long> = emptyList(),
    ): String {
        val paras = paragraphs(segments)
        // paragrafi che contengono un momento segnato
        val starred = paras.indices.filter { i ->
            val end = paras.getOrNull(i + 1)?.startMs ?: Long.MAX_VALUE
            bookmarks.any { it in (if (i == 0) Long.MIN_VALUE else paras[i].startMs) until end }
        }.toSet()
        val excerpts = Bookmarks.excerpts(segments, bookmarks)
        val sb = StringBuilder()
        when (format) {
            ExportFormat.MARKDOWN -> {
                sb.append("# ").append(info.title).append("\n\n")
                sb.append("- **Data:** ").append(formatDate(info.createdAt)).append('\n')
                if (info.course.isNotBlank()) sb.append("- **Corso:** ").append(info.course).append('\n')
                sb.append("- **Durata:** ").append(formatDuration(info.durationMs)).append('\n')
                if (info.modelName.isNotBlank()) sb.append("- **Trascrizione:** Whisper ").append(info.modelName).append(" (offline)\n")
                info.partialUntilMs?.let {
                    sb.append("- **Stato:** trascrizione in corso, aggiornata a ").append(formatTimestamp(it)).append('\n')
                }
                sb.append("\n---\n\n")
                if (!summary.isNullOrBlank()) {
                    // Le sezioni del riassunto (## ...) diventano sottosezioni di "Riassunto"
                    sb.append("## Riassunto\n\n")
                    sb.append(summary.trim().replace(Regex("(?m)^## "), "### ")).append("\n\n---\n\n")
                }
                if (excerpts.isNotEmpty()) {
                    sb.append("## ⭐ Momenti segnati\n\n")
                    for ((ms, text) in excerpts) sb.append("- **[").append(formatTimestamp(ms)).append("]** ").append(text).append('\n')
                    sb.append("\n---\n\n")
                }
                if (!summary.isNullOrBlank() || excerpts.isNotEmpty()) sb.append("## Trascrizione\n\n")
                for ((i, p) in paras.withIndex()) {
                    if (i in starred) sb.append("⭐ ")
                    if (timestamps) sb.append("**[").append(formatTimestamp(p.startMs)).append("]** ")
                    sb.append(p.text).append("\n\n")
                }
            }
            ExportFormat.TEXT -> {
                sb.append(info.title).append('\n')
                sb.append(formatDate(info.createdAt))
                if (info.course.isNotBlank()) sb.append(" - ").append(info.course)
                sb.append(" - ").append(formatDuration(info.durationMs)).append("\n\n")
                if (!summary.isNullOrBlank()) {
                    sb.append("RIASSUNTO\n\n").append(summary.trim().replace(Regex("(?m)^#+ "), "").replace("**", ""))
                        .append("\n\n")
                }
                if (excerpts.isNotEmpty()) {
                    sb.append("MOMENTI SEGNATI\n\n")
                    for ((ms, text) in excerpts) sb.append("* [").append(formatTimestamp(ms)).append("] ").append(text).append('\n')
                    sb.append('\n')
                }
                if (!summary.isNullOrBlank() || excerpts.isNotEmpty()) sb.append("TRASCRIZIONE\n\n")
                for ((i, p) in paras.withIndex()) {
                    if (i in starred) sb.append("* ")
                    if (timestamps) sb.append('[').append(formatTimestamp(p.startMs)).append("] ")
                    sb.append(p.text).append("\n\n")
                }
            }
        }
        return sb.toString()
    }

    fun fileName(title: String, createdAt: Long, ext: String): String {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.ITALY).format(Date(createdAt))
        val safe = title.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").trim().take(80)
        return "$day $safe.$ext"
    }
}
