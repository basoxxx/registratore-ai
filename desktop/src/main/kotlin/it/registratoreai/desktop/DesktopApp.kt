package it.registratoreai.desktop

import it.registratoreai.audio.AudioMath
import it.registratoreai.audio.WavReader
import it.registratoreai.audio.WavWriter
import it.registratoreai.audio.samplesToMs
import it.registratoreai.text.TextSegment
import it.registratoreai.transcription.Chunker
import it.registratoreai.transcription.EtaEstimator
import it.registratoreai.transcription.SpeedStore
import it.registratoreai.transcription.ModelStore
import it.registratoreai.transcription.TextCleaner
import it.registratoreai.transcription.WhisperEngine
import it.registratoreai.transcription.WhisperLib
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

data class LiveRecording(val lessonId: String, val elapsedMs: Long = 0, val paused: Boolean = false, val level: Float = 0f)
data class TxProgress(val lessonId: String, val processedMs: Long, val totalMs: Long, val etaMs: Long? = null) {
    val fraction: Float get() = if (totalMs <= 0) 0f else (processedMs.toFloat() / totalMs).coerceIn(0f, 1f)
}
data class UpdateInfo(val version: String, val url: String)

/** Stato e logica della versione desktop (registrazione, coda di trascrizione, archivio). */
class DesktopApp {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val nativeOk = Native.load()
    val models = ModelStore(File(Paths.dataDir, "models"))
    private val engine by lazy { WhisperEngine() }

    private val _settings = MutableStateFlow(DesktopSettings.load())
    val settings = _settings.asStateFlow()
    private val store = LessonStore(File(_settings.value.libraryDir))

    private val _lessons = MutableStateFlow<List<Lesson>>(emptyList())
    val lessons = _lessons.asStateFlow()
    val recording = MutableStateFlow<LiveRecording?>(null)
    val progress = MutableStateFlow<TxProgress?>(null)
    val queue = MutableStateFlow<List<String>>(emptyList())
    val messages = MutableStateFlow<String?>(null)
    val update = MutableStateFlow<UpdateInfo?>(null)
    /** Velocità di trascrizione misurata (ms di audio per ms di calcolo), per i tempi stimati. */
    val speed = MutableStateFlow<Float?>(null)
    private val speeds = SpeedStore(File(Paths.dataDir, "speed.properties"))
    private var eta = EtaEstimator()

    fun speedFor(modelId: String): Float? = speed.value ?: speeds.get(modelId)

    /** Tempo stimato per finire tutte le trascrizioni (lezione corrente + coda). */
    fun totalEtaMs(): Long? {
        val sp = speedFor(_settings.value.modelId) ?: return null
        val p = progress.value
        val current = p?.let { it.etaMs ?: ((it.totalMs - it.processedMs) / sp).toLong() } ?: 0L
        val queued = queue.value.mapNotNull { lesson(it) }.sumOf { ((it.durationMs - it.transcribedUntilMs).coerceAtLeast(0) / sp).toLong() }
        return current + queued
    }

    private var recorder: Recorder? = null
    private val queueLock = Mutex()
    private var worker: Job? = null
    @Volatile private var currentTx: String? = null

    val version: String = System.getProperty("app.version") ?: System.getProperty("jpackage.app-version") ?: "dev"

    init {
        reload()
        recover()
        if (_settings.value.checkUpdates) scope.launch { runCatching { checkUpdate() } }
    }

    fun lesson(id: String?): Lesson? = _lessons.value.firstOrNull { it.id == id }

    fun reload() {
        _lessons.value = store.loadAll()
    }

    fun updateSettings(block: (DesktopSettings) -> DesktopSettings) {
        val s = block(_settings.value)
        s.save()
        _settings.value = s
        if (File(s.libraryDir) != store.root) {
            store.root = File(s.libraryDir)
            reload()
        }
    }

    /** Applica una modifica a una lezione, la salva su disco e aggiorna l'interfaccia. */
    private fun mutate(id: String, block: (Lesson) -> Lesson): Lesson? {
        var result: Lesson? = null
        _lessons.update { list ->
            list.map { if (it.id == id) block(it).also { n -> result = n } else it }
        }
        // Salviamo sempre la versione più recente (più thread possono modificare la stessa lezione)
        result?.let { store.save(lesson(id) ?: it, _settings.value.timestamps) }
        return result
    }

    /** Dopo una chiusura improvvisa: ripara l'audio e riprende le trascrizioni lasciate a metà. */
    private fun recover() {
        for (l in _lessons.value) {
            if (l.recording) {
                val ms = WavWriter.repair(l.audio)
                mutate(l.id) { it.copy(recording = false, durationMs = ms) }
            }
        }
        _lessons.value.filter { it.status == TxStatus.QUEUED || it.status == TxStatus.RUNNING }
            .sortedBy { it.createdAt }
            .forEach { enqueue(it.id) }
    }

    // ------------------------------------------------------------------ Registrazione

    fun startRecording(title: String, course: String): String? {
        if (recording.value != null) return recording.value?.lessonId
        val now = System.currentTimeMillis()
        val dir = store.newLessonDir(title, now)
        val s = _settings.value
        val lesson = Lesson(dir = dir, title = title, course = course, createdAt = now, recording = true,
            modelId = s.modelId, language = s.language)
        store.save(lesson, s.timestamps)
        _lessons.update { listOf(lesson) + it }
        recording.value = LiveRecording(lesson.id)
        val rec = Recorder(
            lesson.audio,
            onProgress = { ms, level ->
                recording.update { it?.copy(elapsedMs = ms, level = level) }
            },
            onCheckpoint = { ms -> mutate(lesson.id) { it.copy(durationMs = ms) } },
            onError = { msg ->
                messages.value = msg
                scope.launch { stopRecording() }
            },
        )
        recorder = rec
        rec.start()
        if (s.liveTranscription && models.isInstalled(s.modelId)) enqueue(lesson.id, front = true)
        return lesson.id
    }

    fun setPaused(paused: Boolean) {
        recorder?.paused = paused
        recording.update { it?.copy(paused = paused, level = 0f) }
    }

    fun stopRecording() {
        val live = recording.value ?: return
        val rec = recorder ?: return
        recorder = null
        rec.stop()
        mutate(live.lessonId) { it.copy(recording = false, durationMs = rec.elapsedMs) }
        recording.value = null
        val l = lesson(live.lessonId) ?: return
        val s = _settings.value
        val queued = currentTx == l.id || l.id in queue.value
        if (!queued && s.autoTranscribe && l.status == TxStatus.NONE && models.isInstalled(s.modelId)) enqueue(l.id)
    }

    // ------------------------------------------------------------------ Import / modifica

    fun importAudio(file: File): String? = try {
        val now = System.currentTimeMillis()
        val title = file.nameWithoutExtension
        val dir = store.newLessonDir(title, now).apply { mkdirs() }
        val ms = AudioImport.toWav16k(file, File(dir, "audio.wav"))
        val lesson = Lesson(dir = dir, title = title, createdAt = now, durationMs = ms)
        store.save(lesson, _settings.value.timestamps)
        _lessons.update { listOf(lesson) + it }
        if (models.isInstalled(_settings.value.modelId)) enqueue(lesson.id)
        lesson.id
    } catch (e: Exception) {
        messages.value = "Formato non supportato: importa un file WAV o AIFF (${e.message})"
        null
    }

    fun rename(id: String, title: String, course: String) {
        mutate(id) { it.copy(title = title, course = course) }
    }

    fun delete(id: String) {
        scope.launch {
            cancel(id)
            val l = lesson(id) ?: return@launch
            l.dir.deleteRecursively()
            _lessons.update { list -> list.filter { it.id != id } }
        }
    }

    // ------------------------------------------------------------------ Trascrizione

    fun transcribe(id: String, restart: Boolean) {
        if (restart) mutate(id) { it.copy(segments = emptyList(), transcribedUntilMs = 0, status = TxStatus.NONE, error = null) }
        enqueue(id)
    }

    fun enqueue(id: String, front: Boolean = false) {
        scope.launch {
            var preempt: String? = null
            queueLock.withLock {
                if (currentTx == id || id in queue.value) return@launch
                queue.value = if (front) listOf(id) + queue.value else queue.value + id
                if (front && currentTx != null) preempt = currentTx
            }
            mutate(id) { it.copy(status = TxStatus.QUEUED, error = null) }
            preempt?.let { old ->
                // La lezione che si sta registrando ha la precedenza
                worker?.let { it.cancel(); abortNative(); it.join() }
                queueLock.withLock { if (old !in queue.value) queue.value = queue.value + old }
                mutate(old) { it.copy(status = TxStatus.QUEUED) }
            }
            ensureWorker()
        }
    }

    fun cancel(id: String) {
        scope.launch {
            queueLock.withLock { queue.value = queue.value - id }
            if (currentTx == id) {
                worker?.let { it.cancel(); abortNative(); it.join() }
                currentTx = null
                progress.value = null
            }
            mutate(id) { it.copy(status = TxStatus.NONE) }
            if (queue.value.isNotEmpty()) ensureWorker()
        }
    }

    private fun ensureWorker() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            while (true) {
                val next = queueLock.withLock {
                    queue.value.firstOrNull()?.also { queue.value = queue.value.drop(1); currentTx = it }
                } ?: break
                try {
                    run(next)
                } catch (e: CancellationException) {
                    currentTx = null
                    throw e
                } catch (e: Throwable) {
                    mutate(next) { it.copy(status = TxStatus.ERROR, error = e.message ?: e.javaClass.simpleName) }
                }
                currentTx = null
                progress.value = null
            }
        }
    }

    private suspend fun run(id: String) {
        val s = _settings.value
        val start = lesson(id) ?: return
        if (!nativeOk) error(Native.error ?: "Libreria di trascrizione non disponibile")
        val path = models.pathFor(s.modelId) ?: error("Nessun modello scaricato: aprilo dalle Impostazioni.")
        if (!engine.ensureLoaded(path)) error("Impossibile caricare il modello: riscaricalo dalle Impostazioni.")
        var lesson = mutate(id) { it.copy(status = TxStatus.RUNNING, modelId = s.modelId, language = s.language) } ?: return

        eta = EtaEstimator(speeds.get(s.modelId))
        speed.value = eta.speed
        var offsetMs = start.transcribedUntilMs
        var segments = lesson.segments.filter { it.startMs < offsetMs }
        var promptTail = segments.takeLast(6).joinToString(" ") { it.text }.takeLast(200)
        var lastText = segments.lastOrNull()?.text

        WavReader(lesson.audio).use { reader ->
            while (true) {
                coroutineContext.ensureActive()
                val live = recording.value?.lessonId == id
                val samples = when (val c = Chunker.nextChunk(reader, offsetMs, live)) {
                    is Chunker.Chunk.Audio -> c.samples
                    Chunker.Chunk.End -> break
                    Chunker.Chunk.Wait -> {
                        progress.value = progressOf(id, offsetMs, samplesToMs(reader.availableSamples()))
                        delay(2_000)
                        continue
                    }
                }
                val chunkStart = offsetMs
                val lenMs = samplesToMs(samples.size.toLong())
                if (AudioMath.rms(samples) > Chunker.SILENCE_RMS) {
                    val prompt = listOf(lesson.course, promptTail).filter { it.isNotBlank() }.joinToString(". ")
                    val t0 = System.currentTimeMillis()
                    val raw = engine.transcribe(samples, s.language, prompt.ifBlank { null }, s.threads)
                    eta.record(lenMs, System.currentTimeMillis() - t0)
                    speeds.put(s.modelId, eta.speed)
                    speed.value = eta.speed
                    coroutineContext.ensureActive()
                    raw ?: error("Errore durante la trascrizione")
                    val fresh = mutableListOf<TextSegment>()
                    for (r in raw) {
                        val text = TextCleaner.clean(r.text) ?: continue
                        if (lastText != null && text.equals(lastText, ignoreCase = true)) continue
                        lastText = text
                        fresh += TextSegment(chunkStart + r.startMs.coerceIn(0, lenMs), chunkStart + r.endMs.coerceIn(0, lenMs), text)
                    }
                    segments = segments + fresh
                    if (fresh.isNotEmpty()) promptTail = (promptTail + " " + fresh.joinToString(" ") { it.text }).takeLast(200)
                }
                offsetMs += lenMs
                val segs = segments
                val until = offsetMs
                lesson = mutate(id) { it.copy(segments = segs, transcribedUntilMs = until) } ?: return
                progress.value = progressOf(id, offsetMs, samplesToMs(reader.availableSamples()))
            }
        }
        mutate(id) { it.copy(status = TxStatus.DONE) }
    }

    private fun progressOf(id: String, processed: Long, total: Long) =
        TxProgress(id, processed, total, eta.etaMs(total - processed))

    // ------------------------------------------------------------------ Aggiornamenti

    suspend fun checkUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        val conn = URL("https://api.github.com/repos/basoxxx/registratore-ai/releases/latest").openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        if (conn.responseCode != 200) return@withContext null
        val json = org.json.JSONObject(conn.inputStream.bufferedReader().readText())
        val tag = json.getString("tag_name").removePrefix("v")
        val latest = tag.substringAfterLast('.').toIntOrNull() ?: return@withContext null
        val mine = version.substringAfterLast('.').toIntOrNull() ?: return@withContext null
        val info = if (latest > mine) UpdateInfo(tag, json.getString("html_url")) else null
        update.value = info
        info
    }

    private fun abortNative() {
        if (nativeOk) WhisperLib.requestAbort(true)
    }

    fun shutdown() {
        stopRecording()
        abortNative()
    }
}
