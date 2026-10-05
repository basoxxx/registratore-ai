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
import it.registratoreai.text.ExportFormat
import it.registratoreai.text.LessonInfo
import it.registratoreai.text.TextSegment
import it.registratoreai.text.TranscriptFormatter
import it.registratoreai.transcription.modelById
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class Exporter(
    private val context: Context,
    private val dao: RecordingDao,
    private val settings: Settings,
) {
    fun build(rec: Recording, segments: List<Segment>, format: ExportFormat, timestamps: Boolean = settings.current.includeTimestamps): String =
        TranscriptFormatter.build(
            LessonInfo(
                title = rec.title, course = rec.course, createdAt = rec.createdAt, durationMs = rec.durationMs,
                modelName = if (rec.modelId.isNotBlank()) modelById(rec.modelId).name else "",
                partialUntilMs = if (rec.transcription != TxState.DONE) rec.transcribedUntilMs else null,
            ),
            segments.map { TextSegment(it.startMs, it.endMs, it.text) },
            format, timestamps, rec.summary,
        )

    fun fileName(rec: Recording, ext: String): String = TranscriptFormatter.fileName(rec.title, rec.createdAt, ext)

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

    private fun tryWrite(uri: Uri, text: String): Boolean = try {
        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) }
        true
    } catch (e: Exception) {
        false
    }
}
