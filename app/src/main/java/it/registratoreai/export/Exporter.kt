package it.registratoreai.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import it.registratoreai.data.Recording
import it.registratoreai.data.RecordingDao
import it.registratoreai.data.Segment
import it.registratoreai.data.Settings
import it.registratoreai.data.TxState
import it.registratoreai.transcription.modelById
import it.registratoreai.ui.formatDate
import it.registratoreai.ui.formatDuration
import it.registratoreai.ui.formatTimestamp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ExportFormat(val ext: String, val mime: String) {
    MARKDOWN("md", "text/markdown"),
    TEXT("txt", "text/plain"),
}

class Exporter(
    private val context: Context,
    private val dao: RecordingDao,
    private val settings: Settings,
) {
    fun build(rec: Recording, segments: List<Segment>, format: ExportFormat, timestamps: Boolean = settings.current.includeTimestamps): String {
        val paras = paragraphs(segments)
        val sb = StringBuilder()
        val partial = rec.transcription != TxState.DONE
        when (format) {
            ExportFormat.MARKDOWN -> {
                sb.append("# ").append(rec.title).append("\n\n")
                sb.append("- **Data:** ").append(formatDate(rec.createdAt)).append('\n')
                if (rec.course.isNotBlank()) sb.append("- **Corso:** ").append(rec.course).append('\n')
                sb.append("- **Durata:** ").append(formatDuration(rec.durationMs)).append('\n')
                if (rec.modelId.isNotBlank()) sb.append("- **Trascrizione:** Whisper ").append(modelById(rec.modelId).name).append(" (offline)\n")
                if (partial) sb.append("- **Stato:** trascrizione in corso, aggiornata a ")
                    .append(formatTimestamp(rec.transcribedUntilMs)).append('\n')
                sb.append("\n---\n\n")
                for (p in paras) {
                    if (timestamps) sb.append("**[").append(formatTimestamp(p.startMs)).append("]** ")
                    sb.append(p.text).append("\n\n")
                }
            }
            ExportFormat.TEXT -> {
                sb.append(rec.title).append('\n')
                sb.append(formatDate(rec.createdAt))
                if (rec.course.isNotBlank()) sb.append(" - ").append(rec.course)
                sb.append(" - ").append(formatDuration(rec.durationMs)).append("\n\n")
                for (p in paras) {
                    if (timestamps) sb.append('[').append(formatTimestamp(p.startMs)).append("] ")
                    sb.append(p.text).append("\n\n")
                }
            }
        }
        return sb.toString()
    }

    fun fileName(rec: Recording, ext: String): String {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.ITALY).format(Date(rec.createdAt))
        val safe = rec.title.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").trim().take(80)
        return "$day $safe.$ext"
    }

    /** Crea un file temporaneo e restituisce l'Intent di condivisione. */
    suspend fun shareIntent(id: Long, format: ExportFormat): Intent? = withContext(Dispatchers.IO) {
        val rec = dao.get(id) ?: return@withContext null
        val dir = File(context.cacheDir, "export").apply { mkdirs() }
        val file = File(dir, fileName(rec, format.ext))
        file.writeText(build(rec, dao.segments(id), format))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        Intent(Intent.ACTION_SEND).apply {
            type = format.mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, rec.title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    suspend fun audioShareIntent(id: Long): Intent? = withContext(Dispatchers.IO) {
        val rec = dao.get(id) ?: return@withContext null
        val file = File(rec.audioPath)
        if (!file.exists()) return@withContext null
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        Intent(Intent.ACTION_SEND).apply {
            type = if (file.extension == "m4a") "audio/mp4" else "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, rec.title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    suspend fun writeTo(id: Long, uri: Uri, format: ExportFormat) = withContext(Dispatchers.IO) {
        val rec = dao.get(id) ?: return@withContext
        val text = build(rec, dao.segments(id), format)
        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) }
    }

    suspend fun copyAudioTo(id: Long, uri: Uri) = withContext(Dispatchers.IO) {
        val rec = dao.get(id) ?: return@withContext
        context.contentResolver.openOutputStream(uri, "w")!!.use { out ->
            File(rec.audioPath).inputStream().use { it.copyTo(out) }
        }
    }

    /**
     * Esportazione automatica: mantiene sempre aggiornato il file Markdown della lezione
     * nella cartella scelta dall'utente (es. una cartella sincronizzata con Drive/Obsidian).
     */
    suspend fun autoExport(id: Long) = withContext(Dispatchers.IO) {
        val s = settings.current
        val tree = s.exportTreeUri ?: return@withContext
        if (!s.autoExport) return@withContext
        try {
            val rec = dao.get(id) ?: return@withContext
            val text = build(rec, dao.segments(id), ExportFormat.MARKDOWN)
            val existing = rec.exportUri?.let { Uri.parse(it) }
            if (existing != null && tryWrite(existing, text)) return@withContext
            val dir = DocumentFile.fromTreeUri(context, Uri.parse(tree)) ?: return@withContext
            val name = fileName(rec, "md")
            val doc = dir.findFile(name) ?: dir.createFile("text/markdown", name) ?: return@withContext
            if (tryWrite(doc.uri, text)) dao.setExportUri(id, doc.uri.toString())
        } catch (e: Exception) {
            Log.w("Exporter", "Esportazione automatica fallita", e)
        }
    }

    companion object {
        /** Un paragrafo = segmenti consecutivi senza pause lunghe, con il timestamp iniziale. */
        data class Paragraph(val startMs: Long, val text: String)

        fun paragraphs(segments: List<Segment>): List<Paragraph> {
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
    }

    private fun tryWrite(uri: Uri, text: String): Boolean = try {
        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) }
        true
    } catch (e: Exception) {
        false
    }
}
