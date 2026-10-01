package it.registratoreai.text

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class TextSegment(val startMs: Long, val endMs: Long, val text: String)

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

    fun build(info: LessonInfo, segments: List<TextSegment>, format: ExportFormat, timestamps: Boolean = true): String {
        val paras = paragraphs(segments)
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
                for (p in paras) {
                    if (timestamps) sb.append("**[").append(formatTimestamp(p.startMs)).append("]** ")
                    sb.append(p.text).append("\n\n")
                }
            }
            ExportFormat.TEXT -> {
                sb.append(info.title).append('\n')
                sb.append(formatDate(info.createdAt))
                if (info.course.isNotBlank()) sb.append(" - ").append(info.course)
                sb.append(" - ").append(formatDuration(info.durationMs)).append("\n\n")
                for (p in paras) {
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
