package it.registratoreai.desktop

import it.registratoreai.text.ExportFormat
import it.registratoreai.text.LessonInfo
import it.registratoreai.text.TextSegment
import it.registratoreai.text.TranscriptFormatter
import it.registratoreai.transcription.modelById
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

enum class TxStatus { NONE, QUEUED, RUNNING, DONE, ERROR }

/** Una lezione = una cartella con audio.wav, lezione.json e il file .md sempre aggiornato. */
data class Lesson(
    val dir: File,
    val title: String,
    val course: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val durationMs: Long = 0,
    val recording: Boolean = false,
    val status: TxStatus = TxStatus.NONE,
    val transcribedUntilMs: Long = 0,
    val modelId: String = "",
    val language: String = "it",
    val error: String? = null,
    val segments: List<TextSegment> = emptyList(),
) {
    val id: String get() = dir.name
    val audio: File get() = File(dir, "audio.wav")
    val markdownFile: File get() = File(dir, "${dir.name}.md")

    fun info() = LessonInfo(
        title = title, course = course, createdAt = createdAt, durationMs = durationMs,
        modelName = if (modelId.isNotBlank()) modelById(modelId).name else "",
        partialUntilMs = if (status != TxStatus.DONE) transcribedUntilMs else null,
    )

    fun export(format: ExportFormat, timestamps: Boolean) = TranscriptFormatter.build(info(), segments, format, timestamps)
}

class LessonStore(var root: File) {
    fun loadAll(): List<Lesson> {
        root.mkdirs()
        return (root.listFiles() ?: emptyArray())
            .filter { File(it, "lezione.json").isFile }
            .mapNotNull { runCatching { load(it) }.getOrNull() }
            .sortedByDescending { it.createdAt }
    }

    fun load(dir: File): Lesson {
        val j = JSONObject(File(dir, "lezione.json").readText())
        val segs = j.optJSONArray("segments") ?: JSONArray()
        return Lesson(
            dir = dir,
            title = j.getString("title"),
            course = j.optString("course", ""),
            createdAt = j.getLong("createdAt"),
            durationMs = j.optLong("durationMs"),
            recording = j.optBoolean("recording"),
            status = runCatching { TxStatus.valueOf(j.optString("status", "NONE")) }.getOrDefault(TxStatus.NONE),
            transcribedUntilMs = j.optLong("transcribedUntilMs"),
            modelId = j.optString("modelId", ""),
            language = j.optString("language", "it"),
            error = j.optString("error").ifBlank { null },
            segments = (0 until segs.length()).map {
                val s = segs.getJSONObject(it)
                TextSegment(s.getLong("start"), s.getLong("end"), s.getString("text"))
            },
        )
    }

    /** Salva i metadati e riscrive il Markdown (scrittura atomica: niente file corrotti). */
    @Synchronized
    fun save(lesson: Lesson, timestamps: Boolean) {
        lesson.dir.mkdirs()
        val j = JSONObject()
            .put("title", lesson.title).put("course", lesson.course).put("createdAt", lesson.createdAt)
            .put("durationMs", lesson.durationMs).put("recording", lesson.recording)
            .put("status", lesson.status.name).put("transcribedUntilMs", lesson.transcribedUntilMs)
            .put("modelId", lesson.modelId).put("language", lesson.language).put("error", lesson.error ?: "")
            .put("segments", JSONArray(lesson.segments.map {
                JSONObject().put("start", it.startMs).put("end", it.endMs).put("text", it.text)
            }))
        writeAtomic(File(lesson.dir, "lezione.json"), j.toString(1))
        writeAtomic(lesson.markdownFile, lesson.export(ExportFormat.MARKDOWN, timestamps))
    }

    fun newLessonDir(title: String, createdAt: Long): File {
        root.mkdirs()
        val stamp = SimpleDateFormat("yyyy-MM-dd HHmm", Locale.ITALY).format(Date(createdAt))
        val safe = title.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").trim().take(60)
        var dir = File(root, "$stamp $safe")
        var n = 2
        while (dir.exists()) dir = File(root, "$stamp $safe ($n)").also { n++ }
        return dir
    }

    private fun writeAtomic(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}

/** Cartelle dell'app secondo le convenzioni di ciascun sistema operativo. */
object Paths {
    private val os = System.getProperty("os.name").lowercase()
    val isMac = os.contains("mac")
    val isWindows = os.contains("win")
    private val home = File(System.getProperty("user.home"))

    val dataDir: File = when {
        isMac -> File(home, "Library/Application Support/RegistratoreLezioni")
        isWindows -> File(System.getenv("APPDATA") ?: home.path, "RegistratoreLezioni")
        else -> File(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share", "registratore-lezioni")
    }.apply { mkdirs() }

    val defaultLibrary: File = File(home, "Documents/Registratore Lezioni")
}

data class DesktopSettings(
    val modelId: String = "base-q5_1",
    val language: String = "it",
    val liveTranscription: Boolean = true,
    val autoTranscribe: Boolean = true,
    val threads: Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 8),
    val timestamps: Boolean = true,
    val libraryDir: String = Paths.defaultLibrary.path,
    val checkUpdates: Boolean = true,
) {
    companion object {
        private val file = File(Paths.dataDir, "settings.properties")

        fun load(): DesktopSettings {
            val d = DesktopSettings()
            if (!file.exists()) return d
            val p = Properties().apply { file.inputStream().use { load(it) } }
            return DesktopSettings(
                modelId = p.getProperty("modelId", d.modelId),
                language = p.getProperty("language", d.language),
                liveTranscription = p.getProperty("live", "${d.liveTranscription}").toBoolean(),
                autoTranscribe = p.getProperty("autoTranscribe", "${d.autoTranscribe}").toBoolean(),
                threads = p.getProperty("threads", "${d.threads}").toIntOrNull() ?: d.threads,
                timestamps = p.getProperty("timestamps", "${d.timestamps}").toBoolean(),
                libraryDir = p.getProperty("libraryDir", d.libraryDir),
                checkUpdates = p.getProperty("checkUpdates", "${d.checkUpdates}").toBoolean(),
            )
        }
    }

    fun save() {
        val p = Properties()
        p["modelId"] = modelId; p["language"] = language; p["live"] = "$liveTranscription"
        p["autoTranscribe"] = "$autoTranscribe"; p["threads"] = "$threads"; p["timestamps"] = "$timestamps"
        p["libraryDir"] = libraryDir; p["checkUpdates"] = "$checkUpdates"
        file.outputStream().use { p.store(it, "Registratore Lezioni") }
    }
}
