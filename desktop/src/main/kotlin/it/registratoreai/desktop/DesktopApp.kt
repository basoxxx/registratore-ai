package it.registratoreai.desktop

import it.registratoreai.audio.AudioMath
import it.registratoreai.audio.WavReader
import it.registratoreai.audio.WavWriter
import it.registratoreai.audio.samplesToMs
import it.registratoreai.text.TextSegment
import it.registratoreai.summary.LlamaLib
import it.registratoreai.summary.SUMMARY_MODELS
import it.registratoreai.summary.Summarizer
import it.registratoreai.transcription.CUSTOM_MODEL_ID
import it.registratoreai.transcription.Chunker
import it.registratoreai.transcription.QualityCheck
import it.registratoreai.transcription.crossCheckModelFor
import it.registratoreai.transcription.modelById
import it.registratoreai.transcription.EtaEstimator
import it.registratoreai.transcription.beamSizeFor
import it.registratoreai.transcription.canonicalModelId
import it.registratoreai.transcription.SpeechPacker
import it.registratoreai.transcription.WhisperVad
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
    val summaryModels = ModelStore(File(Paths.dataDir, "llm"), SUMMARY_MODELS)
    /** Riassunto in corso: id lezione e avanzamento. */
    val summaryProgress = MutableStateFlow<Pair<String, Float>?>(null)
    private val summaryQueue = ArrayDeque<String>()
    private val engine by lazy { WhisperEngine() }
    /** Secondo modello per la verifica incrociata dei tratti sospetti. */
    private val backupEngine by lazy { WhisperEngine() }

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
    private var vadInstance: WhisperVad? = null

    private fun vad(): WhisperVad? {
        vadInstance?.let { return it }
        val model = WhisperVad.extractModel(File(Paths.dataDir, "vad")) ?: return null
        return WhisperVad(model.absolutePath).takeIf { it.ok }?.also { vadInstance = it }
    }

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
        _lessons.value.filter { it.summaryStatus == TxStatus.QUEUED || it.summaryStatus == TxStatus.RUNNING }
            .forEach { summarize(it.id) }
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
        SleepGuard.acquire("recording") // in standby la registrazione si fermerebbe
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
        SleepGuard.release("recording")
        val l = lesson(live.lessonId) ?: return
        val s = _settings.value
        val queued = currentTx == l.id || l.id in queue.value
        if (!queued && s.autoTranscribe && l.status == TxStatus.NONE &&
            (models.isInstalled(s.modelId) || models.isInstalled(s.finalModelId))
        ) transcribe(l.id, restart = false)
        if (!queued && l.status == TxStatus.DONE) afterTranscription(l.id)
    }

    // ------------------------------------------------------------------ Import / modifica

    fun importAudio(file: File): String? {
        val now = System.currentTimeMillis()
        val title = file.nameWithoutExtension
        val dir = store.newLessonDir(title, now).apply { mkdirs() }
        return try {
            importInto(dir, file, title, now)
        } catch (e: Exception) {
            dir.deleteRecursively()
            messages.value = "Impossibile leggere ${file.name}: formato non supportato (${e.message})"
            null
        }
    }

    private fun importInto(dir: File, file: File, title: String, now: Long): String {
        val ms = AudioImport.toWav16k(file, File(dir, "audio.wav"))
        val lesson = Lesson(dir = dir, title = title, createdAt = now, durationMs = ms)
        store.save(lesson, _settings.value.timestamps)
        _lessons.update { listOf(lesson) + it }
        if (models.isInstalled(_settings.value.modelId) || models.isInstalled(_settings.value.finalModelId)) transcribe(lesson.id, restart = false)
        return lesson.id
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

    /**
     * restart = false: trascrive (direttamente con il modello finale se è scaricato);
     * restart = true: ritrascrive con il modello finale mantenendo visibile il testo attuale.
     */
    fun transcribe(id: String, restart: Boolean) {
        val s = _settings.value
        val l = lesson(id) ?: return
        val finalReady = models.isInstalled(s.finalModelId)
        if (restart || (finalReady && l.segments.isEmpty())) {
            if (finalReady) mutate(id) { it.copy(pass = 2, transcribedUntilMs = 0, status = TxStatus.QUEUED, error = null) }
            else mutate(id) { it.copy(segments = emptyList(), transcribedUntilMs = 0, status = TxStatus.NONE, error = null) }
        }
        enqueue(id)
    }

    /** Dopo l'anteprima parte la trascrizione finale con il modello grande (se diverso e scaricato). */
    private fun afterTranscription(id: String) {
        val l = lesson(id) ?: return
        if (l.status != TxStatus.DONE || l.recording) return
        val s = _settings.value
        if (l.pass == 1 && s.refineAfter && models.isInstalled(s.finalModelId) &&
            canonicalModelId(l.modelId) != canonicalModelId(s.finalModelId)
        ) {
            mutate(id) { it.copy(pass = 2, transcribedUntilMs = 0, status = TxStatus.QUEUED) }
            enqueue(id)
        } else if (s.autoSummary && summaryModels.isInstalled(s.summaryModelId) && l.summaryStatus == TxStatus.NONE) {
            summarize(id)
        }
    }

    // ------------------------------------------------------------------ Riassunto

    fun summarize(id: String) {
        if (!summaryModels.isInstalled(_settings.value.summaryModelId)) return
        scope.launch {
            queueLock.withLock {
                if (id in summaryQueue || summaryProgress.value?.first == id) return@launch
                summaryQueue.addLast(id)
            }
            mutate(id) { it.copy(summaryStatus = TxStatus.QUEUED) }
            ensureWorker()
        }
    }

    private suspend fun runSummary(id: String) {
        val l = lesson(id) ?: return
        val path = summaryModels.pathFor(_settings.value.summaryModelId) ?: return
        if (l.segments.isEmpty()) { mutate(id) { it.copy(summaryStatus = TxStatus.NONE) }; return }
        mutate(id) { it.copy(summaryStatus = TxStatus.RUNNING) }
        summaryProgress.value = id to 0f
        engine.release() // libera la memoria di Whisper
        backupEngine.release()
        try {
            Summarizer(path, _settings.value.threads).use { s ->
                val text = s.summarize(l.info(), l.segments) { p -> summaryProgress.value = id to p }
                mutate(id) { it.copy(summary = text, summaryStatus = TxStatus.DONE) }
            }
        } catch (e: CancellationException) {
            mutate(id) { it.copy(summaryStatus = TxStatus.NONE) }
            throw e
        } catch (e: Throwable) {
            mutate(id) { it.copy(summaryStatus = TxStatus.ERROR, error = e.message) }
        } finally {
            summaryProgress.value = null
        }
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
            // Una registrazione che inizia ha la precedenza anche su un riassunto in corso
            val runningSummary = summaryProgress.value?.first
            if (front && runningSummary != null) {
                worker?.let { it.cancel(); if (nativeOk) LlamaLib.requestAbort(true); it.join() }
                queueLock.withLock { summaryQueue.addFirst(runningSummary) }
                mutate(runningSummary) { it.copy(summaryStatus = TxStatus.QUEUED) }
            }
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
            queueLock.withLock { queue.value = queue.value - id; summaryQueue.remove(id) }
            if (summaryProgress.value?.first == id) {
                worker?.let { it.cancel(); if (nativeOk) LlamaLib.requestAbort(true); it.join() }
                summaryProgress.value = null
            }
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
            if (_settings.value.keepAwake) SleepGuard.acquire("work")
            try { workLoop() } finally { SleepGuard.release("work") }
        }
    }

    private suspend fun workLoop() {
        run {
            while (true) {
                val next = queueLock.withLock {
                    queue.value.firstOrNull()?.also { queue.value = queue.value.drop(1); currentTx = it }
                } ?: break
                try {
                    run(next)
                    currentTx = null
                    afterTranscription(next)
                } catch (e: CancellationException) {
                    currentTx = null
                    throw e
                } catch (e: Throwable) {
                    mutate(next) { it.copy(status = TxStatus.ERROR, error = e.message ?: e.javaClass.simpleName) }
                }
                currentTx = null
                progress.value = null
            }
            // Riassunti quando non ci sono trascrizioni in coda e non si registra
            while (recording.value == null) {
                val next = queueLock.withLock { if (queue.value.isEmpty()) summaryQueue.removeFirstOrNull() else null } ?: break
                runSummary(next)
            }
        }
    }

    private suspend fun run(id: String) {
        val s = _settings.value
        val start = lesson(id) ?: return
        if (!nativeOk) error(Native.error ?: "Libreria di trascrizione non disponibile")
        // Passaggio finale: modello grande che sostituisce man mano il testo dell'anteprima
        val isFinal = start.pass == 2
        val modelId = if (isFinal && models.isInstalled(s.finalModelId)) s.finalModelId else s.modelId
        val beam = beamSizeFor(modelId)
        val path = models.pathFor(modelId) ?: error("Nessun modello scaricato: aprilo dalle Impostazioni.")
        if (!engine.ensureLoaded(path)) error("Impossibile caricare il modello: riscaricalo dalle Impostazioni.")
        var lesson = mutate(id) {
            it.copy(status = TxStatus.RUNNING, modelId = modelId, language = s.language, pass = if (it.pass == 0) 1 else it.pass)
        } ?: return

        eta = EtaEstimator(speeds.get(modelId))
        speed.value = eta.speed
        // Dopo la lezione: VAD + impacchettamento del parlato (vedi SpeechPacker)
        val packer = vad()?.let { SpeechPacker(it) } ?: SpeechPacker { null }
        var offsetMs = start.transcribedUntilMs
        // Ripresa dopo un'interruzione: si scarta quanto prodotto oltre l'ultimo punto salvato
        // (nel passaggio finale solo il testo "finale": l'anteprima resta finché non è sostituita)
        var segments = if (isFinal) lesson.segments.filter { it.pass < 2 || it.startMs < offsetMs }
        else lesson.segments.filter { it.startMs < offsetMs }
        val context = segments.filter { !isFinal || it.pass == 2 }.takeLast(6)
        var promptTail = context.joinToString(" ") { it.text }.takeLast(200)
        var lastText = context.lastOrNull()?.text
        /** Nel passaggio finale toglie l'anteprima nel tratto [from, to) appena ritrascritto. */
        fun dropDraft(list: List<TextSegment>, from: Long, to: Long) =
            if (!isFinal) list else list.filterNot { it.pass < 2 && it.startMs < to && it.endMs > from }

        WavReader(lesson.audio).use { reader ->
            while (true) {
                coroutineContext.ensureActive()
                val live = recording.value?.lessonId == id
                val window = when (val c = packer.next(reader, offsetMs, live)) {
                    is Chunker.Chunk.Audio -> c.window
                    is Chunker.Chunk.Skip -> {
                        segments = dropDraft(segments, offsetMs, c.endMs)
                        offsetMs = c.endMs
                        val until = offsetMs
                        val segs = segments
                        lesson = mutate(id) { it.copy(segments = segs, transcribedUntilMs = until) } ?: return
                        continue
                    }
                    Chunker.Chunk.End -> break
                    Chunker.Chunk.Wait -> {
                        progress.value = progressOf(id, offsetMs, samplesToMs(reader.availableSamples()))
                        delay(2_000)
                        continue
                    }
                }
                val samples = window.samples
                val chunkStart = offsetMs
                if (!Chunker.isSilent(samples)) {
                    val prompt = listOf(lesson.course, promptTail).filter { it.isNotBlank() }.joinToString(". ")
                    val t0 = System.currentTimeMillis()
                    var raw = engine.transcribe(samples, s.language, prompt.ifBlank { null }, s.threads, beam)
                    if (isFinal && raw != null) {
                        // Tratto sospetto (ripetizioni, troppo poco testo…): seconda opinione dall'altro modello grande
                        val speech = window.pieces.sumOf { it.lenMs }
                        val first = QualityCheck.evaluate(raw.map { it.text }, speech)
                        val otherPath = if (first.suspicious) crossCheckModelFor(modelId)?.let { models.pathFor(it) } else null
                        if (otherPath != null && backupEngine.ensureLoaded(otherPath)) {
                            val alt = backupEngine.transcribe(samples, s.language, prompt.ifBlank { null }, s.threads, 5)
                            if (alt != null && QualityCheck.secondIsBetter(first, QualityCheck.evaluate(alt.map { it.text }, speech))) raw = alt
                        }
                    }
                    eta.record(window.endMs - chunkStart, System.currentTimeMillis() - t0)
                    speeds.put(modelId, eta.speed)
                    speed.value = eta.speed
                    coroutineContext.ensureActive()
                    raw ?: error("Errore durante la trascrizione")
                    val fresh = mutableListOf<TextSegment>()
                    for (r in raw) {
                        val text = TextCleaner.clean(r.text) ?: continue
                        if (lastText != null && text.equals(lastText, ignoreCase = true)) continue
                        lastText = text
                        fresh += TextSegment(window.toSourceMs(r.startMs), window.toSourceMs(r.endMs), text, if (isFinal) 2 else 1)
                    }
                    segments = (dropDraft(segments, chunkStart, window.endMs) + fresh).sortedBy { it.startMs }
                    if (fresh.isNotEmpty()) promptTail = (promptTail + " " + fresh.joinToString(" ") { it.text }).takeLast(200)
                }
                offsetMs = window.endMs
                val segs = segments
                val until = offsetMs
                lesson = mutate(id) { it.copy(segments = segs, transcribedUntilMs = until) } ?: return
                progress.value = progressOf(id, offsetMs, samplesToMs(reader.availableSamples()))
            }
        }
        mutate(id) { l -> l.copy(status = TxStatus.DONE, segments = if (isFinal) l.segments.filter { it.pass == 2 } else l.segments) }
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
        SleepGuard.release("work")
        SleepGuard.release("recording")
    }

    /** Importa un modello Whisper scelto dall'utente; false se il file non è valido. */
    suspend fun importModel(file: File): Boolean {
        val ok = models.import(modelById(CUSTOM_MODEL_ID), file.inputStream())
        if (ok) updateSettings { it.copy(finalModelId = CUSTOM_MODEL_ID) }
        return ok
    }
}
