package it.registratoreai.summary

import it.registratoreai.RegistratoreApp
import it.registratoreai.data.SummaryState
import it.registratoreai.service.ServiceState
import it.registratoreai.text.Bookmarks
import it.registratoreai.text.LessonInfo
import it.registratoreai.text.TextSegment
import it.registratoreai.transcription.modelById
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Genera e salva il riassunto di una registrazione con il modello linguistico locale. */
class SummaryRunner(private val app: RegistratoreApp) {

    fun isReady(): Boolean = app.summaryModels.isInstalled(app.settings.current.summaryModelId)

    suspend fun run(id: Long) = withContext(Dispatchers.Default) {
        val dao = app.db.recordings()
        val rec = dao.get(id) ?: return@withContext
        val path = app.summaryModels.pathFor(app.settings.current.summaryModelId)
        if (path == null) {
            dao.setSummaryState(id, SummaryState.NONE)
            return@withContext
        }
        val segments = dao.segments(id).map { TextSegment(it.startMs, it.endMs, it.text) }
        if (segments.isEmpty()) {
            dao.setSummaryState(id, SummaryState.NONE)
            return@withContext
        }
        dao.setSummaryState(id, SummaryState.RUNNING)
        ServiceState.summary.value = id to 0f
        // Whisper e il modello linguistico insieme occuperebbero troppa memoria sul telefono
        app.releaseWhisper()
        try {
            Summarizer(path, app.settings.current.threads).use { s ->
                val info = LessonInfo(
                    rec.title, rec.course, rec.createdAt, rec.durationMs,
                    if (rec.modelId.isNotBlank()) modelById(rec.modelId).name else "",
                )
                val text = s.summarize(info, segments, app.settings.glossary(info.course), Bookmarks.parse(rec.bookmarks)) { p -> ServiceState.summary.value = id to p }
                dao.setSummary(id, text, SummaryState.DONE)
            }
            app.exporter.autoExport(id)
        } catch (e: CancellationException) {
            dao.setSummaryState(id, SummaryState.NONE)
            throw e
        } catch (e: Throwable) {
            dao.setSummaryState(id, SummaryState.ERROR)
        } finally {
            ServiceState.summary.value = null
        }
    }
}
